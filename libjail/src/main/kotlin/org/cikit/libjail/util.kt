package org.cikit.libjail

import com.sun.jna.IntegerType
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.ByReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json


sealed class TraceEvent {
    class Msg(val level: Int, val msg: String) : TraceEvent() {
        val levelString = when (level) {
            0 -> "warn"
            1 -> "info"
            else -> "debug"
        }
    }
}

enum class TraceControl {
    ACCEPT,
    REJECT,
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

fun trace(level: Int, vararg args: String?) {
    trace(level, args.filterNotNull())
}

fun trace(level: Int, args: List<String>) {
    val line = if (level > 1) {
        "+ ${args.joinToString(" ")}"
    } else {
        args.joinToString(" ")
    }
    val ev = TraceEvent.Msg(level, line)
    trace(ev)
}

fun trace(ev: TraceEvent) {
    synchronized(traceFunctions) {
        var i = 0
        while (i < traceFunctions.size) {
            val f = traceFunctions[i++]
            val control = f(ev)
            when (ev) {
                is TraceEvent.Msg -> when (control) {
                    TraceControl.ACCEPT -> {
                        //ignore
                    }
                    TraceControl.REJECT -> {
                        break
                    }
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
    trace(2, args)
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

internal class SizeT(
    value: Long = 0
) : IntegerType(Native.SIZE_T_SIZE, value, true) {
    override fun toByte(): Byte {
        return this.toByte()
    }
    override fun toShort(): Short {
        return this.toShort()
    }
}

internal class SizeTByReference(
    value: SizeT = SizeT()
) : ByReference(Native.SIZE_T_SIZE) {

   init {
        setValue(value)
    }

    fun getValue(): SizeT {
        val p = pointer
        return if (Native.SIZE_T_SIZE == 8) {
            SizeT(p.getLong(0))
        } else {
            SizeT(p.getInt(0).toLong())
        }
    }

    fun setValue(value: SizeT) {
        val p = pointer
        if (Native.SIZE_T_SIZE == 8) {
            p.setLong(0, value.toLong())
        } else {
            p.setInt(0, value.toInt())
        }
    }

}

internal interface FreeBSDLibC : com.sun.jna.Library {
    fun nmount(iov: Array<StructIov?>?, niov: Int, flags: Int): Int
    fun unmount(dir: String, flags: Int): Int

    fun jail_attach(jid: Int): Int
    fun jail_remove(jid: Int): Int

    fun sysctlbyname(
        name: String?,               // sysctl name (e.g., "hw.model")
        oldp: Pointer?,              // buffer to store result
        oldlenp: SizeTByReference?,  // buffer size (input/output)
        newp: Pointer?,              // new value (setter) or null
        newlen: SizeT?               // new value length (or 0)
    ): Int
}

internal val FREEBSD_LIBC by lazy {
    Native.load("c", FreeBSDLibC::class.java)
}

internal class StructIov : Structure() {
    @JvmField
    var iov_base: Pointer? = null

    @JvmField
    var iov_len: Long? = 0

    override fun getFieldOrder(): MutableList<String> {
        return mutableListOf("iov_base", "iov_len")
    }
}
