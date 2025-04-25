package org.cikit.oci

interface OciCommand {
    val commandName: String
    val containerId: String
}
