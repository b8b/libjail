package org.cikit.oci

import kotlinx.serialization.Serializable

@Serializable
data class OciConfig(
    val ociVersion: String,
    val annotations: Map<String, String> = emptyMap(),
    val hostname: String,
    val root: Root,
    val mounts: List<Mount> = emptyList(),
    val process: Process,
    val hooks: Map<String, List<Hook>> = emptyMap(),
) {
    @Serializable
    data class Root(
        val path: String
    )

    @Serializable
    data class Process(
        val user: User,
        val cwd: String,
        val env: List<String> = emptyList(),
        val args: List<String> = emptyList(),
        val terminal: Boolean = false,
        val rlimits: List<RLimit> = emptyList()
    )

    @Serializable
    data class User(
        val uid: Long,
        val gid: Long,
        val umask: Long? = null,
        val additionalGids: List<Long> = emptyList()
    )

    @Serializable
    data class RLimit(
        val hard: Long,
        val soft: Long,
        val type: String
    )

    @Serializable
    data class Mount(
        val type: String,
        val source: String,
        val destination: String,
        val options: List<String> = emptyList()
    )

    @Serializable
    data class Hook(
        val path: String
    )
}