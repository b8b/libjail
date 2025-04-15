package org.cikit.oci

import com.github.ajalt.clikt.core.main
import org.cikit.oci.jail.OciJailInterceptor

fun main(args: Array<String>) {
    val prg = System.getenv("INTERCEPT_OCI_RUNTIME_NAME")
    val main = when(prg) {
        "ocijail" -> OciJailInterceptor()
        else -> GenericInterceptor()
    }
    main.main(args)
}
