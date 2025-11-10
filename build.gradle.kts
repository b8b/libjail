plugins {
    kotlin("jvm")
}

allprojects {
    group = "org.cikit"

    repositories {
        mavenCentral()
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.register<Exec>("build_freebsd") {
    val javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(25)
    }.get()
    environment(
        "java_cmd",
        buildString {
            append(javaLauncher.executablePath.asFile.absolutePath)
            append(" --enable-native-access=ALL-UNNAMED")
        }
    )
    executable = file("build_freebsd.sh").path
}

tasks.register<Exec>("build_generic") {
    val javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(25)
    }.get()
    environment(
        "java_cmd",
        buildString {
            append(javaLauncher.executablePath.asFile.absolutePath)
            append(" --enable-native-access=ALL-UNNAMED")
        }
    )
    executable = file("build_generic.sh").path
}
