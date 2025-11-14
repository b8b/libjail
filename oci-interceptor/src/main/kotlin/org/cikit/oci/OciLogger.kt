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
import kotlin.io.path.exists

class OciLogger(
    logFile: String?,
    logFormat: String?,
    logLevel: String?,
    disableConsole: Boolean = false
) {
    var logFile: String? = logFile
        private set

    var logFormat: String? = logFormat

    var logLevel: String? = logLevel

    private object Lock

    private var w: BufferedWriter? = null
    private var logToConsole = !disableConsole && logFile == null

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
                logLevel = (state["logLevel"] as? JsonPrimitive)
                    ?.contentOrNull
            }
            if (logFormat == null) {
                logFormat = (state["logFormat"] as? JsonPrimitive)
                    ?.contentOrNull
            }
            if (logFile == null) {
                logFile = (state["logFile"] as? JsonPrimitive)
                    ?.contentOrNull
                if (logFile != null) {
                    logToConsole = false
                    open()
                } else {
                    logToConsole = true
                }
            }
        }
    }

    fun overrideLogFile(logFile: String?) {
        synchronized(Lock) {
            this.logFile = logFile
            this.logToConsole = this.logFile == null
            open()
        }
    }

    fun open() {
        synchronized(Lock) {
            w?.close()
            w = logFile
                ?.let { Path(it) }
                ?.takeIf {
                    // TBD activate logToConsole?
                    it.parent?.exists() != false
                }
                ?.bufferedWriter(
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

    fun warn(msg: String, ex: Throwable? = null) {
        trace(TraceEvent.Warn(msg, ex))
    }

    fun error(msg: String, ex: Throwable? = null) {
        trace(TraceEvent.Err(msg, ex))
    }

    fun trace(ev: TraceEvent) {
        val evLevel: Int
        val evLevelString: String
        val evMsgString: String
        val evEx: Throwable?
        when (ev) {
            is TraceEvent.Ffi -> {
                evLevel = 2
                evLevelString = "debug"
                evMsgString = "+ ${ev.func}(${ev.args.joinToString(", ")})"
                evEx = null
            }

            is TraceEvent.Exec -> {
                evLevel = 2
                evLevelString = "debug"
                evMsgString = "+ ${ev.args.joinToString(" ")}"
                evEx = null
            }

            is TraceEvent.Debug -> {
                evLevel = 2
                evLevelString = "debug"
                evMsgString = ev.msg
                evEx = null
            }

            is TraceEvent.Info -> {
                evLevel = 1
                evLevelString = "info"
                evMsgString = ev.msg
                evEx = null
            }

            is TraceEvent.Warn -> {
                evLevel = 0
                evLevelString = "warn"
                evMsgString = ev.msg
                evEx = ev.ex
            }

            is TraceEvent.Err -> {
                evLevel = 0
                evLevelString = "error"
                evMsgString = ev.msg
                evEx = ev.ex
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
                if (finalLogLevel >= evLevel) {
                    System.err.println(evMsgString)
                    if (finalLogLevel >= 3) {
                        evEx?.printStackTrace()
                    }
                }
            } else {
                val writer = w ?: return@synchronized
                val line = if (logFormat == "json") {
                    Json.encodeToString(
                        buildJsonObject {
                            put("msg", evMsgString)
                            put("level", evLevelString)
                            put("time", now)
                            if (evEx != null) {
                                put("stacktrace", evEx.stackTraceToString())
                            }
                        }
                    )
                } else {
                    "${ev.javaClass.simpleName} $evLevelString: $evMsgString" +
                            if (evEx != null) {
                                "\n" + evEx.stackTraceToString()
                            } else {
                                ""
                            }
                }
                writer.appendLine(line)
                writer.flush()
                if (ev is TraceEvent.Err) {
                    System.err.println(evMsgString)
                    ev.ex
                        ?.takeIf { finalLogLevel >= 3 }
                        ?.printStackTrace()
                }
            }
        }
    }
}
