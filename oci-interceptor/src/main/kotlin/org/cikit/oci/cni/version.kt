package org.cikit.oci.cni

import kotlinx.serialization.Serializable

@Serializable
data class VersionResult(
    val cniVersion: String = "1.0.0",
    val supportedVersions: Set<String> = setOf(
        "0.3.0","0.3.1","0.4.0","1.0.0"
    )
)
