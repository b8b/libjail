package org.cikit.oci.jail

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.cikit.libjail.*
import org.cikit.oci.CreateCommand
import org.cikit.oci.DeleteCommand
import org.cikit.oci.GenericInterceptor
import org.cikit.oci.OciConfig
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.io.path.*
import kotlin.system.exitProcess

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
    name = "ocijail-interceptor",
    create = Create(),
    delete = Delete()
) {
    init {
        subcommands(CleanupCommand())
    }

    val root by option()
        .help("Override default location for state database").path()
        .default(Path("/var/run/ocijail"))

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
        @JvmStatic
        fun main(args: Array<String>) = OciJailInterceptor().main(
            listOf("--oci-runtime-bin=/usr/local/bin/ocijail") + args
        )
    }
}

private class CleanupCommand : CliktCommand("cleanup") {

    override fun help(context: Context): String {
        return context.theme.info("Cleanup the jail with the given id")
    }

    private val runtime: OciJailInterceptor by requireObject()

    private val jail by option("-j",
        help = "Unique identifier for the jail"
    ).required()

    override fun run() {
        runBlocking {
            val jails = readJailParameters()
            val parameters = jails.singleOrNull { p ->
                p.name == jail || p.jid == jail.toIntOrNull()
            }
            parameters?.let { p ->
                try {
                    val rc = cleanup(runtime.logger, p)
                    exitProcess(rc)
                } catch (ex: Throwable) {
                    throw PrintMessage(
                        "cleanup failed: ${ex.message}",
                        1,
                        printError = true
                    )
                }
            } ?: throw PrintMessage(
                "jail \"$jail\" not found",
                1,
                printError = true
            )
        }
    }
}

private class Create : CreateCommand() {
    val runtime: OciJailInterceptor by requireObject()

    override fun run() {
        val interceptorStateIn = runtime.readInterceptorState(containerId)
        val interceptorStateOut = buildJsonObject {
            if (interceptorStateIn != null) {
                for ((k, v) in interceptorStateIn.entries) {
                    put(k, v)
                }
            }
            for ((k, v) in runtime.logger.saveState()) {
                put(k, v)
            }
        }
        try {
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

            val allow = patchedConfig.annotations.mapNotNull { (k, v) ->
                val match = k.removePrefix(OCI_ANNOTATION_ALLOW)
                if (match != k && match.startsWith(".")) {
                    "allow$match" to v
                } else {
                    null
                }
            }

            val secureLevel = patchedConfig
                .annotations[OCI_ANNOTATION_SECURE_LEVEL]

            val jail = callOciRuntime()

            if (interceptorStateIn != interceptorStateOut) {
                runtime.writeInterceptorState(
                    containerId,
                    interceptorStateOut
                )
            }

            runtime.logger.info("modifying jail parameters")
            runBlocking {
                modifyJailParameters(
                    jail,
                    buildMap {
                        put("host.hostid", hostId.toString())
                        put("host.hostuuid", hostUuid)
                        if (secureLevel != null) {
                            put("securelevel", secureLevel)
                        }
                        putAll(allow)
                    }
                )
            }
        } catch (ex: Throwable) {
            runtime.logger.error(ex.toString(), ex)
            exitProcess(1)
        }
    }

    private fun patchConfig(containerConfig: OciConfig): OciConfig {
        var patchedConfig = containerConfig
        if (isJailed()) {
            val hasVmmAllow = patchedConfig.annotations
                .containsKey("${OCI_ANNOTATION_ALLOW}.vmm")
            if (!hasVmmAllow && vmmAllowed()) {
                // automatically inherit parent jail's setting
                runtime.logger.info("inheriting allow.vmm from parent")
                patchedConfig = patchedConfig.copy(
                    annotations = patchedConfig.annotations + mapOf(
                        "${OCI_ANNOTATION_ALLOW}.vmm" to "1"
                    )
                )
            }

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

    private fun vmmAllowed(): Boolean {
        return sysctlByNameInt32("security.jail.vmm_allowed") == 1
    }

    private fun callOciRuntime(): JailParameters {
        runtime.callOciRuntime(this)
        return runBlocking {
            readJailParameters().singleOrNull { jail ->
                jail.name == containerId
            } ?: error("jail \"$containerId\" not found")
        }
    }
}

private class Delete : DeleteCommand() {
    val runtime: OciJailInterceptor by requireObject()

    override fun run() {
        runtime.readInterceptorState(containerId)?.let {
            runtime.logger.restoreState(it)
        }

        val state = runtime.readOciJailState(containerId)

        if (state != null) {
            val status = state["status"]?.jsonPrimitive?.content ?: "unknown"
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

        runBlocking {
            val jails = readJailParameters()
            val jail = jails.singleOrNull { p ->
                p.name == containerId || p.jid == containerId.toIntOrNull()
            }
            if (jail != null) {
                val rc = try {
                    cleanup(runtime.logger, jail)
                } catch (ex: Throwable) {
                    runtime.logger.error(ex.toString(), ex)
                    1
                }
                if (rc != 0) {
                    throw PrintMessage(
                        "delete failed", 1, printError = true
                    )
                }
            }
        }

        runtime.callOciRuntime(this)
        runtime.deleteInterceptorState(containerId)
    }
}
