package org.cikit.libjail

import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlin.io.path.*

class VmContext(internal val opaque: Any?)

private val vmmPath = Path("/dev/vmm")

fun listVms(): List<String> {
    if (vmmPath.isDirectory()) {
        return vmmPath.listDirectoryEntries()
            .filter { p -> !p.isSymbolicLink() && !p.isDirectory() }
            .map { p -> p.name }
    }
    return emptyList()
}

fun vmOpenOrNull(name: String): VmContext? {
    val result = FREEBSD_LIBVMM.vm_open(name)
    if (result == null) {
        val errnum = Native.getLastError()
        if (errnum == 2 /* ENOENT */) {
            return null
        }
        error("vm_open(): error code $errnum")
    }
    return VmContext(result)
}

fun vmOpen(name: String): VmContext {
    val result = FREEBSD_LIBVMM.vm_open(name)
        ?: error("vm_open(): error code ${Native.getLastError()}")
    return VmContext(result)
}

fun vmClose(ctx: VmContext) {
    FREEBSD_LIBVMM.vm_close(ctx.opaque as Pointer)
}

fun vmDestroy(ctx: VmContext) {
    FREEBSD_LIBVMM.vm_destroy(ctx.opaque as Pointer)
}

private interface FreeBSDLibVmm : com.sun.jna.Library {
    fun vm_open(name: String): Pointer?
    fun vm_close(ctx: Pointer?)
    fun vm_destroy(p: Pointer)
}

private val FREEBSD_LIBVMM by lazy {
    Native.load("vmmapi", FreeBSDLibVmm::class.java)
}
