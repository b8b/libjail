package org.cikit.libjail

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.NativeLongByReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json


internal val json = Json {
    ignoreUnknownKeys = true
}

private var logLevel = 0

fun setLogLevel(level: Int) {
    logLevel = level
}

fun trace(level: Int, vararg args: String?) {
    trace(level, args.filterNotNull())
}

fun trace(level: Int, args: List<String>) {
    when (level) {
        0 -> System.err.println(args.joinToString(" "))
        else -> if (logLevel >= level) {
            if (level > 1) {
                System.err.println("+ ${args.joinToString(" ")}")
            } else {
                System.err.println(args.joinToString(" "))
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

internal interface FreeBSDLibC : com.sun.jna.Library {
    fun nmount(iov: Array<StructIov?>?, niov: Int, flags: Int): Int
    fun unmount(dir: String, flags: Long): Int

    fun jail_attach(jid: Int): Int
    fun jail_remove(jid: Int): Int

    fun sysctlbyname(
        name: String?,  // sysctl name (e.g., "hw.model")
        oldp: Pointer?,  // buffer to store result
        oldlenp: NativeLongByReference?,  // buffer size (input/output)
        newp: Pointer?,  // new value (setter) or null
        newlen: Long? // new value length (or 0)
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
