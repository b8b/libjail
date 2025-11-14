package org.cikit.oci.cni

import kotlinx.serialization.Serializable

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

    fun toIPConfig(): IPConfig {
        val isDefaultGw = true // by cniConfig
        val isGw = true // by cniConfig

        val gwByVersion = mutableMapOf<IPConfig.IpVersion, String?>()
        for (ipVersion in IPConfig.IpVersion.entries) {
            val gw = routes.firstNotNullOfOrNull { route ->
                route.gw?.takeIf {
                    route.dst == ipVersion.defaultNet
                }
            }
            if (gw != null) {
                gwByVersion[ipVersion] = gw
            }
        }

        val ips = ips.mapIndexed { index, ip ->
            val address = ip.address.substringBefore('/')
            val ipVersion = ip.version
                ?.let(IPConfig.IpVersion::ofString)
                ?: IPConfig.IpVersion.guess(address)
            if (gwByVersion[ipVersion] == null) {
                gwByVersion[ipVersion] = ip.gateway
            }
            IPConfig.Ip(
                address = address,
                prefixLen = ip.address
                    .substringAfter('/', ipVersion.len.toString())
                    .toInt(),
                version = ipVersion,
                primary = index == 0
            )
        }

        val defaultRoutes = mutableMapOf<String, IPConfig.Route>()
        val additionalRoutes = mutableListOf<IPConfig.Route>()

        when {
            isDefaultGw -> {
                for ((ipVersion, gw) in gwByVersion) {
                    defaultRoutes[ipVersion.defaultNet] = IPConfig.Route(
                        dst = ipVersion.defaultNet,
                        default = true,
                        gw = gw,
                        version = ipVersion
                    )
                }
                for (route in routes) {
                    if (!defaultRoutes.containsKey(route.dst)) {
                        val ip = route.dst.substringBefore('/')
                        val ipVersion = IPConfig.IpVersion.guess(ip)
                        additionalRoutes += IPConfig.Route(
                            dst = route.dst,
                            gw = route.gw ?: gwByVersion[ipVersion],
                            version = ipVersion
                        )
                    }
                }
            }
            isGw -> {
                for (route in routes) {
                    val ipVersion = IPConfig.IpVersion.guess(route.dst)
                    val route = IPConfig.Route(
                        dst = route.dst,
                        default = route.dst == ipVersion.defaultNet,
                        gw = route.gw ?: gwByVersion[ipVersion],
                        version = ipVersion
                    )
                    when {
                        !route.default -> additionalRoutes += route
                        !defaultRoutes.containsKey(ipVersion.defaultNet) -> {
                            defaultRoutes[ipVersion.defaultNet] = route
                        }
                    }
                }
            }
        }

        return IPConfig(
            ips = ips,
            routes = additionalRoutes + defaultRoutes.values,
            dns = IPConfig.Dns(
                nameservers = dns.nameservers,
                domain = dns.domain,
                search = dns.search,
                options = dns.options
            )
        )
    }
}

@Serializable
data class IPConfig(
    val ips: List<Ip> = emptyList(),
    val routes: List<Route> = emptyList(),
    val dns: Dns = Dns()
) {
    @Serializable
    data class Ip(
        val address: String,
        val prefixLen: Int,
        val version: IpVersion,
        val primary: Boolean = false
    )

    @Serializable
    data class Route(
        val dst: String,
        val default: Boolean = false,
        val gw: String? = null,
        val version: IpVersion,
    )

    @Serializable
    data class Dns(
        val nameservers: List<String> = emptyList(),
        val domain: String? = null,
        val search: List<String> = emptyList(),
        val options: List<String> = emptyList(),
    )

    @Serializable
    enum class IpVersion(val len: Int, val defaultNet: String) {
        Inet4(32, "0.0.0.0/0"),
        Inet6(128, "::/0");

        companion object {
            fun ofString(input: String) = when (input) {
                "4" -> Inet4
                "6" -> Inet6
                else -> throw IllegalArgumentException(
                    "unsupported ip version '${input}'"
                )
            }

            fun guess(ip: String): IpVersion {
                val is4 = ip.split('.')
                    .all { it.toIntOrNull() in 0 .. 255 }
                return if (is4) {
                    Inet4
                } else {
                    Inet6
                }
            }
        }
    }
}
