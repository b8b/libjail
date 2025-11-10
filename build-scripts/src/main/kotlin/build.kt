#!/bin/sh

/*/ __kotlin_script_installer__ 2>&-
# vim: syntax=kotlin
#    _         _   _ _                       _       _
#   | |       | | | (_)                     (_)     | |
#   | | _____ | |_| |_ _ __    ___  ___ _ __ _ _ __ | |_
#   | |/ / _ \| __| | | '_ \  / __|/ __| '__| | '_ \| __|
#   |   < (_) | |_| | | | | | \__ \ (__| |  | | |_) | |_
#   |_|\_\___/ \__|_|_|_| |_| |___/\___|_|  |_| .__/ \__|
#                         ______              | |
#                        |______|             |_|
v=2.2.21.32
p=org/cikit/kotlin_script/"$v"/kotlin_script-"$v".sh
url="${M2_CENTRAL_REPO:=https://repo1.maven.org/maven2}"/"$p"
kotlin_script_sh="${M2_LOCAL_REPO:-"$HOME"/.m2/repository}"/"$p"
if ! [ -r "$kotlin_script_sh" ]; then
  kotlin_script_sh="$(mktemp)" || exit 1
  fetch_cmd="$(command -v curl) -kfLSso" || \
    fetch_cmd="$(command -v fetch) --no-verify-peer -aAqo" || \
    fetch_cmd="wget --no-check-certificate -qO"
  if ! $fetch_cmd "$kotlin_script_sh" "$url"; then
    echo "failed to fetch kotlin_script.sh from $url" >&2
    rm -f "$kotlin_script_sh"; exit 1
  fi
  dgst_cmd="$(command -v openssl) dgst -sha256 -r" || dgst_cmd=sha256sum
  case "$($dgst_cmd < "$kotlin_script_sh")" in
  "ad53c905302b3247059729f3ff4762727a0c52b903d66241acc277c60d427e94 "*) ;;
  *) echo "error: failed to verify kotlin_script.sh" >&2
     rm -f "$kotlin_script_sh"; exit 1;;
  esac
fi
. "$kotlin_script_sh"; exit 2
*/

///DEP=org.cikit:kotlin_script:2.2.21.32

///DEP=com.github.ajalt.mordant:mordant-jvm:3.0.2
///DEP=com.github.ajalt.mordant:mordant-core-jvm:3.0.2
///DEP=com.github.ajalt.colormath:colormath-jvm:3.6.0
///RDEP=com.github.ajalt.mordant:mordant-jvm-jna-jvm:3.0.2
///DEP=net.java.dev.jna:jna:5.15.0
///RDEP=com.github.ajalt.mordant:mordant-jvm-ffm-jvm:3.0.2
///RDEP=com.github.ajalt.mordant:mordant-jvm-graal-ffi-jvm:3.0.2

///DEP=com.github.ajalt.clikt:clikt-jvm:5.0.3
///DEP=com.github.ajalt.clikt:clikt-core-jvm:5.0.3

import com.sun.jna.Native
import kotlin_script.Dependency
import kotlin_script.KotlinScript
import kotlin_script.Script
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.system.exitProcess

private val baseDir = Path(".")
private val targetDir = baseDir / "target"
private val version = System.getenv("LIBJAIL_VERSION") ?: "0.0.4-dev"
private val os = System.getProperty("os.name").lowercase().let { osName ->
    if (osName == "freebsd") {
        osName + System.getProperty("os.version")
            .replace("""\D.*$""".toRegex(), "")
    } else {
        osName
    }
}
private val arch = System.getProperty("os.arch")

fun main() {
    require(Native.SIZE_T_SIZE == 8) {
        "Ffm bindings are hard coded to sizeof(size_t) == 8 currently!"
    }
    val javaVersion = System.getProperty("java.vm.specification.version")
    require(javaVersion.toInt() >= 24) {
        "Build requires a jdk >= 24. Detected '$javaVersion'."
    }
    val javaC = Path(System.getProperty("java.home")) / "bin" / "javac"
    require(javaC.isExecutable()) {
        "Build requires a jdk >= 24. $javaC is not executable."
    }

    val libJail = compileLibJail()
    val libJailVersion = libJail.nameWithoutExtension
        .removePrefix("kotlin_script_cache-")
        .replaceFirst("-", ":")
    (targetDir / "classes").createDirectories()
    libJail.copyTo(
        targetDir / "classes/libjail-$version.jar",
        overwrite = true
    )

    val source = buildString {
        appendLine("///DEP=org.cikit:kotlin_script_cache:$libJailVersion")
        (baseDir / "bin" / "intercept-oci-runtime.sh").useLines { lines ->
            for (line in lines) {
                if (line.startsWith("///DEP=") ||
                    line.startsWith("///PLUGIN="))
                {
                    appendLine(line)
                } else if (line.startsWith("///INC=../oci-interceptor/")) {
                    appendLine(line.replace("///INC=../", "///INC="))
                } else if (line.startsWith("///MAIN=")) {
                    appendLine(line)
                }
            }
        }
    }

    val data = source.encodeToByteArray()
    val chkSum = MessageDigest.getInstance("SHA-256")
        .digest(data)
        .joinToString("", prefix = "sha256=") { "%02x".format(it) }
    val script = Script(Path("oci-interceptor.kt"), chkSum, data)

    val compiler = KotlinScript(progress = true, trace = true)
    val metaData = compiler.compile(script)
    val mainJar = compiler.jarCachePath(metaData)
    mainJar.copyTo(
        targetDir / "classes/oci-interceptor-$version.jar",
        overwrite = true
    )

    val localRepo = (0 until mainJar.nameCount)
        .last { mainJar.getName(it) == Path("org") }
        .let {
            if (mainJar.isAbsolute) {
                mainJar.root / mainJar.subpath(0, it)
            } else {
                mainJar.root / mainJar.subpath(0, it)
            }
        }

    val mordantNativeMod =
        "mordant-jvm-ffm-jvm" to "com.github.ajalt.mordant.ffm"

    val modsToCombine = mapOf(
        "clikt-combined" to listOf("clikt-core-jvm", "clikt-jvm"),
        "toml-java-combined" to listOf("antlr4-runtime", "toml-java")
    )

    val modsToPatch = mapOf(
        "jna" to "com.sun.jna",
        "libjail" to "org.cikit.libjail",
        "forte-jvm" to "org.cikit.forte",
        "colormath-jvm" to "com.github.ajalt.colormath",
        "mordant-core-jvm" to "com.github.ajalt.mordant.core",
        mordantNativeMod.first to mordantNativeMod.second,
        "clikt-combined" to "com.github.ajalt.clikt",
        "toml-java-combined" to "net.vieiro.toml",
        "oci-interceptor" to "org.cikit.oci.interceptor"
    )

    val modsDistributed = mapOf(
        "kotlin-stdlib" to "kotlin.stdlib",
        "kotlinx-coroutines-core-jvm" to "kotlinx.coroutines.core",
        "kotlinx-serialization-core-jvm" to "kotlinx.serialization.core",
        "kotlinx-serialization-json-jvm" to "kotlinx.serialization.json",
        "kotlinx-datetime-jvm" to "kotlinx.datetime",
        "kotlinx-io-bytestring-jvm" to "kotlinx.io.bytestring",
    )

    val modulePath = mutableListOf<Path>()
    val depByArtifactId = mutableMapOf<String, Dependency>()

    listOf(
        Dependency("org.cikit", "libjail", version),
        Dependency("org.cikit", "oci-interceptor", version)
    ).forEach { d -> depByArtifactId[d.artifactId] = d }

    val allDeps = metaData.dep + metaData.dep.mapNotNull { d ->
        if (d.artifactId == "mordant-jvm") {
            d.copy(artifactId = mordantNativeMod.first)
        } else {
            null
        }
    }

    for (d in allDeps) {
        depByArtifactId[d.artifactId] = d
        val tgt = targetDir / "classes/${d.artifactId}-${d.version}.jar"
        (localRepo / d.subPath).copyTo(tgt, overwrite = true)
        if (d.artifactId in modsDistributed.keys) {
            modulePath.add(tgt)
        }
    }

    for ((artifactId, artifacts) in modsToCombine) {
        val v = depByArtifactId.getValue(artifacts.last()).version
        val tgt = targetDir / "classes/$artifactId-$v.jar"
        val sources = artifacts
            .map { depByArtifactId.getValue(it) }
            .map { targetDir / "classes/${it.artifactId}-${it.version}.jar" }
        combineJars(tgt, *sources.toTypedArray())
        depByArtifactId[artifactId] = depByArtifactId
            .getValue(artifacts.last())
            .copy(artifactId = artifactId)
    }

    val buildScript = buildString {
        for ((artifactId, modId) in modsToPatch) {
            val d = depByArtifactId.getValue(artifactId)
            val jar = targetDir / "classes/${d.artifactId}-${d.version}.jar"
            val destination = targetDir / "classes/${d.artifactId}-${d.version}"
            if (destination.exists()) {
                destination.createDirectories()
            }
            appendLine(
                buildString {
                    append("$javaC -p ${modulePath.joinToString(":")} ")
                    append("--patch-module $modId=$jar ")
                    append("-d ${destination.pathString} ")
                    append("build-scripts/src/mod-info/$artifactId/module-info.java")
                }
            )
            appendLine(
                buildString {
                    append("${javaC.parent}/jar -uf $jar ")
                    append("-C ${destination.pathString} module-info.class")
                }
            )
            modulePath.add(jar)
        }

        appendLine(
            buildString {
                append("${javaC.parent}/jlink ")
                append("-p ${modulePath.joinToString(":")} ")
                append("--add-modules org.cikit.oci.interceptor,${mordantNativeMod.second} ")
                append("--no-header-files ")
                append("--no-man-pages ")
                for ((launcher, mainClass) in mapOf(
                    "intercept-oci-runtime" to "org.cikit.oci.GenericInterceptor",
                    "intercept-ocijail" to "org.cikit.oci.jail.OciJailInterceptor",
                    "intercept-rcjail" to "org.cikit.oci.jail.RcJailInterceptor",
                    "pkgbuild" to "org.cikit.oci.jail.PkgbuildCommand",
                )) {
                    append("--launcher $launcher=org.cikit.oci.interceptor/$mainClass ")
                }
                append("--output \${WORKDIR:-target}/libjail")
            }
        )
    }

    val rc = ProcessBuilder("/bin/sh", "-xc", buildScript)
        .inheritIO()
        .start()
        .waitFor()

    Path("target/libjail/VERSION").writeText(
        "$version-$os-$arch\n"
    )

    exitProcess(rc)
}

private fun compileLibJail(): Path {
    val source = buildString {
        (baseDir / "bin" / "intercept-oci-runtime.sh").useLines { lines ->
            for (line in lines) {
                if (line.startsWith("///DEP=") ||
                    line.startsWith("///PLUGIN="))
                {
                    appendLine(line)
                } else if (line.startsWith("///INC=../libjail/")) {
                    if (line.contains("util_jna.kt")) {
                        appendLine(
                            line.replace("../libjail/", "libjail-ffm/")
                                .replace("util_jna", "util_ffm")
                        )
                    } else {
                        appendLine(line.replace("///INC=../", "///INC="))
                    }
                }
            }
        }
    }

    val data = source.encodeToByteArray()
    val chkSum = MessageDigest.getInstance("SHA-256")
        .digest(data)
        .joinToString("", prefix = "sha256=") { "%02x".format(it) }
    val script = Script(Path("libjail.kt"), chkSum, data)

    val compiler = KotlinScript(progress = true, trace = true)
    val metaData = compiler.compile(script)
    return compiler.jarCachePath(metaData)
}

private fun combineJars(output: Path, vararg input: Path) {
    val names = mutableSetOf<String>()
    output.outputStream().use { out ->
        ZipOutputStream(out).use { zOut ->
            for (i in input) {
                i.inputStream().use { `in` ->
                    ZipInputStream(`in`).use { zIn ->
                        for (entry in generateSequence { zIn.nextEntry }) {
                            if (entry.name !in names) {
                                zOut.putNextEntry(entry)
                                zIn.copyTo(zOut)
                                zOut.closeEntry()
                                names.add(entry.name)
                            }
                        }
                    }
                }
            }
        }
    }
}
