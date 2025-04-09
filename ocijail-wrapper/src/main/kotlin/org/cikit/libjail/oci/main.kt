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
v=2.1.0.26
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
  "04126ed68e3dad3f077458c00cb4a4a06ac8b8df45666208af8497f822a0094a "*) ;;
  *) echo "error: failed to verify kotlin_script.sh" >&2
     rm -f "$kotlin_script_sh"; exit 1;;
  esac
fi
. "$kotlin_script_sh"; exit 2
*/

///PLUGIN=org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable

///DEP=org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.1

///DEP=org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.8.1
///DEP=org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.8.1

///DEP=com.github.ajalt.clikt:clikt-jvm:5.0.3
///DEP=com.github.ajalt.clikt:clikt-core-jvm:5.0.3
///DEP=com.github.ajalt.mordant:mordant-jvm:3.0.2
///DEP=com.github.ajalt.mordant:mordant-core-jvm:3.0.2
///DEP=com.github.ajalt.colormath:colormath-jvm:3.6.0
///RDEP=com.github.ajalt.mordant:mordant-jvm-jna-jvm:3.0.2
///DEP=net.java.dev.jna:jna:5.14.0
///RDEP=com.github.ajalt.mordant:mordant-jvm-ffm-jvm:3.0.2
///RDEP=com.github.ajalt.mordant:mordant-jvm-graal-ffi-jvm:3.0.2

///RDEP=org.slf4j:slf4j-simple:2.0.13
///DEP=org.slf4j:slf4j-api:2.0.13

///DEP=io.ktor:ktor-network-jvm:3.1.2
///DEP=io.ktor:ktor-utils-jvm:3.1.2
///DEP=io.ktor:ktor-io-jvm:3.1.2
///DEP=org.jetbrains.kotlinx:kotlinx-io-core-jvm:0.6.0
///DEP=org.jetbrains.kotlinx:kotlinx-io-bytestring-jvm:0.6.0

///INC=../../../../../../../../libjail/src/main/kotlin/org/cikit/libjail/jail.kt
///INC=../../../../../../../../libjail/src/main/kotlin/org/cikit/libjail/kill.kt
///INC=../../../../../../../../libjail/src/main/kotlin/org/cikit/libjail/mount.kt
///INC=../../../../../../../../libjail/src/main/kotlin/org/cikit/libjail/sysctl.kt
///INC=../../../../../../../../libjail/src/main/kotlin/org/cikit/libjail/util.kt
///INC=../../../../../../../../libjail/src/main/kotlin/org/cikit/libjail/vmm.kt
///INC=../../../../../../../../libjail/src/main/kotlin/org/cikit/libjail/vnet.kt

///INC=OciConfig.kt
///INC=OciLogger.kt

///INC=cleanup.kt
///INC=CleanupCommand.kt
///INC=CreateCommand.kt
///INC=DeleteCommand.kt
///INC=ExecCommand.kt
///INC=KillCommand.kt
///INC=ListCommand.kt
///INC=StartCommand.kt
///INC=StateCommand.kt

///MAIN=org.cikit.libjail.oci.MainKt

package org.cikit.libjail.oci

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.*
import kotlin.system.exitProcess

val json = Json {
    encodeDefaults = false
    explicitNulls = false
    ignoreUnknownKeys = true
}

private const val ociJailBinDefault = "/usr/local/bin/ocijail"
private const val ociJailStateBaseDefault = "/var/run/ocijail"
private const val ociJailStateFile = "state.json"
private const val ociJailStateLock = "state.lock"

const val OCI_ANNOTATION_VNET = "org.freebsd.jail.vnet"
const val OCI_ANNOTATION_ALLOW = "org.freebsd.jail.allow"
const val OCI_ANNOTATION_SECURE_LEVEL = "org.freebsd.jail.securelevel"

const val EXIT_UNHANDLED = 9

data class GlobalOptions(
    val ociJailBin: Path,
    val root: Path,
    val logFormat: String?,
    val logLevel: String?,
    val logFile: Path?,
    val localStateDir: Path,
    val devFsRulesetVnet: Long = 5,
    val devFsRulesetVmm: Long = 25,
    val ociLogger: OciLogger
) {
    fun readOciJailState(containerId: String): JsonObject? {
        val lockFile = root / containerId / ociJailStateLock
        if (lockFile.parent?.exists() != true) {
            return null
        }
        val stateFile = root / containerId / ociJailStateFile
        val stateJson = FileChannel.open(
            lockFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        ).use { fc ->
            fc.lock().use { lock ->
                require(lock.isValid) { "error locking $lockFile" }
                if (stateFile.isReadable()) {
                    stateFile.readText()
                } else {
                    return null
                }
            }
        }
        return if (stateJson.isBlank()) {
            null
        } else {
            json.decodeFromString(stateJson)
        }
    }

    fun readWrapperState(containerId: String): JsonObject? {
        val stateFile = localStateDir / "$containerId.json"
        if (stateFile.parent?.exists() != true) {
            return null
        }
        val stateJson = FileChannel.open(
            stateFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        ).use { fc ->
            fc.lock().use { lock ->
                require(lock.isValid) { "error locking $stateFile" }
                if (stateFile.isReadable()) {
                    stateFile.readText()
                } else {
                    return null
                }
            }
        }
        return if (stateJson.isBlank()) {
            null
        } else {
            json.decodeFromString(stateJson)
        }
    }

    fun writeWrapperState(containerId: String, state: JsonObject) {
        val stateJson = json.encodeToString(state)
        val stateFile = localStateDir / "$containerId.json"
        stateFile.parent?.let {
            if (!it.exists()) {
                it.createDirectories()
            }
        }
        FileChannel.open(
            stateFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        ).use { fc ->
            fc.lock().use { lock ->
                require(lock.isValid) { "error locking $stateFile" }
                fc.truncate(0L)
                fc.write(ByteBuffer.wrap(stateJson.encodeToByteArray()))
            }
        }
    }

    fun deleteWrapperState(containerId: String) {
        val stateFile = localStateDir / "$containerId.json"
        if (stateFile.exists()) {
            stateFile.deleteIfExists()
        }
    }
}

class GlobalCommandOptions : OptionGroup(name = "Global Options") {

    private val ociJailBin by option()
        .help("Path to ocijail")
        .path(mustExist = true, canBeDir = false)

    private val root by option()
        .help("Override default location for state database")
        .path()

    private val logFormat by option()
        .help("Log format")

    private val logLevel by option()
        .help("Log level")

    private val log by option()
        .help("Log file")

    private val localStateDir by option()
        .help("Override default location for wrapper state database")
        .path(canBeFile = false)
        .default(Path("/var/run/ocijail-wrapper"))

    private val devFsRulesetVnet by option()
        .help("Use the specified devfs ruleset for vnet jails")
        .long()

    private val devFsRulesetVmm by option()
        .help("Use the specified devfs ruleset for vmm jails")
        .long()

    fun getGlobalOptions(): GlobalOptions = GlobalOptions(
        ociJailBin = ociJailBin ?: Path(ociJailBinDefault),
        root = root ?: Path(ociJailStateBaseDefault),
        logFormat = logFormat,
        logLevel = logLevel,
        logFile = log?.let { Path(it) },
        localStateDir = localStateDir,
        devFsRulesetVnet = devFsRulesetVnet ?: 5L,
        devFsRulesetVmm = devFsRulesetVmm ?: 25L,
        ociLogger = OciLogger(
            logFile = log,
            logFormat = logFormat,
            logLevel = logLevel
        )
    )
}

private class OciRuntimeCommand : CliktCommand("ocijail-wrapper") {

    override val invokeWithoutSubcommand = true

    override fun help(context: Context): String {
        return context.theme.info("wrapper around ocijail oci runtime")
    }

    private val globalOptions by GlobalCommandOptions()

    private val version by option()
        .help("Print runtime version")
        .flag()

    init {
        subcommands(
            CreateCommand(),
            StartCommand(),
            CleanupCommand(),
            DeleteCommand(),
            ExecCommand(),
            KillCommand(),
            StateCommand(),
            ListCommand(),
        )
    }

    override fun run() {
        if (version) {
            exitProcess(EXIT_UNHANDLED)
        }
        val globalOptions = globalOptions.getGlobalOptions()
        currentContext.findOrSetObject {
            globalOptions
        }
    }
}

fun main(args: Array<String>) = OciRuntimeCommand().main(args)
