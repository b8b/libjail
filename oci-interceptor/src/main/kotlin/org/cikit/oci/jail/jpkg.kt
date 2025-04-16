package org.cikit.oci.jail

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.runBlocking
import org.cikit.libjail.*
import org.cikit.oci.OciLogger
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.concurrent.thread
import kotlin.io.path.*

class JPkgCommand : CliktCommand("jpkg") {

    override val invokeWithoutSubcommand = true
    override val treatUnknownOptionsAsArgs = true

    override fun help(context: Context): String {
        return context.theme.info("Helper for pkg -j")
    }

    private val log by option()
        .help("Log file")

    private val logFormat by option()
        .help("Log format")

    private val logLevel by option()
        .help("Log level")
        .default("warn")

    private val pkgBin by option(envvar = "JPKG_PKG_BIN")
        .help("path to pkg binary")
        .path(canBeDir = false)

    private val pkgCacheBase by option(envvar = "JPKG_CACHE_BASE")
        .help("base path to local cache")
        .path(canBeFile = false)
        .default(Path("/var/cache/jpkg"))

    private val root by option("-r", "--rootdir", "-c", "--chroot")
        .help("path to jail root directory")
        .path(canBeFile = false)
        .required()

    private val execStart by option()
        .help("commands to run in the jail environment before running pkg")

    private val execStop by option()
        .help("commands to run in the jail environment after running pkg")

    private val args by argument("PKG_ARG").multiple()

    private val osRelDate = sysctlByNameInt32("kern.osreldate")!!
    private val arch = sysctlByNameString("hw.machine_arch")!!

    private val pkgDbDir = Path("var/db/pkg")
    private val pkgCacheDir = Path("var/cache/pkg")
    private val pkgRepoConfDir = Path("usr/local/etc/pkg/repos")
    private val pkgConf = Path("usr/local/etc/pkg.conf")

    private fun cacheRoot(): Path {
        val base = pkgCacheBase
        val major = osRelDate / 100_000
        val minor = osRelDate / 1_000 - major * 100
        val snapshot = osRelDate % 1_000
        return if (snapshot == 0) {
            base / "FreeBSD-$major-$arch" / "r$minor"
        } else {
            base / "FreeBSD-$major-$arch" / "%07d".format(osRelDate)
        }
    }

    override fun run() {
        currentContext.findOrSetObject {
            OciLogger(
                logFile = log,
                logFormat = logFormat,
                logLevel = logLevel
            )
        }

        if (args.isEmpty() && execStart == null && execStop == null) {
            throw PrintHelpMessage(currentContext)
        }

        if (args.isNotEmpty() || execStop != null) {
            prepareRoot()
        }

        val cleanups = ArrayDeque<() -> Unit>()
        val shutdownHook = thread(start = false) {
            synchronized(cleanups) {
                while (cleanups.isNotEmpty()) {
                    cleanups.removeLast().invoke()
                }
            }
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        val tmpDir = createTempDirectory("jpkg-").toRealPath()
        cleanups += {
            if (tmpDir.exists()) {
                tmpDir.deleteIfExists()
            }
        }

        val lockFile = FileChannel.open(
            Path("$tmpDir.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        )
        cleanups += {
            try {
                lockFile.close()
            } catch (ex: Throwable) {
                //ignore
            }
            Path("$tmpDir.lock").deleteIfExists()
        }
        try {
            lockFile.lock().use {
                run0(tmpDir, cleanups)
            }
        } catch (ex: PrintMessage) {
            throw ex
        } catch (ex: Throwable) {
            throw PrintMessage(
                ex.toString(),
                statusCode = 1,
                printError = true
            )
        } finally {
            // eagerly run cleanups
            synchronized(cleanups) {
                while (cleanups.isNotEmpty()) {
                    cleanups.last().invoke()
                    cleanups.removeLast()
                }
            }
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        }
    }

    private fun prepareRoot() {
        val cacheRoot = cacheRoot()

        (root / "dev").createDirectories()
        (root / pkgDbDir / "repos").createDirectories()
        (root / pkgCacheDir).createDirectories()
        (root / pkgRepoConfDir).createDirectories()
        val rootResolvConf = (root / "etc").createDirectories() / "resolv.conf"
        if (!rootResolvConf.exists()) {
            rootResolvConf.writeText("")
        }
        val runtimePkg by lazy {
            runPkg("fetch", "--quiet", "-y", "FreeBSD-runtime")
            val pkgName = runPkgSearchVersion("FreeBSD-runtime")
            cacheRoot / pkgCacheDir / "$pkgName.pkg"
        }

        // pkg want's to read ABI from /usr/bin/uname (via elf parser)
        val rootUname = (root / "usr/bin").createDirectories() / "uname"
        if (!rootUname.exists()) {
            val rc = ProcessBuilder(
                "tar",
                "-C", root.pathString,
                "-xpf", runtimePkg.pathString,
                "-s", "|^/||", "/usr/bin/uname"
            ).inheritIO().start().waitFor()
            require(rc == 0) {
                "tar terminated with exit code $rc"
            }
        }
        val rootGroupDb = (root / "etc/group")
        if (!rootGroupDb.exists()) {
            val rc = ProcessBuilder(
                "tar",
                "-C", root.pathString,
                "-xpf", runtimePkg.pathString,
                "-s", "|^/||", "/etc/group"
            ).inheritIO().start().waitFor()
            require(rc == 0) {
                "tar terminated with exit code $rc"
            }
        }
        val rootPwdDb = (root / "etc/pwd.db")
        if (!rootPwdDb.exists()) {
            if (!(root / "etc/master.passwd").exists()) {
                val rc = ProcessBuilder(
                    "tar",
                    "-C", root.pathString,
                    "-xpf", runtimePkg.pathString,
                    "-s", "|^/||", "/etc/master.passwd"
                ).inheritIO().start().waitFor()
                require(rc == 0) {
                    "tar terminated with exit code $rc"
                }
            }
            ProcessBuilder(
                "pwd_mkdb", "-i", "-p",
                "-d", (root / "etc").pathString,
                (root / "etc/master.passwd").pathString
            ).inheritIO().start().waitFor().let { rc ->
                require(rc == 0) { "pwd_mkdb terminated with exit code $rc" }
            }
        }
        val rootServicesDb = root / "var/db/services.db"
        if (!rootServicesDb.exists()) {
            if (!(root / "etc/services").exists()) {
                val rc = ProcessBuilder(
                    "tar",
                    "-C", root.pathString,
                    "-xpf", runtimePkg.pathString,
                    "-s", "|^/||", "/etc/services"
                ).inheritIO().start().waitFor()
                require(rc == 0) {
                    "tar terminated with exit code $rc"
                }
            }
            ProcessBuilder(
                "services_mkdb", "-l", "-q",
                "-o", rootServicesDb.pathString,
                (root).pathString + "/"
            ).inheritIO().start().waitFor().let { rc ->
                require(rc == 0) {
                    "services_mkdb terminated with exit code $rc"
                }
            }
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun run0(tmpDir: Path, cleanups: ArrayDeque<() -> Unit>) {
        val cacheRoot = cacheRoot()

        nmount("nullfs", tmpDir, root)
        cleanups += {
            val mountInfo = readJailMountInfo() ?: runBlocking {
                readMountInfo()
            }
            val fsId = mountInfo
                .lastOrNull { mount -> mount.node == tmpDir.pathString }
                ?.parseFsId()
            if (fsId != null) {
                unmount(fsId, force = true)
            } else {
                unmount(tmpDir.pathString, force = true)
            }
        }

        val tmpPkgDbDir = tmpDir / pkgDbDir
        val tmpPkgCacheDir = tmpDir / pkgCacheDir
        val tmpPkgConfDir = tmpDir / pkgRepoConfDir
        val tmpResolvConf = tmpDir / "etc/resolv.conf"

        ProcessBuilder(
            "jail",
            "-c",
            "name=${tmpDir.name}",
            "path=$tmpDir",
            "mount.devfs",
            *if (isJailed()) {
                emptyArray()
            } else {
                arrayOf("devfs_ruleset=4")
            },
            "allow.chflags=1",
            "ip4=inherit",
            "ip6=inherit",
            "persist"
        ).inheritIO().start().waitFor().let { rc ->
            require(rc == 0) { "jail terminated with exit code $rc" }
        }

        cleanups += {
            val rc = ProcessBuilder("jail", "-r", tmpDir.name)
                .inheritIO()
                .start()
                .waitFor()
            require(rc == 0) {
                "jail terminated with exit code $rc"
            }
        }

        val jail = runBlocking {
            readJailParameters().singleOrNull { p ->
                p.name == tmpDir.name
            }
        } ?: error("jail ${tmpDir.name} vanished after create")

        cleanups += {
            runBlocking {
                cleanup(jail, attach = false)
            }
        }

        nmount("nullfs", tmpPkgCacheDir, cacheRoot / pkgCacheDir)
        nmount("tmpfs", tmpPkgDbDir / "repos")
        (cacheRoot / pkgDbDir / "repos").copyToRecursively(
            target = tmpPkgDbDir / "repos",
            followLinks = false,
            overwrite = true
        )
        nmount(
            "nullfs",
            tmpPkgConfDir,
            cacheRoot / pkgRepoConfDir,
            readOnly = true
        )
        nmount(
            "nullfs",
            tmpResolvConf,
            Path("/etc/resolv.conf"),
            readOnly = true
        )

        execStart?.let { cmd -> runCmdInJail(jail, cmd) }

        if (args.isNotEmpty()) {
            val rc = ProcessBuilder(
                pkgBin?.pathString ?: "pkg",
                "-j", jail.jid.toString(),
                *args.toTypedArray()
            )
                .inheritIO()
                .apply {
                    environment()["REPO_AUTOUPDATE"] = "false"
                }
                .start()
                .waitFor()
            if (rc != 0) {
                throw ProgramResult(rc)
            }
        }

        execStop?.let { cmd -> runCmdInJail(jail, cmd) }
    }

    private fun runPkg(vararg arg: String) {
        val cacheRoot = cacheRoot()
        val rc = ProcessBuilder(
            pkgBin?.pathString ?: "pkg",
            "-C", (cacheRoot / pkgConf).pathString,
            "-R", (cacheRoot / pkgRepoConfDir).pathString,
            *arg
        )
            .inheritIO()
            .apply {
                environment()["INSTALL_AS_USER"] = "yes"
                environment()["PKG_DBDIR"] =
                    (cacheRoot / pkgDbDir).pathString
                environment()["PKG_CACHEDIR"] =
                    (cacheRoot / pkgCacheDir).pathString
            }
            .start()
            .waitFor()
        require(rc == 0) {
            "pkg terminated with exit code $rc"
        }
    }

    private fun runPkgSearchVersion(name: String): String {
        val cacheRoot = cacheRoot()
        val p = ProcessBuilder(
            pkgBin?.pathString ?: "pkg",
            "-C", (cacheRoot / pkgConf).pathString,
            "-R", (cacheRoot / pkgRepoConfDir).pathString,
            "search", "--quiet", "--no-repo-update",
            "-r", "base", "-S", "name", "-L", "pkg-name",
            "--exact", name
        )
            .inheritIO()
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .apply {
                environment()["INSTALL_AS_USER"] = "yes"
                environment()["PKG_DBDIR"] =
                    (cacheRoot / pkgDbDir).pathString
                environment()["PKG_CACHEDIR"] =
                    (cacheRoot / pkgCacheDir).pathString
            }
            .start()
        val pkgName = p.inputStream.use { String(it.readBytes()) }
        val rc = p.waitFor()
        require(rc == 0) {
            "pkg terminated with exit code $rc"
        }
        return pkgName.trim()
    }

    private fun runCmdInJail(jail: JailParameters, cmd: String) {
        val rc = ProcessBuilder(
            "jexec", jail.jid.toString(), "/bin/sh", "-c", cmd
        )
            .inheritIO()
            .start()
            .waitFor()
        if (rc != 0) {
            throw PrintMessage(
                "command failed with exit code $rc",
                statusCode = rc,
                printError = true
            )
        }
    }
}
