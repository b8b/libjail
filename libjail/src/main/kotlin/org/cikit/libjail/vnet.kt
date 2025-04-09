package org.cikit.libjail

data class NetifParameters(
    val name: String,
    val description: String,
    val driverName: String,
    val lines: List<String>
)

suspend fun readNetifParameters(
    jail: JailParameters? = null
): List<NetifParameters> {
    val jailArgs = if (jail == null) {
        emptyArray()
    } else {
        arrayOf("-j", jail.name)
    }
    val output = pRead(listOf("ifconfig", *jailArgs, "-D"))
    val networkInterfaces = mutableListOf<NetifParameters>()
    var ifName = ""
    var description = ""
    var driverName = ""
    val lines = mutableListOf<String>()
    for (line in output.split("\n")) {
        when {
            line.trim().startsWith("description:") -> {
                description = line.substringAfter(':').trim()
            }
            line.trim().startsWith("drivername:") -> {
                driverName = line.substringAfter(':').trim()
            }
            else -> {
                val maybeIfName = line.substringBefore(':', "")
                if (maybeIfName.isNotBlank() &&
                    maybeIfName == maybeIfName.trim()
                ) {
                    if (ifName.isNotBlank()) {
                        networkInterfaces += NetifParameters(
                            name = ifName,
                            description = description,
                            driverName = driverName,
                            lines = lines.toList()
                        )
                    }
                    ifName = maybeIfName
                    description = ""
                    driverName = ""
                    lines.clear()
                } else {
                    lines += line.trim()
                }
            }
        }
    }
    if (ifName.isNotBlank()) {
        networkInterfaces += NetifParameters(
            name = ifName,
            description = description,
            driverName = driverName,
            lines = lines.toList()
        )
    }
    return networkInterfaces
}

suspend fun destroyNetif(jail: JailParameters, name: String) {
    pRead(listOf("ifconfig", "-j", jail.name, name, "destroy"))
}
