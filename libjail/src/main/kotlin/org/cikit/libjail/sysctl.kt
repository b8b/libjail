package org.cikit.libjail

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer

private val defaultErrorHandler = { func: String, errnum: Int ->
    error("$func(): error code $errnum")
}

fun sysctlByNameString(
    name: String,
    errorHandler: (String, Int) -> Unit = defaultErrorHandler
): String? {
    trace(3, "sysctlbyname(", "\"$name\"", ")")
    val size = SizeTByReference(SizeT(0))
    if (FREEBSD_LIBC.sysctlbyname(name, null, size, null, null) != 0) {
        errorHandler("sysctlbyname", Native.getLastError())
        return null
    }
    val buf = Memory(size.getValue().toLong())
    if (FREEBSD_LIBC.sysctlbyname(name, buf, size, null, null) != 0) {
        errorHandler("sysctlbyname", Native.getLastError())
        return null
    }
    return buf.getString(0L)
}

fun sysctlByNameString(
    name: String,
    value: String,
    errorHandler: (String, Int) -> Unit = defaultErrorHandler
) {
    trace(3, "sysctlbyname(", "\"$name\"", "=", "\"$value\"", ")")
    val data = value.encodeToByteArray()
    val dataLen = data.size + 1L
    val valueMem = Memory(dataLen).apply {
        write(0, data, 0, data.size)
        setByte(dataLen - 1L, 0.toByte())
    }
    val size = SizeT(dataLen)
    if (FREEBSD_LIBC.sysctlbyname(name, null, null, valueMem, size) != 0) {
        errorHandler("sysctlbyname", Native.getLastError())
        return
    }
}

fun sysctlByNameInt32(
    name: String,
    errorHandler: (String, Int) -> Unit = defaultErrorHandler
): Int? {
    trace(3, "sysctlbyname(", "\"$name\"", ")")
    val size = SizeTByReference(SizeT(4))
    val buf = Memory(size.getValue().toLong())
    if (FREEBSD_LIBC.sysctlbyname(name, buf, size, null, null) != 0) {
        errorHandler("sysctlbyname", Native.getLastError())
        return null
    }
    return buf.getInt(0L)
}
