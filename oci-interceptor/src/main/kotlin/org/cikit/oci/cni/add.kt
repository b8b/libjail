package org.cikit.oci.cni

import inet.ipaddr.AddressStringParameters
import inet.ipaddr.IPAddress
import inet.ipaddr.IPAddressString
import inet.ipaddr.IPAddressStringParameters
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import java.math.BigInteger

private val json = Json { ignoreUnknownKeys = true }

@Serializable
data class AddResult(
    val cniVersion: String = "1.0.0",
    val interfaces: List<Interface> = emptyList(),
    val ips: List<Ip> = emptyList(),
    val routes: List<Route> = emptyList(),
    val dns: Dns = Dns()
) {
    @Serializable
    data class Interface(
        val name: String,
        val mac: String? = null,
        val mtu: UInt? = null,
        val sandbox: String? = null,
    )

    @Serializable
    data class Ip(
        val address: String,
        val version: String? = null,
        val gateway: String? = null,
        val `interface`: UInt? = null,
    )

    @Serializable
    data class Route(
        val dst: String,
        val gw: String? = null,
        val mtu: UInt? = null,
        val advmss: UInt? = null,
        val priority: UInt? = null,
        val table: UInt? = null,
        val scope: UInt? = null,
    )

    @Serializable
    data class Dns(
        val nameservers: List<String> = emptyList(),
        val domain: String? = null,
        val search: List<String> = emptyList(),
        val options: List<String> = emptyList(),
    )

    fun toIFConfig(netConfig: JsonObject): IFConfig {
        val isDefaultGw = netConfig["isDefaultGateway"] == JsonPrimitive(true)
        val isGw = netConfig["isGateway"] == JsonPrimitive(true)

        // for each ip version, the first gateway appearing in the ips section
        val gwByVersion = mutableMapOf<IPAddress.IPVersion, IPAddress>()

        val ifIndexUsed = mutableSetOf<UInt>()
        var nullIfUsed = false

        val ips = ips.map { ip ->
            val address = parseIp(ip.address)
            val gateway = ip.gateway?.let { parseIp(it) }
            if (gateway != null) {
                gwByVersion.computeIfAbsent(gateway.ipVersion) { gateway }
            }
            val ifIndex = ip.`interface`
            val primary = if (ifIndex == null) {
                if (nullIfUsed) {
                    false
                } else {
                    nullIfUsed = true
                    true
                }
            } else {
                if (ifIndex in ifIndexUsed) {
                    false
                } else {
                    ifIndexUsed += ifIndex
                    true
                }
            }
            IFConfig.Ip(
                address = address,
                gateway = gateway,
                ifName = ifIndex?.let { interfaces[it.toInt()].name },
                isPrimary = primary
            )
        }

        val defaultRoutes: MutableMap<IPAddress.IPVersion, IFConfig.Route> =
            mutableMapOf()
        val additionalRoutes = mutableListOf<IFConfig.Route>()

        if (isDefaultGw) {
            val dst = IPAddressString("0/0").address
            for ((ipVersion, gateway) in gwByVersion) {
                val far = ips.none { ip ->
                    ip.network?.contains(gateway) == true
                }
                defaultRoutes[ipVersion] = IFConfig.Route(
                    dst = when (ipVersion) {
                        IPAddress.IPVersion.IPV4 -> dst.toIPv4()
                        IPAddress.IPVersion.IPV6 -> dst.toIPv6()
                    },
                    isDefault = true,
                    isMulticast = false,
                    gateway = gateway,
                    isFarGateway = far,
                    ipVersion = ipVersion
                )
            }
        }

        if (isGw || isDefaultGw) {
            for (route in routes) {
                val dst = parseIp(route.dst)
                val gw = route.gw?.let { parseIp(it) }
                    ?: if (dst.isMulticast) {
                        null
                    } else {
                        gwByVersion[dst.ipVersion]
                    }
                val far = gw != null && ips.none { ip ->
                    ip.network?.contains(gw) == true
                }
                val route = IFConfig.Route(
                    dst = dst,
                    isDefault = dst.prefixLength == 0,
                    isMulticast = dst.isMulticast,
                    gateway = gw,
                    isFarGateway = far,
                    ipVersion = dst.ipVersion,
                    mtu = route.mtu,
                    advmss = route.advmss,
                    priority = route.priority,
                    table = route.table,
                    scope = route.scope
                )
                if (route.isDefault) {
                    defaultRoutes[dst.ipVersion] = route
                } else {
                    additionalRoutes += route
                }
            }
        }

        return IFConfig(
            interfaces = interfaces,
            ips = ips,
            routes = defaultRoutes.values + additionalRoutes,
            dns = dns
        )
    }
}

private fun parseIp(input: String): IPAddress {
    val addressString = IPAddressString(
        input,
        IPAddressStringParameters.Builder()
            .allowAll(false)
            .setRangeOptions(AddressStringParameters.RangeParameters.NO_RANGE)
            .toParams()
    )
    val address = addressString.address
    require(address != null) {
        "invalid IP address: $addressString"
    }
    return address
}

class IPAddressSerializer : KSerializer<IPAddress> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("IPAddress", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): IPAddress {
        return parseIp(decoder.decodeString())
    }

    override fun serialize(encoder: Encoder, value: IPAddress) {
        encoder.encodeString(value.toCanonicalString())
    }
}

@Serializable
data class IFConfig(
    val interfaces: List<AddResult.Interface> = emptyList(),
    val ips: List<Ip> = emptyList(),
    val routes: List<Route> = emptyList(),
    val dns: AddResult.Dns = AddResult.Dns()
) {
    companion object {
        fun decodeFromJsonElement(jsonElement: JsonElement): IFConfig =
            json.decodeFromJsonElement(jsonElement)
    }

    @Serializable
    data class Ip(
        @Serializable(with = IPAddressSerializer::class)
        val address: IPAddress,
        @Serializable(with = IPAddressSerializer::class)
        val network: IPAddress? = null,
        val prefixLen: Int,
        @Serializable(with = IPAddressSerializer::class)
        val gateway: IPAddress? = null,
        val isFarGateway: Boolean,
        val ipVersion: IPAddress.IPVersion,
        val ifName: String? = null,
        val isPrimary: Boolean
    ) {
        constructor(
            address: IPAddress,
            gateway: IPAddress?,
            ifName: String?,
            isPrimary: Boolean = false
        ) : this(
            address = address.withoutPrefixLength(),
            network = address.toPrefixBlock()
                .takeIf { it.count != BigInteger.ONE },
            prefixLen = when {
                address.isPrefixed -> address.networkPrefixLength
                else -> IPAddress.getBitCount(address.ipVersion)
            },
            gateway = gateway?.also {
                require(!it.isPrefixed) {
                    "gateway cannot have a prefix length: $it"
                }
            },
            isFarGateway = gateway
                ?.let { !address.toPrefixBlock().contains(it) }
                ?: false,
            ipVersion = address.ipVersion,
            ifName = ifName,
            isPrimary = isPrimary
        )
    }

    @Serializable
    data class Route(
        @Serializable(with = IPAddressSerializer::class)
        val dst: IPAddress,
        val isDefault: Boolean,
        val isMulticast: Boolean,
        @Serializable(with = IPAddressSerializer::class)
        val gateway: IPAddress? = null,
        val isFarGateway: Boolean,
        val ipVersion: IPAddress.IPVersion,
        val mtu: UInt? = null,
        val advmss: UInt? = null,
        val priority: UInt? = null,
        val table: UInt? = null,
        val scope: UInt? = null,
    )

    fun merge(ifConfig: JsonObject): IFConfig {
        val left = Json.encodeToJsonElement(this).jsonObject
        val merged = mergeResult(left, ifConfig)
        return json.decodeFromJsonElement(merged)
    }

    fun toAddResult(): AddResult = AddResult(
        interfaces = interfaces,
        ips = ips.map { ip ->
            AddResult.Ip(
                address = "${ip.address}/${ip.prefixLen}",
                version = when (ip.ipVersion) {
                    IPAddress.IPVersion.IPV4 -> "4"
                    IPAddress.IPVersion.IPV6 -> "6"
                },
                gateway = ip.gateway?.toString(),
                `interface` = ip.ifName?.let { ifName ->
                    interfaces.indexOfFirst { it.name == ifName }.toUInt()
                }
            )
        },
        routes = routes.map { route ->
            AddResult.Route(
                dst = route.dst.toString(),
                gw = route.gateway?.toString(),
                mtu = route.mtu,
                advmss = route.advmss,
                priority = route.priority,
                table = route.table,
                scope = route.scope
            )
        },
        dns = dns
    )

    private fun mergeResult(
        left: JsonObject,
        right: JsonObject,
        path: List<String> = emptyList()
    ): JsonObject {
        val emitted = mutableSetOf<String>()
        return buildJsonObject {
            for ((k, leftValue) in left) {
                val newPath = path + k
                val rightValue = right[k]
                if (rightValue == null) {
                    put(k, leftValue)
                } else if (rightValue !is JsonNull) {
                    put(k, mergeResultValue(leftValue, rightValue, newPath))
                }
                emitted += k
            }
            for ((k, rightValue) in right) {
                if (k !in emitted) {
                    put(k, rightValue)
                }
            }
        }
    }

    private fun mergeResultValue(
        left: JsonElement,
        right: JsonElement,
        path: List<String>
    ): JsonElement {
        return when (left) {
            is JsonObject -> when (right) {
                is JsonObject -> mergeResult(left, right)
                else -> right
            }
            is JsonArray -> when (right) {
                is JsonArray -> when (path) {
                        listOf("interfaces") -> {
                            mergeBy(left, right, "name", path)
                        }
                        listOf("ips") -> {
                            mergeBy(left, right, "address", path)
                        }
                        listOf("routes") -> {
                            mergeBy(left, right, "dst", path)
                        }
                        else -> {
                            buildJsonArray {
                                for (item in left) {
                                    add(item)
                                }
                                for (item in right) {
                                    add(item)
                                }
                            }
                        }
                    }

                else -> right
            }
            else -> right
        }
    }

    private fun mergeBy(
        left: JsonArray,
        right: JsonArray,
        field: String,
        path: List<String>
    ): JsonArray {
        val leftAsObject = buildJsonObject {
            for (item in left) {
                item as JsonObject
                put(item.getValue(field).jsonPrimitive.content, item)
            }
        }
        val rightAsObject = buildJsonObject {
            for (item in right) {
                item as JsonObject
                put(item.getValue(field).jsonPrimitive.content, item)
            }
        }
        val merged = mergeResult(leftAsObject, rightAsObject, path)
        return buildJsonArray {
            for ((_, v) in merged) {
                add(v as JsonObject)
            }
        }
    }
}
