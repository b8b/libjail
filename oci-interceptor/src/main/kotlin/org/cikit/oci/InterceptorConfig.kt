package org.cikit.oci

import kotlinx.serialization.Serializable

@Serializable
data class InterceptorConfig(
    val hooks: Hooks = Hooks()
) {
    @Serializable
    data class Hooks(
        val precreate: PreHookConfig? = null,
        val postcreate: PostHookConfig? = null,
        val prestart: PreHookConfig? = null,
        val poststart: PostHookConfig? = null,
        val predelete: PreHookConfig? = null,
        val postdelete: PostHookConfig? = null,
        val preexec: PreHookConfig? = null,
        val postexec: PostHookConfig? = null,
        val prekill: PreHookConfig? = null,
        val postkill: PostHookConfig? = null,
    )

    interface HookConfig {
        val interpreter: List<String>
        val timeout: Long
        val preempt: Boolean
        val script: String
    }

    @Serializable
    data class PreHookConfig(
        override val interpreter: List<String> = listOf("/bin/sh", "-c"),
        override val timeout: Long = 30,
        val lock: Boolean = false,
        override val preempt: Boolean = false,
        override val script: String
    ) : HookConfig

    @Serializable
    data class PostHookConfig(
        override val interpreter: List<String> = listOf("/bin/sh", "-c"),
        override val timeout: Long = 30,
        override val script: String
    ) : HookConfig {
        override val preempt: Boolean
            get() = false
    }
}
