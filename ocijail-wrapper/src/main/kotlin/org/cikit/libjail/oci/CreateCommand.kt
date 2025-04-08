package org.cikit.libjail.oci

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.cikit.libjail.*
import java.security.MessageDigest
import kotlin.io.path.*

class CreateCommand : CliktCommand("create") {

    override fun help(context: Context): String {
        return context.theme.info("Create a jail instance for the container described by the given bundle directory.")
    }

    private val options by requireObject<GlobalOptions>()

    private val bundle by option("-b", "--bundle")
        .help("Path to the OCI runtime bundle directory")
        .path(mustExist = true, canBeDir = true, canBeFile = false)
        .required()

    private val consoleSocket by option()
        .help("Path to a socket which will receive the console pty descriptor")
        .path()

    private val pidFile by option()
        .help("Path to a file where the container process id will be written")
        .path()

    private val preserveFds by option()
        .help("Number of additional file descriptors for the container")
        .long()

    private val containerId by argument("container-id", help = "Unique identifier for the container")

    override fun run() {
        try {
            val wrapperStateIn = options.readWrapperState(containerId)
            val wrapperStateOut = buildJsonObject {
                if (wrapperStateIn != null) {
                    for ((k, v) in wrapperStateIn.entries) {
                        put(k, v)
                    }
                }
                put("logFile", options.logFile?.pathString)
                put("logLevel", options.logLevel)
                put("logFormat", options.logFormat)
            }
            if (wrapperStateIn != wrapperStateOut) {
                options.writeWrapperState(containerId, wrapperStateOut)
            }
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

            val hostId = with (MessageDigest.getInstance("MD5")) {
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

            val patchedConfig = patchConfig(ociConfig)
            ociConfigFile.writeText(json.encodeToString(patchedConfig))

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

            val jail = callOciJail()

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
            throw PrintMessage(
                "create failed: ${ex.message}",
                1,
                printError = true
            )
        }
    }

    private fun patchConfig(containerConfig: OciConfig): OciConfig {
        var patchedConfig = containerConfig
        if (isJailed()) {
            val hasVmmAllow = patchedConfig.annotations
                .containsKey("$OCI_ANNOTATION_ALLOW.vmm")
            if (!hasVmmAllow && vmmAllowed()) {
                // automatically inherit parent jail's setting
                patchedConfig = patchedConfig.copy(
                    annotations = patchedConfig.annotations + mapOf(
                        "$OCI_ANNOTATION_ALLOW.vmm" to "1"
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
                trace(0, "warn:", "jail_mntinfo not available")
                trace(0, "removing nullfs file mounts")
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
            patchedConfig.annotations["$OCI_ANNOTATION_ALLOW.vmm"] == "1" -> {
                options.devFsRulesetVmm
            }
            patchedConfig.annotations[OCI_ANNOTATION_VNET] == "new" -> {
                options.devFsRulesetVnet
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
        val netGraphLoaded = ProcessBuilder("kldstat", "-qm", "netgraph")
            .inheritIO()
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .start()
            .waitFor() == 0
        if (netGraphLoaded) {
            val rc = ProcessBuilder("ngctl", "list")
                .inheritIO()
                .redirectInput(ProcessBuilder.Redirect.PIPE)
                .start()
                .apply { inputStream.use { it.readBytes() } }
                .waitFor()
            require(rc == 0) {
                "ngctl terminated with exit code $rc"
            }
        }
    }

    private fun listDevFsRulesets(): Set<Long> {
        val p = ProcessBuilder("devfs", "rule", "showsets")
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
        val rc = ProcessBuilder("/etc/rc.d/devfs", "forcerestart")
            .inheritIO()
            .start()
            .waitFor()
        require(rc == 0) { "devfs terminated with exit code $rc" }
    }

    private fun vmmAllowed(): Boolean {
        return sysctlByNameInt32("security.jail.vmm_allowed") == 1
    }

    private fun callOciJail(): JailParameters = runBlocking {
        val args = buildList {
            add(options.ociJailBin.pathString)
            add("create")
            add("--bundle")
            add(bundle.pathString)
            consoleSocket?.let {
                add("--console-socket")
                add(it.pathString)
            }
            pidFile?.let {
                add("--pid-file")
                add(it.pathString)
            }
            preserveFds?.let {
                add("--preserve-fds")
                add(it.toString())
            }
            add(containerId)
        }
        trace(1, args)
        options.ociLogger.close()
        val rc = try {
            ProcessBuilder(args).inheritIO().start().waitFor()
        } finally {
            options.ociLogger.open()
        }
        require(rc == 0) { "ocijail terminated with exit code $rc" }
        readJailParameters().singleOrNull { jail ->
            jail.name == containerId
        } ?: error("jail \"$containerId\" not found")
    }
}
