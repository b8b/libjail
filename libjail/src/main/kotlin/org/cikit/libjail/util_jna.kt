package org.cikit.libjail

import com.sun.jna.*
import com.sun.jna.ptr.ByReference

internal object Ffi : FfiFunctions {
    override fun nmount(
        iov: Map<String, String>,
        flags: Int,
        errorHandler: (String, Int) -> Unit
    ) {
        val iovArray = iov.toIov()
        val rc = FREEBSD_LIBC.nmount(iovArray, iovArray.size, flags)
        if (rc != 0) {
            errorHandler("nmount", Native.getLastError())
        }
    }

    override fun unmount(
        dir: String,
        flags: Int,
        errorHandler: (String, Int) -> Unit
    ) {
        val rc = FREEBSD_LIBC.unmount(
            dir,
            (flags.toLong() and 0xFFFFFFFF).toInt()
        )
        if (rc != 0) {
            errorHandler("unmount", Native.getLastError())
        }
    }

    override fun jailAttach(
        jid: Int,
        errorHandler: (String, Int) -> Unit
    ) {
        val rc = FREEBSD_LIBC.jail_attach(jid)
        if (rc != 0) {
            error("jail_attach(): error code ${Native.getLastError()}")
        }
    }

    override fun jailRemove(
        jid: Int,
        errorHandler: (String, Int) -> Unit
    ) {
        val rc = FREEBSD_LIBC.jail_remove(jid)
        if (rc != 0) {
            error("jail_remove(): error code ${Native.getLastError()}")
        }
    }

    override fun sysctlByNameString(
        name: String,
        errorHandler: (String, Int) -> Unit
    ): String? {
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

    override fun sysctlByNameString(
        name: String,
        value: String,
        errorHandler: (String, Int) -> Unit
    ) {
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

    override fun sysctlByNameInt32(
        name: String,
        errorHandler: (String, Int) -> Unit
    ): Int? {
        val size = SizeTByReference(SizeT(4))
        val buf = Memory(size.getValue().toLong())
        if (FREEBSD_LIBC.sysctlbyname(name, buf, size, null, null) != 0) {
            errorHandler("sysctlbyname", Native.getLastError())
            return null
        }
        return buf.getInt(0L)
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

internal interface FreeBSDLibC : Library {
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

private fun Map<String, String>.toIov(): Array<StructIov?> {
    val iov = StructIov().toArray(size * 2)
    var i = 0
    for ((k, v) in entries) {
        k.let { s ->
            val data = s.encodeToByteArray()
            val dataLen = data.size + 1L
            (iov[i] as StructIov).iov_len = dataLen
            (iov[i++] as StructIov).iov_base = Memory(dataLen).apply {
                write(0, data, 0, data.size)
                setByte(dataLen - 1L, 0.toByte())
            }
        }
        v.let { s ->
            val data = s.encodeToByteArray()
            val dataLen = data.size + 1L
            (iov[i] as StructIov).iov_len = dataLen
            (iov[i++] as StructIov).iov_base = Memory(dataLen).apply {
                write(0, data, 0, data.size)
                setByte(dataLen - 1L, 0.toByte())
            }
        }
    }
    @Suppress("UNCHECKED_CAST")
    return iov as Array<StructIov?>
}
