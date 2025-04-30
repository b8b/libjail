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

    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    api("net.vieiro:toml-java:13.4.2")
    api("org.cikit:forte-jvm:0.4.2")
    api("com.github.ajalt.clikt:clikt:5.0.3")
}
