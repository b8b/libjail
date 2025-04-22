plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `maven-publish`
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
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    api("net.java.dev.jna:jna:5.15.0")
}
