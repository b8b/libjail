package org.cikit.libjail

import kotlinx.serialization.json.*

class JailParameters(
    val parameters: JsonObject,
) {
    val jid = parameters.getValue("jid").jsonPrimitive.int
    val name by lazy { parameters.getValue("name").jsonPrimitive.content }
    val path by lazy { parameters.getValue("path").jsonPrimitive.content }
}

suspend fun readJailParameters(): List<JailParameters> {
    val output = pRead(listOf("jls", "--libxo", "json", "-n"))
    return json.decodeFromString<JsonObject>(output)
        .getValue("jail-information").jsonObject
        .getValue("jail").jsonArray
        .filterIsInstance<JsonObject>()
        .map { JailParameters(it) }
}

fun isJailed(): Boolean {
    return sysctlByNameInt32("security.jail.jailed") == 1
}

suspend fun modifyJailParameters(
    jail: JailParameters,
    parameters: Map<String, String>
) {
    val args = parameters.entries
        .map { (k, v) -> "$k=$v" }
        .toTypedArray()
    pRead(listOf("jail", "-m", "name=${jail.name}", *args))
}

fun jailAttach(jail: JailParameters) {
    trace(TraceEvent.Ffi("jail_attach", "${jail.jid} /* ${jail.name} */"))
    Ffi.jailAttach(jail.jid)
}

fun jailRemove(jail: JailParameters) {
    trace(TraceEvent.Ffi("jail_remove", "${jail.jid} /* ${jail.name} */"))
    Ffi.jailRemove(jail.jid)
}
