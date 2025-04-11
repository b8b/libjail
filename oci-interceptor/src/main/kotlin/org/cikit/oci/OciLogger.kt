package org.cikit.oci

import kotlinx.serialization.json.*
import org.cikit.libjail.TraceControl
import org.cikit.libjail.TraceEvent
import org.cikit.libjail.registerTraceFunction
import java.io.BufferedWriter
import java.nio.file.StandardOpenOption
import java.time.OffsetDateTime
import kotlin.concurrent.thread
import kotlin.io.path.Path
import kotlin.io.path.bufferedWriter

class OciLogger(
    logFile: String?,
    logFormat: String?,
    logLevel: String?
) {
    var logFile: String? = logFile
        private set

    var logFormat: String? = logFormat
        private set

    var logLevel: String? = logLevel
        private set

    private object Lock

    private var w: BufferedWriter? = null
    private var logToConsole = logFile == null

    private val shutdownHook = thread(start = false) {
        w?.close()
    }

    init {
        open()
        Runtime.getRuntime().addShutdownHook(
            shutdownHook
        )
        registerTraceFunction { ev ->
            trace(ev)
            TraceControl.CONTINUE
        }
    }

    fun saveState() = buildJsonObject {
        put("logFile", logFile)
        put("logLevel", logLevel)
        put("logFormat", logFormat)
    }

    fun restoreState(state: JsonObject) {
        synchronized(Lock) {
            if (logLevel == null) {
                logLevel = (state["logLevel"] as? JsonPrimitive)?.content
            }
            if (logFormat == null) {
                logFormat = (state["logFormat"] as? JsonPrimitive)?.content
            }
            if (logFile == null) {
                logFile = (state["logFile"] as? JsonPrimitive)?.content
                if (logFile != null) {
                    logToConsole = false
                    open()
                } else {
                    logToConsole = true
                }
            }
        }
    }

    fun overrideLogFile(logFile: String) {
        synchronized(Lock) {
            close()
            this.logFile = logFile.takeIf { it.isNotBlank() }
            open()
        }
    }

    fun open() {
        synchronized(Lock) {
            w?.close()
            w = logFile?.let { Path(it) }?.bufferedWriter(
                options = arrayOf(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                )
            )
        }
    }

    fun close() {
        synchronized(Lock) {
            w?.close()
            w = null
        }
    }

    fun info(msg: String) {
        trace(TraceEvent.Info(msg))
    }

    fun warn(msg: String) {
        trace(TraceEvent.Warn(msg))
    }

    fun error(msg: String, ex: Throwable? = null) {
        trace(TraceEvent.Err(msg, ex))
    }

    fun trace(ev: TraceEvent) {
        val evLevel: Int
        val evLevelString: String
        val evMsgString: String
        when (ev) {
            is TraceEvent.Ffi -> {
                evLevel = 2
                evLevelString = "debug"
                evMsgString = "+ ${ev.func}(${ev.args.joinToString(", ")})"
            }

            is TraceEvent.Exec -> {
                evLevel = 2
                evLevelString = "debug"
                evMsgString = "+ ${ev.args.joinToString(" ")}"
            }

            is TraceEvent.Info -> {
                evLevel = 1
                evLevelString = "info"
                evMsgString = ev.msg
            }

            is TraceEvent.Warn -> {
                evLevel = 0
                evLevelString = "warn"
                evMsgString = ev.msg
            }

            is TraceEvent.Err -> {
                evLevel = 0
                evLevelString = "error"
                evMsgString = ev.msg
            }
        }
        val now = OffsetDateTime.now().toString()
        synchronized(Lock) {
            val finalLogLevel = when (logLevel) {
                "0", "info" -> 1
                "1", "warn" -> 0
                "2", "debug" -> 3
                else -> 1
            }
            if (logToConsole) {
                if (ev is TraceEvent.Err && finalLogLevel >= 3) {
                    ev.ex
                        ?.printStackTrace()
                        ?: System.err.println(evMsgString)
                } else if (finalLogLevel >= evLevel) {
                    System.err.println(evMsgString)
                }
            } else {
                val writer = w ?: return@synchronized
                val line = if (logFormat == "json") {
                    Json.encodeToString(
                        buildJsonObject {
                            put("msg", evMsgString)
                            put("level", evLevelString)
                            put("time", now)
                        }
                    )
                } else {
                    "${ev.javaClass.simpleName} $evLevelString: $evMsgString"
                }
                writer.appendLine(line)
                writer.flush()
                if (ev is TraceEvent.Err) {
                    ev.ex
                        ?.takeIf { finalLogLevel >= 3 }
                        ?.printStackTrace()
                        ?: System.err.println(evMsgString)
                }
            }
        }
    }
}
