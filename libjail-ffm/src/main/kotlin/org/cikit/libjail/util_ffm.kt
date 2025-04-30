package org.cikit.libjail

import java.lang.foreign.*

internal object Ffi : FfiFunctions {

    private val linker = Linker.nativeLinker()
    private val lookup = linker.defaultLookup()

    private val ccs = Linker.Option.captureCallState("errno")

    private val nmountHandle = linker.downcallHandle(
        lookup.find("nmount").orElseThrow(),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,       // return type: int
            ValueLayout.ADDRESS,        // iov: pointer to struct iovec
            ValueLayout.JAVA_INT,       // niov: int
            ValueLayout.JAVA_INT        // flags: int
        ),
        ccs
    )

    private val unmountHandle = linker.downcallHandle(
        lookup.find("unmount").orElseThrow(),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,       // return type: int
            ValueLayout.ADDRESS,        // dir: char*
            ValueLayout.JAVA_INT        // flags: int
        ),
        ccs
    )

    private val jailAttachHandle = linker.downcallHandle(
        lookup.find("jail_attach").orElseThrow(),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,       // return type: int
            ValueLayout.JAVA_INT,       // jid: int
        ),
        ccs
    )

    private val jailRemoveHandle = linker.downcallHandle(
        lookup.find("jail_remove").orElseThrow(),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,       // return type: int
            ValueLayout.JAVA_INT,       // jid: int
        ),
        ccs
    )

    private val sysctlByNameHandle = linker.downcallHandle(
        lookup.find("sysctlbyname").orElseThrow(),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,  // return type: int
            ValueLayout.ADDRESS,   // name: char* name
            ValueLayout.ADDRESS,   // oldp: void* buffer to store result
            ValueLayout.ADDRESS,   // oldlenp: size_t* buffer size (in/out)
            ValueLayout.ADDRESS,   // newp: void*  new value (setter) or null
            ValueLayout.JAVA_LONG, // newlen: size_t new value length (or 0)
        ),
        ccs
    )

    private val capturedStateLayout = Linker.Option.captureStateLayout()
    private val errnoHandle = capturedStateLayout.varHandle(
        MemoryLayout.PathElement.groupElement("errno")
    )

    override fun nmount(
        iov: Map<String, String>,
        flags: Int,
        errorHandler: (String, Int) -> Unit
    ) {
        Arena.ofConfined().use { arena ->
            val iovLayout = MemoryLayout.structLayout(
                ValueLayout.ADDRESS.withName("iov_base"),
                ValueLayout.JAVA_LONG.withName("iov_len")
            )
            val iovArrayLayout = MemoryLayout.sequenceLayout(
                iov.size * 2L,
                iovLayout
            )
            val baseHandle = iovArrayLayout.varHandle(
                MemoryLayout.PathElement.sequenceElement(),
                MemoryLayout.PathElement.groupElement("iov_base")
            )
            val lenHandle = iovArrayLayout.varHandle(
                MemoryLayout.PathElement.sequenceElement(),
                MemoryLayout.PathElement.groupElement("iov_len")
            )
            val iovArray = arena.allocate(iovArrayLayout)
            iov.entries.forEachIndexed { i, (k, v) ->
                val kIndex = i * 2L
                val kData = arena.allocateFrom(k)
                baseHandle.set(iovArray, 0L, kIndex, kData)
                lenHandle.set(iovArray, 0L, kIndex, kData.byteSize())
                val vIndex = kIndex + 1L
                val vData = arena.allocateFrom(v)
                baseHandle.set(iovArray, 0L, vIndex, vData)
                lenHandle.set(iovArray, 0L, vIndex, vData.byteSize())
            }
            val capturedState = arena.allocate(capturedStateLayout)

            val rc = nmountHandle.invokeExact(
                capturedState,
                iovArray,
                ((iov.size * 2L) and 0xFFFFFFFF).toInt(),
                flags
            ) as Int

            if (rc != 0) {
                val err = errnoHandle.get(capturedState, 0L) as Int
                errorHandler("nmount", err)
            }
        }
    }

    override fun unmount(
        dir: String,
        flags: Int,
        errorHandler: (String, Int) -> Unit
    ) {
        Arena.ofConfined().use { arena ->
            val dirData = arena.allocateFrom(dir)
            val capturedState = arena.allocate(capturedStateLayout)
            val rc = unmountHandle.invokeExact(
                capturedState,
                dirData,
                flags
            ) as Int
            if (rc != 0) {
                val err = errnoHandle.get(capturedState, 0L) as Int
                errorHandler("unmount", err)
            }
        }
    }

    override fun jailAttach(
        jid: Int,
        errorHandler: (String, Int) -> Unit
    ) {
        Arena.ofConfined().use { arena ->
            val capturedState = arena.allocate(capturedStateLayout)
            val rc = jailAttachHandle.invokeExact(
                capturedState,
                jid
            ) as Int
            if (rc != 0) {
                val err = errnoHandle.get(capturedState, 0L) as Int
                errorHandler("jail_attach", err)
            }
        }
    }

    override fun jailRemove(
        jid: Int,
        errorHandler: (String, Int) -> Unit
    ) {
        Arena.ofConfined().use { arena ->
            val capturedState = arena.allocate(capturedStateLayout)
            val rc = jailRemoveHandle.invokeExact(
                capturedState,
                jid
            ) as Int
            if (rc != 0) {
                val err = errnoHandle.get(capturedState, 0L) as Int
                errorHandler("jail_remove", err)
            }
        }
    }

    override fun sysctlByNameString(
        name: String,
        errorHandler: (String, Int) -> Unit
    ): String? {
        Arena.ofConfined().use { arena ->
            val nameData = arena.allocateFrom(name)
            val oldLenData = arena.allocate(ValueLayout.JAVA_LONG.byteSize())
            val capturedState = arena.allocate(capturedStateLayout)
            val rc1 = sysctlByNameHandle.invokeExact(
                capturedState,
                nameData,             // name
                MemorySegment.NULL,   // oldp
                oldLenData,           // oldlenp
                MemorySegment.NULL,   // newp
                0L,                   // newlen
            ) as Int
            if (rc1 != 0) {
                val err = errnoHandle.get(capturedState, 0L) as Int
                errorHandler("sysctlbyname", err)
                return null
            }
            val buf = arena.allocate(
                oldLenData.get(ValueLayout.JAVA_LONG, 0)
            )
            val rc2 = sysctlByNameHandle.invokeExact(
                capturedState,
                nameData,             // name
                buf,                  // oldp
                oldLenData,           // oldlenp
                MemorySegment.NULL,   // newp
                0L,                   // newlen
            ) as Int
            if (rc2 != 0) {
                val err = errnoHandle.get(capturedState, 0L) as Int
                errorHandler("sysctlbyname", err)
                return null
            }
            return buf.getString(0)
        }
    }

    override fun sysctlByNameString(
        name: String,
        value: String,
        errorHandler: (String, Int) -> Unit
    ) {
        Arena.ofConfined().use { arena ->
            val nameData = arena.allocateFrom(name)
            val valueData = arena.allocateFrom(value)
            val capturedState = arena.allocate(capturedStateLayout)
            val rc = sysctlByNameHandle.invokeExact(
                capturedState,
                nameData,             // name
                MemorySegment.NULL,   // oldp
                MemorySegment.NULL,   // oldlenp
                valueData,            // newp
                valueData.byteSize()  // newlen
            ) as Int
            if (rc != 0) {
                val err = errnoHandle.get(capturedState, 0L) as Int
                errorHandler("sysctlbyname", err)
            }
        }
    }

    override fun sysctlByNameInt32(
        name: String,
        errorHandler: (String, Int) -> Unit
    ): Int? {
        Arena.ofConfined().use { arena ->
            val nameData = arena.allocateFrom(name)
            val result = arena.allocate(ValueLayout.JAVA_INT.byteSize())
            val oldLenData = arena.allocate(ValueLayout.JAVA_LONG.byteSize())
            oldLenData.set(ValueLayout.JAVA_LONG, 0L, oldLenData.byteSize())
            val capturedState = arena.allocate(capturedStateLayout)
            val rc = sysctlByNameHandle.invokeExact(
                capturedState,
                nameData,               // name
                result,                 // oldp
                oldLenData,             // oldlenp
                MemorySegment.NULL,     // newp
                0L,                     // newlen
            ) as Int
            if (rc != 0) {
                val err = errnoHandle.get(capturedState, 0L) as Int
                errorHandler("sysctlbyname", err)
                return null
            }
            return result.get(ValueLayout.JAVA_INT, 0)
        }
    }
}
