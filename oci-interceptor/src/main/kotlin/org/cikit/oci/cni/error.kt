package org.cikit.oci.cni

import kotlinx.serialization.Serializable
import java.lang.RuntimeException

class ErrorResultException(
    message: String,
    errorCode: ErrorCode,
    val result: ErrorResult = ErrorResult(code = errorCode.code, msg = message)
) : RuntimeException(message)

@Serializable
data class ErrorResult(
    val cniVersion: String = "0.4.0",
    val code: Int,
    val msg: String,
    val details: String = msg,
)

/**
 * Error codes 0-99 are reserved for well-known errors.
 * Values of 100+ can be freely used for plugin specific errors.
 */
enum class ErrorCode(val code: Int) {
    /**
     * 1 	Incompatible CNI version
     */
    IncompatibleVersion(1),

    /**
     * 2 	Unsupported field in network configuration. The error message must contain the key and value of the
     *      unsupported field.
     */
    UnsupportedField(2),

    /**
     * 3 	Container unknown or does not exist. This error implies the runtime does not need to perform any
     *      container network cleanup (for example, calling the DEL action on the container).
     */
    ContainerUnknown(3),

    /**
     * 4 	Invalid necessary environment variables, like CNI_COMMAND, CNI_CONTAINERID, etc. The error message must
     *      contain the names of invalid variables.
     */
    InvalidEnv(4),

    /**
     * 5 	I/O failure. For example, failed to read network config bytes from stdin.
     */
    IOFailure(5),

    /**
     * 6 	Failed to decode content. For example, failed to unmarshal network config from bytes or failed to
     *      decode version info from string.
     */
    DecodeFailure(6),

    /**
     * 7 	Invalid network config. If some validations on network configs do not pass, this error will be raised.
     */
    InvalidNetworkConfig(7),

    /**
     * 11 	Try again later. If the plugin detects some transient condition that should clear up, it can use this
     *      code to notify the runtime it should re-try the operation later.
     */
    TempError(11),

    /**
     * 50 	The plugin is not available (i.e. cannot service ADD requests)
     */
    PluginDisabled(50),

    /**
     * 51 	The plugin is not available, and existing containers in the network may have limited connectivity.
     */
    PluginUnavailable(51),
}
