package org.cikit.libjail

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.io.path.pathString

@Serializable
data class MountInfo(
    @SerialName("fstype")
    val fsType: String,
    val special: String,
    val node: String,
    val opts: List<String> = emptyList(),
    @SerialName("fsid")
    val fsId: String? = null,
) {
    fun parseFsId(): FsId? {
        if (fsId == null) {
            return null
        }
        var v0 = 0
        v0 = v0 or (fsId.substring(0..1)).toInt(16)
        v0 = v0 or ((fsId.substring(2..3)).toInt(16) shl 8)
        v0 = v0 or ((fsId.substring(4..5)).toInt(16) shl 16)
        v0 = v0 or ((fsId.substring(6..7)).toInt(16) shl 24)
        var v1 = 0
        v1 = v1 or (fsId.substring(8 .. 9)).toInt(16)
        v1 = v1 or ((fsId.substring(10 .. 11)).toInt(16) shl 8)
        v1 = v1 or ((fsId.substring(12 .. 13)).toInt(16) shl 16)
        v1 = v1 or ((fsId.substring(14 .. 15)).toInt(16) shl 24)
        return FsId(v0, v1)
    }
}

data class FsId(val val0: Int, val val1: Int) {
    fun encodeToDecimalFsId() = buildString {
        append("FSID:")
        append(val0)
        append(":")
        append(val1)
    }
}

@Serializable
private data class LibXOMountInfo(
    val mount: MountInfoContainer
) {
    @Serializable
    data class MountInfoContainer(
        val mounted: List<MountInfo>
    )
}

suspend fun readMountInfo(): List<MountInfo> {
    val output = pRead(listOf("mount", "--libxo", "json", "-v"))
    return json.decodeFromString<LibXOMountInfo>(output).mount.mounted
}

@Serializable
private data class SysctlMountInfo(
    val mounted: List<MountInfo>
)

const val OID_JAIL_MNT_INFO = "security.jail.mntinfojson"

fun readJailMountInfo(): List<MountInfo>? {
    val data = sysctlByNameString(OID_JAIL_MNT_INFO) { func, errnum ->
        if (errnum != 2 /* ENOENT */) {
            error("$func(): error code $errnum")
        }
    } ?: return null
    return json.decodeFromString<SysctlMountInfo>(data).mounted
}

fun nmount(
    fsType: String,
    fsPath: Path,
    target: Path? = null,
    args: Map<String, String> = emptyMap(),
    readOnly: Boolean = false,
    ignore: Boolean = false,
    errorHandler: (String, Int) -> Unit = defaultErrorHandler
) {
    val allArgs = buildMap {
        put("fstype", fsType)
        put("fspath", fsPath.pathString)
        if (target != null) {
            put("target", target.pathString)
        }
        putAll(args)
    }
    trace(TraceEvent.Ffi("nmount", allArgs.entries.map { (k, v) -> "$k=$v" }))
    var flags = 0L
    if (readOnly) {
        flags = flags or MNT_RDONLY
    }
    if (ignore) {
        flags = flags or MNT_IGNORE
    }
    Ffi.nmount(allArgs, (flags and 0xFFFFFFFF).toInt(), errorHandler)
}

fun unmount(
    fsId: FsId,
    force: Boolean,
    errorHandler: (String, Int) -> Unit = defaultErrorHandler
) {
    var flags = MNT_BYFSID
    if (force) {
        flags = flags or MNT_FORCE
    }
    val decimalFsId = fsId.encodeToDecimalFsId()
    trace(TraceEvent.Ffi("unmount", decimalFsId, flags.toString()))
    Ffi.unmount(decimalFsId, (flags and 0xFFFFFFFF).toInt(), errorHandler)
}

fun unmount(
    dir: String,
    force: Boolean,
    errorHandler: (String, Int) -> Unit = defaultErrorHandler
) {
    var flags = 0L
    if (force) {
        flags = flags or MNT_FORCE
    }
    trace(TraceEvent.Ffi("unmount", dir, flags.toString()))
    Ffi.unmount(dir, (flags and 0xFFFFFFFF).toInt(), errorHandler)
}

private const val MNT_RDONLY   = 0x0000000000000001L
private const val MNT_IGNORE   = 0x0000000000800000L
private const val MNT_BYFSID   = 0x0000000008000000L
private const val MNT_FORCE    = 0x0000000000080000L
