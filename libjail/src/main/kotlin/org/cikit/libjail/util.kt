package org.cikit.libjail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import kotlin.io.path.pathString

internal val defaultErrorHandler = { func: String, errnum: Int ->
    error("$func(): error code $errnum")
}

interface FfiFunctions {

    fun nmount(
        iov: Map<String, String>,
        flags: Int,
        errorHandler: (String, Int) -> Unit = defaultErrorHandler
    )

    fun unmount(
        dir: String,
        flags: Int,
        errorHandler: (String, Int) -> Unit = defaultErrorHandler
    )

    fun jailAttach(
        jid: Int,
        errorHandler: (String, Int) -> Unit = defaultErrorHandler
    )

    fun jailRemove(
        jid: Int,
        errorHandler: (String, Int) -> Unit = defaultErrorHandler
    )

    fun sysctlByNameString(
        name: String,
        errorHandler: (String, Int) -> Unit = defaultErrorHandler
    ): String?

    fun sysctlByNameString(
        name: String,
        value: String,
        errorHandler: (String, Int) -> Unit = defaultErrorHandler
    )

    fun sysctlByNameInt32(
        name: String,
        errorHandler: (String, Int) -> Unit = defaultErrorHandler
    ): Int?
}

sealed class TraceEvent {
    class Ffi(
        val func: String,
        val args: List<String> = emptyList()
    ) : TraceEvent() {
        constructor(
            func: String,
            vararg arg: String
        ) : this(func, arg.toList())
    }

    class Exec(
        val args: List<String>
    ) : TraceEvent() {
        constructor(vararg arg: String) : this(arg.toList())
    }

    class Debug(val msg: String) : TraceEvent()
    class Info(val msg: String) : TraceEvent()
    class Warn(val msg: String, val ex: Throwable? = null) : TraceEvent()
    class Err(val msg: String, val ex: Throwable? = null) : TraceEvent()
}

enum class TraceControl {
    CONTINUE,
    DEREGISTER,
}

internal val json = Json {
    ignoreUnknownKeys = true
}

private val traceFunctions = ArrayDeque<(TraceEvent) -> TraceControl>()

fun registerTraceFunction(traceFunction: (TraceEvent) -> TraceControl) {
    synchronized(traceFunctions) {
        traceFunctions.addFirst(traceFunction)
    }
}

internal fun trace(ev: TraceEvent) {
    synchronized(traceFunctions) {
        var i = 0
        while (i < traceFunctions.size) {
            val f = traceFunctions[i++]
            when (f(ev)) {
                TraceControl.CONTINUE -> {}
                TraceControl.DEREGISTER -> {
                    traceFunctions.removeAt(i)
                    i--
                }
            }
        }
    }
}

private val defaultHandler = { args: List<String>, rc: Int, errors: String ->
    require(rc == 0) {
        when {
            errors.isBlank() -> "${args.first()} terminate with exit code $rc"
            errors.startsWith("${args.first()}:") -> errors.trim()
            else -> "${args.first()}: ${errors.trim()}"
        }
    }
}

internal suspend fun pRead(
    args: List<String>,
    block: (List<String>, Int, String) -> Unit = defaultHandler
): String {
    trace(TraceEvent.Exec(args))
    return withContext(Dispatchers.IO) {
        val p = ProcessBuilder(args).start()
        val errors = async {
            p.errorStream.use { String(it.readBytes()) }
        }
        val output = p.inputStream.use { String(it.readBytes()) }
        val rc = p.waitFor()
        block(args, rc, errors.await())
        output
    }
}

const val FLUA_BIN = "/usr/libexec/flua"

private const val UCL2JSON_LUA = """local ucl = require("ucl")

-- Read UCL file into a string
local filename = arg[3]
if not filename then
  io.stderr:write("Usage: lua ucl2json.lua <input.ucl>\n")
  os.exit(1)
end

local file = io.open(filename, "r")
if not file then
  io.stderr:write("Could not open file: " .. filename .. "\n")
  os.exit(1)
end

local ucl_text = file:read("*a")
file:close()

-- Parse UCL string
local obj, err = ucl.parser():parse_string(ucl_text)
if not obj then
  io.stderr:write("UCL parse error: " .. tostring(err) .. "\n")
  os.exit(1)
end

-- Emit as JSON (pretty-printed)
print(obj:emit("json"))
"""

suspend fun Path.readUcl(): JsonObject {
    val jsonString = pRead(
        listOf(FLUA_BIN, "-e", UCL2JSON_LUA, pathString)
    )
    return Json.decodeFromString(jsonString)
}
