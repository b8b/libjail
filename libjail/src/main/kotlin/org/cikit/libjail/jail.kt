package org.cikit.libjail

import com.sun.jna.Native
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

suspend fun modifyJailParameters(jail: JailParameters, parameters: Map<String, String>) {
    val args = parameters.entries
        .map { (k, v) -> "$k=$v" }
        .toTypedArray()
    pRead(listOf("jail", "-m", "name=${jail.name}", *args))
}

fun jailAttach(jail: JailParameters) {
    trace(3, "jail_attach(", jail.jid.toString(), "/* ${jail.name} */", ")")
    val rc = FREEBSD_LIBC.jail_attach(jail.jid)
    if (rc != 0) {
        error("jail_attach(): error code ${Native.getLastError()}")
    }
}

fun jailRemove(jail: JailParameters) {
    trace(3, "jail_remove(", jail.jid.toString(), "/* ${jail.name} */", ")")
    val rc = FREEBSD_LIBC.jail_remove(jail.jid)
    if (rc != 0) {
        error("jail_remove(): error code ${Native.getLastError()}")
    }
}
