package org.cikit.oci.jail

import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.cikit.libjail.*
import org.cikit.oci.*
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.io.path.*

private const val ociJailStateFile = "state.json"
private const val ociJailStateLock = "state.lock"

private const val OCI_ANNOTATION_VNET = "org.freebsd.jail.vnet"
private const val OCI_ANNOTATION_ALLOW = "org.freebsd.jail.allow"
private const val OCI_ANNOTATION_SECURE_LEVEL = "org.freebsd.jail.securelevel"

private val json = Json {
    encodeDefaults = false
    explicitNulls = false
    ignoreUnknownKeys = true
}

class OciJailInterceptor : GenericInterceptor(
    name = "intercept-ocijail",
    create = Create(),
    start = Start(),
    delete = Delete()
) {
    val root by option()
        .help("Override default location for state database").path()
        .default(Path("/var/run/ocijail"))

    val interceptRcJail by option(envvar = "INTERCEPT_RC_JAIL")
        .help("Path to intercept-rcjail command")
        .required()

    val devFsRulesetVnet by option()
        .help("Use the specified devfs ruleset for vnet jails")
        .long()

    val devFsRulesetVmm by option()
        .help("Use the specified devfs ruleset for vmm jails")
        .long()

    override fun rebuildGlobalOptions(): kotlin.collections.List<String> {
        return super.rebuildGlobalOptions() + listOf("--root=$root")
    }

    fun readOciJailState(containerId: String): JsonObject? {
        val lockFile = root / containerId / ociJailStateLock
        if (lockFile.parent?.exists() != true) {
            return null
        }
        val stateFile = root / containerId / ociJailStateFile
        val stateJson = FileChannel.open(
            lockFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        ).use { fc ->
            fc.lock().use { lock ->
                require(lock.isValid) { "error locking $lockFile" }
                if (stateFile.isReadable()) {
                    stateFile.readText()
                } else {
                    return null
                }
            }
        }
        return if (stateJson.isBlank()) {
            null
        } else {
            json.decodeFromString(stateJson)
        }
    }

    companion object {

        private const val ENV_OCI_RUNTIME_BIN = "INTERCEPT_OCI_RUNTIME_BIN"
        private const val ENV_INTERCEPT_RC_JAIL = "INTERCEPT_RC_JAIL"

        @JvmStatic
        fun main(args: Array<String>) {
            val finalArgs = mutableListOf<String>()
            if (System.getenv(ENV_OCI_RUNTIME_BIN) == null &&
                args.none {
                    it == "--oci-runtime-bin" ||
                            it.startsWith("--oci-runtime-bin=")
                })
            {
                finalArgs.add("--oci-runtime-bin=/usr/local/bin/ocijail")
            }
            if (System.getenv(ENV_INTERCEPT_RC_JAIL) == null &&
                args.none {
                    it == "--intercept-rc-jail" ||
                            it.startsWith("--intercept-rc-jail=")
                })
            {
                val p = Path(System.getProperty("java.home")) /
                        "bin" /
                        "intercept-rcjail"
                if (p.isExecutable() || (p.parent / "${p.name}.exe").exists()) {
                    finalArgs.add("--intercept-rc-jail=${p.pathString}")
                }
            }
            if (finalArgs.isEmpty()) {
                OciJailInterceptor().main(args)
            } else {
                finalArgs.addAll(args)
                OciJailInterceptor().main(finalArgs)
            }
        }
    }
}

private class Create : CreateCommand() {
    val runtime: OciJailInterceptor by requireObject()

    override fun run() {
        if (runtime.readContainerState(containerId) != null) {
            throw PrintMessage(
                "container $containerId exists",
                statusCode = 0,
                printError = true
            )
        }
        val state = runtime.logger.saveState() + buildJsonObject {
            put("bundle", bundle.toAbsolutePath().pathString)
        }
        val ociConfigFile = bundle.resolve("config.json")
        val ociConfigJson = ociConfigFile.readText()
        val ociConfig = json.decodeFromString<OciConfig>(ociConfigJson)
        runtime.logger.info("loaded oci config: $ociConfigJson")

        val patchedConfig = patchConfig(ociConfig)
        val patchedConfigJson = json.encodeToString(patchedConfig)
        ociConfigFile.writeText(patchedConfigJson)
        runtime.logger.info("patched oci config: $patchedConfigJson")

        if (patchedConfig.annotations[OCI_ANNOTATION_VNET] == "new") {
            startNetGraph()
        }

        val finalState = state + buildJsonObject {
            put("oci", json.encodeToJsonElement(patchedConfig))
        }

        runtime.lockLocalStateDir {
            runtime.writeContainerState(containerId, state)
            try {
                runtime.runHooks(Hooks::precreate, this, finalState)
            } catch (ex: Throwable) {
                runtime.deleteContainerState(containerId)
                throw ex
            }
        }

        runtime.callOciRuntime(this)
        //TBD we could get jail parameters for the postcreate hook
        runtime.runHooks(Hooks::postcreate, this, finalState)
    }

    private fun patchConfig(containerConfig: OciConfig): OciConfig {
        var patchedConfig = containerConfig
        if (isJailed()) {
            // devfs rules / rulesets are not allowed inside the jail
            patchedConfig = patchedConfig.copy(
                mounts = patchedConfig.mounts.map { mount ->
                    if (mount.type == "devfs") {
                        mount.copy(options = emptyList())
                    } else {
                        mount
                    }
                }
            )

            // check if jail_mntinfo is available
            val mounted = readJailMountInfo()
            if (mounted == null) {
                //TBD fail here
                runtime.logger.warn(
                    "jail_mntinfo not available: removing nullfs mounts"
                )
                patchedConfig = patchedConfig.copy(
                    mounts = patchedConfig.mounts.filterNot { mount ->
                        mount.type == "nullfs" &&
                                !Path(mount.source).isDirectory()
                    }
                )
            }

            return patchedConfig
        }
        val devFsRuleset = when {
            patchedConfig.annotations["${OCI_ANNOTATION_ALLOW}.vmm"] == "1" -> {
                runtime.devFsRulesetVmm
            }

            patchedConfig.annotations[OCI_ANNOTATION_VNET] == "new" -> {
                runtime.devFsRulesetVnet
            }

            else -> null
        }
        if (devFsRuleset != null) {
            val mounts = patchedConfig.mounts.map { mount ->
                if (mount.type == "devfs") {
                    val options = mount.options.map { option ->
                        if (option.startsWith("ruleset=")) {
                            "ruleset=$devFsRuleset"
                        } else {
                            option
                        }
                    }
                    mount.copy(options = options)
                } else {
                    mount
                }
            }
            patchedConfig = patchedConfig.copy(mounts = mounts)
        }
        var devFsRulesets = listDevFsRulesets()
        if (devFsRulesets.isEmpty()) {
            restartDevFs()
            devFsRulesets = listDevFsRulesets()
        }
        for (mount in patchedConfig.mounts) {
            if (mount.type == "devfs") {
                val o = mount.options.lastOrNull {
                    it.startsWith("ruleset=")
                }
                if (o != null) {
                    val requestedSet = o.removePrefix("ruleset=").toLong()
                    require(requestedSet in devFsRulesets) {
                        "requested devfs ruleset not available: $o"
                    }
                }
            }
        }
        return patchedConfig
    }

    private fun startNetGraph() {
        val args = listOf("kldstat", "-qm", "netgraph")
        runtime.logger.trace(TraceEvent.Exec(args))
        val netGraphLoaded = ProcessBuilder(args)
            .inheritIO()
            .start()
            .waitFor() == 0
        if (netGraphLoaded) {
            runtime.logger.trace(TraceEvent.Exec("ngctl", "list"))
            val rc = ProcessBuilder("ngctl", "list")
                .inheritIO()
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .start()
                .apply { inputStream.use { it.readBytes() } }
                .waitFor()
            require(rc == 0) {
                "ngctl terminated with exit code $rc"
            }
        }
    }

    private fun listDevFsRulesets(): Set<Long> {
        val args = listOf("devfs", "rule", "showsets")
        runtime.logger.trace(TraceEvent.Exec(args))
        val p = ProcessBuilder(args)
            .inheritIO()
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .start()
        val installed = p.inputStream.use { String(it.readBytes()) }
        val rc = p.waitFor()
        require(rc == 0) { "devfs terminated with exit code $rc" }
        return installed.split("\n").mapNotNull {
            if (it.isBlank()) null else it.trim().toLongOrNull()
        }.toSet()
    }

    private fun restartDevFs() {
        val args = listOf("/etc/rc.d/devfs", "forcerestart")
        runtime.logger.trace(TraceEvent.Exec(args))
        val rc = ProcessBuilder(args).inheritIO().start().waitFor()
        require(rc == 0) { "devfs terminated with exit code $rc" }
    }
}

private class Start : StartCommand() {
    val runtime: OciJailInterceptor by requireObject()

    override fun run() {
        val state = runtime.readContainerState(containerId)
            ?: throw PrintMessage(
                "container '$containerId' not found",
                statusCode = 1,
                printError = true
            )

        runtime.logger.restoreState(JsonObject(state))

        val jail = runBlocking {
            val jails = readJailParameters()
            jails.singleOrNull { p ->
                p.name == containerId || p.jid == containerId.toIntOrNull()
            } ?: error("jail \"$containerId\" not found")
        }

        val bundle = state.getValue("bundle").jsonPrimitive.content
        val ociConfigFile = Path(bundle) / "config.json"
        val ociConfigJson = ociConfigFile.readText()
        val ociConfig = json.decodeFromString<OciConfig>(ociConfigJson)

        val hostUuid = buildString {
            append(containerId.substring(0, 8))
            append("-")
            append(containerId.substring(8, 12))
            append("-")
            append(containerId.substring(12, 16))
            append("-")
            append(containerId.substring(16, 20))
            append("-")
            append(containerId.substring(20, 32))
        }

        val hostId = with(MessageDigest.getInstance("MD5")) {
            update("$hostUuid\n".encodeToByteArray())
            val result = digest()
            ((result[0].toLong() and 0xFF) shl 24) or
                    ((result[1].toLong() and 0xFF) shl 16) or
                    ((result[2].toLong() and 0xFF) shl 8) or
                    (result[3].toLong() and 0xFF)
        }

        val allow = ociConfig.annotations.mapNotNull { (k, v) ->
            val match = k.removePrefix(OCI_ANNOTATION_ALLOW)
            if (match != k && match.startsWith(".")) {
                "allow$match" to v
            } else {
                null
            }
        }

        val secureLevel = ociConfig
            .annotations[OCI_ANNOTATION_SECURE_LEVEL]

        val updatedJailParameters: Map<String, String> = buildMap {
            put("host.hostid", hostId.toString())
            put("host.hostuuid", hostUuid)
            if (secureLevel != null) {
                put("securelevel", secureLevel)
            }
            putAll(allow)
        }

        runtime.logger.info("modifying jail parameters")

        runBlocking {
            modifyJailParameters(jail, updatedJailParameters)
        }

        val modifiedJailParameters = jail.parameters + buildJsonObject {
            for ((k, v) in updatedJailParameters) {
                put(k, JsonPrimitive(v))
            }
        }

        val finalState = state + buildJsonObject {
            put("oci", json.encodeToJsonElement(ociConfig))
            put("jail", JsonObject(modifiedJailParameters))
        }

        runtime.runHooks(Hooks::prestart, this, finalState)
        runtime.callOciRuntime(this)
        runtime.runHooks(Hooks::poststart, this, finalState)
    }
}

private class Delete : DeleteCommand() {
    val runtime: OciJailInterceptor by requireObject()

    override fun run() {
        val state = runtime.readContainerState(containerId)
        if (state != null) {
            runtime.logger.restoreState(JsonObject(state))
        }

        val ociJailState = runtime.readOciJailState(containerId)

        if (ociJailState != null) {
            val status = ociJailState["status"]
                ?.jsonPrimitive?.content
                ?: "unknown"
            val canDelete = when (status) {
                "stopped", "created" -> true
                "running" -> force
                else -> false
            }

            if (!canDelete) {
                throw PrintMessage(
                    "delete: container not in \"stopped\" or \"created\" " +
                            "state (currently $status)",
                    1,
                    printError = true
                )
            }
        }

        val jail = runBlocking {
            val jails = readJailParameters()
            jails.singleOrNull { p ->
                p.name == containerId || p.jid == containerId.toIntOrNull()
            }
        }

        val finalState = if (state != null && jail != null) {
            state + buildJsonObject {
                put("jail", jail.parameters)
            }
        } else {
            null
        }

        if (state != null) {
            runtime.runHooks(Hooks::predelete, this, finalState ?: state)
        }

        if (jail != null) {
            val cleanupArgs = buildList {
                add(runtime.interceptRcJail)
                runtime.logger.logFile?.let {
                    add("--log=$it")
                }
                runtime.logger.logFormat?.let {
                    add("--log-format=$it")
                }
                runtime.logger.logLevel?.let {
                    add("--log-level=$it")
                }
                add("cleanup")
                add("-j")
                add(jail.name)
            }
            runtime.logger.trace(TraceEvent.Exec(cleanupArgs))
            runtime.logger.close()
            val rc = try {
                ProcessBuilder(cleanupArgs)
                    .inheritIO()
                    .start()
                    .waitFor()
            } finally {
                runtime.logger.open()
            }
            if (rc != 0) {
                throw PrintMessage(
                    "delete failed", 1, printError = true
                )
            }
        }

        runtime.callOciRuntime(this)

        if (state != null) {
            runtime.runHooks(Hooks::postdelete, this, finalState ?: state)
            runtime.deleteContainerState(containerId)
        }
    }
}
