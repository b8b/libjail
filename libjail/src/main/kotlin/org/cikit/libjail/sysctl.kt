package org.cikit.libjail

fun sysctlByNameString(
    name: String,
    errorHandler: (String, Int) -> Unit = defaultErrorHandler
): String? {
    trace(TraceEvent.Ffi("sysctlbyname", "\"$name\""))
    return Ffi.sysctlByNameString(name, errorHandler)
}

fun sysctlByNameString(
    name: String,
    value: String,
    errorHandler: (String, Int) -> Unit = defaultErrorHandler
) {
    trace(TraceEvent.Ffi("sysctlbyname", "\"$name\"=\"$value\""))
    Ffi.sysctlByNameString(name, value, errorHandler)
}

fun sysctlByNameInt32(
    name: String,
    errorHandler: (String, Int) -> Unit = defaultErrorHandler
): Int? {
    trace(TraceEvent.Ffi("sysctlbyname", "\"$name\""))
    return Ffi.sysctlByNameInt32(name, errorHandler)
}
