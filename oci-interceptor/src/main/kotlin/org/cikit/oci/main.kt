package org.cikit.oci

import com.github.ajalt.clikt.core.main
import org.cikit.oci.jail.PkgbuildCommand
import org.cikit.oci.jail.OciJailInterceptor
import org.cikit.oci.jail.RcJailInterceptor

fun main(args: Array<String>) {
    val prg = System.getenv("INTERCEPT_OCI_RUNTIME_NAME")
    val main = when(prg) {
        "pkgbuild" -> PkgbuildCommand()
        "ocijail" -> OciJailInterceptor()
        "rcjail" -> RcJailInterceptor()
        "cni" -> CniPluginCommand()
        else -> GenericInterceptor()
    }
    main.main(args)
}
