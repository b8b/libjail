package org.cikit.oci.jail

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.cikit.libjail.*
import org.cikit.oci.OciLogger
import kotlin.system.exitProcess

suspend fun cleanup(log: OciLogger, jail: JailParameters): Int {
    var rcAll = 0

    try {
        withTimeout(30_000L) {
            log.info("killing processes")
            kill(jail)
        }
    } catch (ex: Throwable) {
        log.error("error killing processes", ex)
        rcAll = 1
    }

    try {
        val rc = cleanupJails(log, jail)
        if (rc != 0) {
            rcAll = 1
        }
    } catch (ex: Throwable) {
        log.error("error removing nested jails", ex)
        rcAll = 1
    }

    if (jail.parameters["vnet"] == JsonPrimitive("new")) {
        try {
            val rc = cleanupInterfaces(log, jail)
            if (rc != 0) {
                rcAll = 1
            }
        } catch (ex: Throwable) {
            log.error("error removing interfaces", ex)
            rcAll = 1
        }
    }

    val vmmDevices = listVms().toSet()

    var mounted = readMountInfo()

    if (isJailed()) {
        // get entries including fsid for filesystems mounted by us
        val allMountedByUs = readJailMountInfo()
        if (allMountedByUs == null) {
            log.warn("jail_mntinfo kernel module not available")
        } else {
            mounted = allMountedByUs
        }
    }

    val unmounted: Set<MountInfo> = try {
        cleanupMounts(log, jail, mounted)
    } catch (ex: Throwable) {
        log.error("error unmounting filesystems", ex)
        rcAll = 1
        emptySet()
    }

    val probeNullFs = mounted.mapNotNull { mount ->
        // https://bugs.freebsd.org/bugzilla/show_bug.cgi?id=282041
        // entries mounted by the cleaned up jail with a wrong
        // node path will be "probe unmounted" after attach
        if (mount.fsType == "nullfs" && mount !in unmounted) {
            if (mount.node.startsWith("${jail.path}/")) {
                mount.copy(node = mount.node.removePrefix(jail.path))
            } else {
                mount
            }
        } else {
            null
        }
    }

    if (probeNullFs.isEmpty() && vmmDevices.isEmpty()) {
        return rcAll
    }

    try {
        log.info("attaching to jail to cleanup private resources")
        jailAttach(jail)
    } catch (ex: Exception) {
        throw RuntimeException("failed to attach to jail", ex)
    }

    try {
        val allMountedByJail = readJailMountInfo()
        val rc = if (allMountedByJail == null) {
            log.warn("probe unmounting nullfs mounts")
            probeUnmountAttached(log, probeNullFs)
        } else {
            cleanupMountsAttached(log, allMountedByJail)
        }
        if (rc != 0) {
            rcAll = 1
        }
    } catch (ex: Throwable) {
        log.error("error unmounting filesystems", ex)
        rcAll = 1
    }

    if (vmmDevices.isNotEmpty()) {
        try {
            val rc = cleanupVmmDevicesAttached(log, vmmDevices)
            if (rc != 0) {
                rcAll = 1
            }
        } catch (ex: Throwable) {
            log.error("error cleaning up vmm devices", ex)
            rcAll = 1
        }
    }

    return rcAll
}

private suspend fun cleanupJails(log: OciLogger, jail: JailParameters): Int {
    val children = readJailParameters().filter { p ->
        p.parameters.getValue("parent").jsonPrimitive.int == jail.jid
    }
    var rcAll = 0
    for (child in children) {
        log.info("removing nested jail \"${child.name}\"")
        try {
            jailRemove(child)
        } catch (ex: Exception) {
            log.error("failed to remove nested jail \"${child.name}\"", ex)
            rcAll = 1
        }
    }
    return rcAll
}

private suspend fun cleanupInterfaces(
    log: OciLogger,
    jail: JailParameters
): Int {
    var rcAll = 0
    var chk = -1
    while (true) {
        val netIfs = readNetifParameters(jail).filter { i ->
            i.driverName.startsWith("epair")
        }
        if (netIfs.isEmpty()) {
            break
        }
        require(chk < 0 || netIfs.size < chk) {
            "unexpected interface count while cleaning up"
        }
        chk = netIfs.size
        val ifName = netIfs.first().name
        try {
            log.info("destroying network interface \"$ifName\"")
            destroyNetif(jail, ifName)
        } catch (ex: Throwable) {
            log.error("failed to destroy network interface \"$ifName\"", ex)
            rcAll = 1
        }
    }
    return rcAll
}

private fun cleanupMounts(
    log: OciLogger,
    jail: JailParameters,
    mounted: List<MountInfo>,
): Set<MountInfo> {
    val prefix = jail.path + "/"
    val unmount = mounted.filter { mount -> mount.node.startsWith(prefix) }
    val unmounted = mutableSetOf<MountInfo>()
    for (mount in unmount.reversed()) {
        try {
            log.info("unmounting ${mount.node.removePrefix(jail.path)}")
            mount.parseFsId()
                ?.let { unmount(it, force = true) }
                ?: unmount(mount.node, force = true)
            unmounted += mount
        } catch (ex: Exception) {
            log.error(
                "failed to unmount ${mount.node.removePrefix(jail.path)}: ",
                ex
            )
        }
    }
    return unmounted.toSet()
}

private fun probeUnmountAttached(
    log: OciLogger,
    mounted: List<MountInfo>
): Int {
    var rcAll = 0
    for (mount in mounted) {
        try {
            var permissionDenied = false
            mount.parseFsId()?.let { fsId ->
                unmount(fsId, force = true) { func, errnum ->
                    if (errnum != 1) {
                        error("$func(): error code $errnum")
                    }
                    permissionDenied = true
                }
            } ?: unmount(mount.node, force = true) { func, errnum ->
                if (errnum != 1) {
                    error("$func(): error code $errnum")
                }
                permissionDenied = true
            }
            if (!permissionDenied) {
                log.info("unmounted ${mount.node}")
            }
        } catch (ex: Exception) {
            log.error("error unmounting ${mount.node}", ex)
            rcAll = 1
        }
    }
    return rcAll
}

private fun cleanupMountsAttached(
    log: OciLogger,
    mounted: List<MountInfo>
): Int {
    var rcAll = 0
    for (mount in mounted) {
        try {
            log.info("unmounting ${mount.node}")
            mount.parseFsId()
                ?.let { unmount(it, force = true) }
                ?: unmount(mount.node, force = true)
        } catch (ex: Exception) {
            log.error("failed to unmount ${mount.node}", ex)
            rcAll = 1
        }
    }
    return rcAll
}

private fun cleanupVmmDevicesAttached(
    log: OciLogger,
    vmmDevices: Set<String>
): Int {
    var rcAll = 0
    for (vm in vmmDevices) {
        try {
            // probe vm_destroy and see if we have access
            when (vmDestroy(vm)) {
                is VmDestroyResult.NoPermission -> { /* ignore this vm */ }
                is VmDestroyResult.NotFound -> {
                    log.warn("vm \"$vm\" disappeared while cleaning")
                }
                is VmDestroyResult.Ok -> {
                    log.info("destroyed vmm device \"$vm\"")
                }
            }
        } catch (ex: Exception) {
            log.error("failed to destroy vmm device \"$vm\":", ex)
            rcAll = 1
        }
    }
    return rcAll
}


class CleanupCommand : CliktCommand("cleanup") {

    override fun help(context: Context): String {
        return context.theme.info("Cleanup the jail with the given id")
    }

    private val log by option()
        .help("Log file")

    private val logFormat by option()
        .help("Log format")

    private val logLevel by option()
        .help("Log level")

    private val logger by lazy {
        OciLogger(
            logFile = log,
            logFormat = logFormat,
            logLevel = logLevel
        )
    }

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
                    val rc = cleanup(logger, p)
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

    companion object {
        @JvmStatic
        fun main(args: Array<String>) = CleanupCommand().main(args)
    }
}
