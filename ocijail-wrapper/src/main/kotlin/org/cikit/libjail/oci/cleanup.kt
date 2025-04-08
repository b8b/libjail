package org.cikit.libjail.oci

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.cikit.libjail.*

suspend fun cleanup(jail: JailParameters): Int {
    var rcAll = 0

    try {
        withTimeout(30_000L) {
            trace(1, "killing processes")
            kill(jail)
        }
    } catch (ex: Throwable) {
        trace(
            0,
            "warn:",
            "error killing processes:",
            ex.message
        )
        rcAll = 1
    }

    try {
        val rc = cleanupJails(jail)
        if (rc != 0) {
            rcAll = 1
        }
    } catch (ex: Throwable) {
        trace(
            0,
            "warn:",
            "error removing nested jails:",
            ex.message
        )
        rcAll = 1
    }

    if (jail.parameters["vnet"] == JsonPrimitive("new")) {
        try {
            val rc = cleanupInterfaces(jail)
            if (rc != 0) {
                rcAll = 1
            }
        } catch (ex: Throwable) {
            trace(
                0,
                "warn:",
                "error removing interfaces:",
                ex.message
            )
            rcAll = 1
        }
    }

    val vmmDevices = listVms().toSet()

    var mounted = readMountInfo()

    if (isJailed()) {
        // get entries including fsid for filesystems mounted by us
        val allMountedByUs = readJailMountInfo()
        if (allMountedByUs == null) {
            trace(0, "warn:", "jail_mntinfo not available")
        } else {
            mounted = allMountedByUs
        }
    }

    val unmounted: Set<MountInfo> = try {
        cleanupMounts(jail, mounted)
    } catch (ex: Throwable) {
        trace(
            0,
            "warn:",
            "error unmounting filesystems:",
            ex.message
        )
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
        trace(1, "attaching to jail")
        jailAttach(jail)
    } catch (ex: Exception) {
        throw RuntimeException("failed to attach to jail", ex)
    }

    try {
        val allMountedByJail = readJailMountInfo()
        val rc = if (allMountedByJail == null) {
            trace(0, "warn:", "probe unmounting nullfs mounts")
            probeUnmountAttached(probeNullFs)
        } else {
            cleanupMountsAttached(allMountedByJail)
        }
        if (rc != 0) {
            rcAll = 1
        }
    } catch (ex: Throwable) {
        trace(
            0,
            "warn:",
            "error unmounting filesystems:",
            ex.message
        )
        rcAll = 1
    }

    if (vmmDevices.isNotEmpty()) {
        try {
            val rc = cleanupVmmDevicesAttached(vmmDevices)
            if (rc != 0) {
                rcAll = 1
            }
        } catch (ex: Throwable) {
            rcAll = 1
        }
    }

    return rcAll
}

private suspend fun cleanupJails(jail: JailParameters): Int {
    val children = readJailParameters().filter { p ->
        p.parameters.getValue("parent").jsonPrimitive.int == jail.jid
    }
    var rcAll = 0
    for (child in children) {
        trace(1, "removing nested jail \"${child.name}\"")
        try {
            jailRemove(child)
        } catch (ex: Exception) {
            trace(
                0,
                "warn:",
                "failed to remove nested jail \"${child.name}\"",
                ex.message
            )
            rcAll = 1
        }
    }
    return rcAll
}

private suspend fun cleanupInterfaces(jail: JailParameters): Int {
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
            trace(1, "destroying network interface \"$ifName\"")
            destroyNetif(jail, ifName)
        } catch (ex: Throwable) {
            trace(
                0,
                "warn:",
                "failed to destroy network interface \"$ifName\"",
                ex.message
            )
            rcAll = 1
        }
    }
    return rcAll
}

private fun cleanupMounts(
    jail: JailParameters,
    mounted: List<MountInfo>,
): Set<MountInfo> {
    val prefix = jail.path + "/"
    val unmount = mounted.filter { mount -> mount.node.startsWith(prefix) }
    val unmounted = mutableSetOf<MountInfo>()
    for (mount in unmount.reversed()) {
        try {
            trace(1, "unmounting ${mount.node.removePrefix(jail.path)}")
            mount.parseFsId()
                ?.let {
                    unmount(it, force = true)
                }
                ?: run {
                    unmount(mount.node, force = true)
                }
            unmounted += mount
        } catch (ex: Exception) {
            trace(
                0,
                "warn:",
                "failed to unmount ${mount.node.removePrefix(jail.path)}",
                ex.message
            )
        }
    }
    return unmounted.toSet()
}

private fun probeUnmountAttached(mounted: List<MountInfo>): Int {
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
                trace(0, "unmounted ${mount.node}")
            }
        } catch (ex: Exception) {
            trace(
                0,
                "error unmounting ${mount.node}:",
                ex.message
            )
            rcAll = 1
        }
    }
    return rcAll
}

private fun cleanupMountsAttached(mounted: List<MountInfo>): Int {
    var rcAll = 0
    for (mount in mounted) {
        try {
            trace(1, "unmounting ${mount.node}")
            mount.parseFsId()
                ?.let {
                    unmount(it, force = true)
                }
                ?: run {
                    unmount(mount.node, force = true)
                }
        } catch (ex: Exception) {
            trace(
                0,
                "warn:",
                "failed to unmount ${mount.node}:",
                ex.message
            )
            rcAll = 1
        }
    }
    return rcAll
}

private fun cleanupVmmDevicesAttached(vmmDevices: Set<String>): Int {
    var rcAll = 0
    for (vm in vmmDevices) {
        try {
            // probe vm_destroy and see if we have access
            when (vmDestroy(vm)) {
                is VmDestroyResult.NoPermission -> { /* ignore this vm */ }
                is VmDestroyResult.NotFound -> {
                    trace(0, "warn:", "vm \"$vm\" disappeared while cleaning")
                }
                is VmDestroyResult.Ok -> {
                    trace(1, "destroyed vmm device \"$vm\"")
                }
            }
        } catch (ex: Exception) {
            trace(
                0,
                "warn:",
                "failed to destroy vmm device \"$vm\":",
                ex.message
            )
            rcAll = 1
        }
    }
    return rcAll
}
