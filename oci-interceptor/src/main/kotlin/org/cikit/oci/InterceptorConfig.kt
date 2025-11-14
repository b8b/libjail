@file:UseSerializers(UPathSerializer::class)

package org.cikit.oci

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.cikit.forte.core.DecodeUrlPath
import org.cikit.forte.core.UPath
import kotlin.reflect.KProperty1

typealias Phase = KProperty1<Hooks, Collection<HookConfig>>

interface HookConfig {
    val command: List<String>
    val appendArgs: Boolean
    val timeout: Long
    val preempt: Boolean
    val template: UPath?
    val enabled: Boolean
}

@Serializable
data class PreHookConfig(
    override val command: List<String> = listOf("/bin/sh", "-s", "--"),
    override val appendArgs: Boolean = true,
    override val timeout: Long = 30,
    override val preempt: Boolean = false,
    override val template: UPath? = null,
    override val enabled: Boolean = true,
) : HookConfig

@Serializable
data class PostHookConfig(
    override val command: List<String> = listOf("/bin/sh", "-s", "--"),
    override val appendArgs: Boolean = true,
    override val timeout: Long = 30,
    override val template: UPath? = null,
    override val enabled: Boolean = true,
) : HookConfig {
    override val preempt: Boolean = false
}

@Serializable
data class Hooks(
    val templatesDir: List<UPath> = emptyList(),
    val precreate: List<PreHookConfig> = emptyList(),
    val postcreate: List<PostHookConfig> = emptyList(),
    val prestart: List<PreHookConfig> = emptyList(),
    val poststart: List<PostHookConfig> = emptyList(),
    val predelete: List<PreHookConfig> = emptyList(),
    val postdelete: List<PostHookConfig> = emptyList(),
    val preexec: List<PreHookConfig> = emptyList(),
    val postexec: List<PostHookConfig> = emptyList(),
    val prekill: List<PreHookConfig> = emptyList(),
    val postkill: List<PostHookConfig> = emptyList(),
)

@Serializable
data class CniConfig(
    val templatesDir: List<UPath> = emptyList(),
    val plugin: List<CniPluginConfig> = emptyList(),
)

@Serializable
data class CniPluginConfig(
    val type: String,
    val defaultCommand: List<String> = listOf("/bin/sh", "-s", "--"),
    val delegate: DelegationMode = DelegationMode.NONE,
    val delegateCommand: List<String>? = null,
    val prepare: UPath? = null,
    val prepareCommand: List<String>? = null,
    val setup: UPath? = null,
    val setupCommand: List<String>? = null,
    val prepareCheck: UPath? = null,
    val prepareCheckCommand: List<String>? = null,
    val check: UPath? = null,
    val checkCommand: List<String>? = null,
    val prepareDelete: UPath? = null,
    val prepareDeleteCommand: List<String>? = null,
    val delete: UPath? = null,
    val deleteCommand: List<String>? = null,
    val timeout: Long = 30,
    val enabled: Boolean = true,
) {
    @Serializable
    enum class DelegationMode {
        NONE,
        IPAM,
        CNI
    }
}

@Serializable
data class Interceptor(
    @SerialName("override_log")
    val overrideLog: String? = null,
    @SerialName("override_log_format")
    val overrideLogFormat: String? = null,
    @SerialName("override_log_level")
    val overrideLogLevel: String? = null,
)

@Serializable
data class InterceptorConfig(
    val interceptor: Interceptor = Interceptor(),
    val hooks: Hooks = Hooks(),
    val cni: CniConfig = CniConfig(),
)

class UPathSerializer : KSerializer<UPath> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("UPath", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): UPath {
        return UPath(decoder.decodeString(), DecodeUrlPath)
    }

    override fun serialize(encoder: Encoder, value: UPath) {
        encoder.encodeString(value.toUrlPath())
    }
}
