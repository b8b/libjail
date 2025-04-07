package org.cikit.libjail

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.ptr.NativeLongByReference

private val defaultErrorHandler = { func: String, errnum: Int ->
    error("$func(): error code $errnum")
}

fun sysctlByNameString(
    name: String,
    errorHandler: (String, Int) -> Unit = defaultErrorHandler
): String? {
    val size = NativeLongByReference(NativeLong(0))
    if (FREEBSD_LIBC.sysctlbyname(name, null, size, null, null) != 0) {
        errorHandler("sysctlbyname", Native.getLastError())
        return null
    }
    val buf = Memory(size.value.toLong())
    if (FREEBSD_LIBC.sysctlbyname(name, buf, size, null, null) != 0) {
        errorHandler("sysctlbyname", Native.getLastError())
        return null
    }
    return buf.getString(0L)
}

fun sysctlByNameInt32(
    name: String,
    errorHandler: (String, Int) -> Unit = defaultErrorHandler
): Int? {
    val size = NativeLongByReference(NativeLong(4))
    val buf = Memory(size.value.toLong())
    if (FREEBSD_LIBC.sysctlbyname(name, buf, size, null, null) != 0) {
        errorHandler("sysctlbyname", Native.getLastError())
        return null
    }
    return buf.getInt(0L)
}
