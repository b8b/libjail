package org.cikit.libjail

import kotlin.io.path.*

class VmContext(internal val opaque: Any?)

private val vmmPath = Path("/dev/vmm")

private const val OID_VMM_CREATE = "hw.vmm.create"
private const val OID_VMM_DESTROY = "hw.vmm.destroy"

sealed class VmCreateResult(val error: Int) {
    data object Ok :           VmCreateResult(0)
    data object NoPermission : VmCreateResult(1  /* EPERM   */)
    data object Exists :       VmCreateResult(17 /* EEXISTS */)
}

sealed class VmDestroyResult(val error: Int) {
    data object Ok :           VmDestroyResult(0)
    data object NoPermission : VmDestroyResult(1  /* EPERM   */)
    data object NotFound :     VmDestroyResult(2  /* ENOENT  */)
}

fun listVms(): List<String> {
    if (vmmPath.isDirectory()) {
        return vmmPath.listDirectoryEntries()
            .filter { p -> !p.isSymbolicLink() && !p.isDirectory() }
            .map { p -> p.name }
    }
    return emptyList()
}

fun vmCreate(name: String): VmCreateResult {
    var result: VmCreateResult = VmCreateResult.Ok
    sysctlByNameString(OID_VMM_CREATE, name) { cmd, rc ->
        result = when (rc) {
            1  /* EPERM  */ -> VmCreateResult.NoPermission
            17 /* EEXIST */ -> VmCreateResult.Exists
            else -> error("$cmd(): error code $rc")
        }
    }
    return result
}

fun vmDestroy(name: String): VmDestroyResult {
    var result: VmDestroyResult = VmDestroyResult.Ok
    sysctlByNameString(OID_VMM_DESTROY, name) { cmd, rc ->
        result = when (rc) {
            1  /* EPERM  */ -> VmDestroyResult.NoPermission
            2  /* ENOENT */ -> VmDestroyResult.NotFound
            22 /* EINVAL */ -> VmDestroyResult.NotFound
            else -> error("$cmd(): error code $rc")
        }
    }
    return result
}
