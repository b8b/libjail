pluginManagement {
    plugins {
        val kotlinVersion = "2.2.10"
        kotlin("jvm") version kotlinVersion
        kotlin("plugin.serialization") version kotlinVersion
    }
}

include("build-scripts")
include("libjail")
include("libjail-ffm")
include("oci-interceptor")
