plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    api(project(":libjail"))

    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    api("net.vieiro:toml-java:13.5.1")
    api("org.cikit:forte-jvm:0.8.2")
    api("com.github.seancfoley:ipaddress:5.6.2")
    api("com.github.ajalt.clikt:clikt:5.1.0")

    testImplementation(kotlin("test"))
}
