package org.cikit.oci.jail

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

    val interceptRcJail by option(envvar = "INTERCEPT_RC_JAIL")
        .help("Path to intercept-rcjail command")
        .required()

    private val pkgBin by option(envvar = "JPKG_PKG_BIN")
        .help("path to pkg binary")
        .path(canBeDir = false)

    private val pkgCacheBase by option(envvar = "JPKG_CACHE_BASE")
        .help("base path to local cache")
        .path(canBeFile = false)
        .default(Path("/var/cache/jpkg"))

    private val src by mutuallyExclusiveOptions(
        option("--from")
            .help("create a root directory using `buildah`"),
        option("-r", "--root", "-c", "--chroot")
            .path(canBeFile = false)
            .help("path to existing root directory")
    ).required()

    private val commit by option()
        .help("commit image when running with buildah")

    private val mount by option("--mount")
        .help("mount a directory into the jail")
        .multiple()

    private val execStart by option()
        .help("commands to run in the jail environment before running pkg")

    private val execStop by option()
        .help("commands to run in the jail environment after running pkg")

    private val args by argument("PKG_ARG").multiple()

    private val logger by lazy {
        OciLogger(
            logFile = log,
            logFormat = logFormat,
            logLevel = logLevel
        )
    }

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
        if (args.isEmpty() && execStart == null && execStop == null) {
            throw PrintHelpMessage(currentContext)
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
                val from = src as? String
                if (from != null) {
                    runBuildah(from, commit, tmpDir, cleanups)
                } else {
                    val root = src as? Path ?: throw PrintMessage(
                        "--root is not a Path",
                        1,
                        printError = true
                    )
                    runJail(root, tmpDir, cleanups)
                }
            }
        } catch (ex: PrintMessage) {
            logger.error(ex.toString(), ex)
            throw ProgramResult(ex.statusCode)
        } catch (ex: Throwable) {
            logger.error(ex.toString(), ex)
            throw ProgramResult(1)
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

    private fun runBuildah(
        from: String,
        commit: String?,
        tmpDir: Path,
        cleanups: ArrayDeque<() -> Unit>
    ) {
        val cid = buildahProcess(
            "from",
            "--name=${tmpDir.name}",
            from
        ).pReadLines { lines -> lines.last() }
        cleanups += {
            buildahProcess("rm", cid).pReadLines {}
        }
        val mp = buildahProcess("mount", cid).pReadLines { lines ->
            lines.last()
        }
        runJail(Path(mp), tmpDir, cleanups)
        if (commit != null) {
            buildahProcess("commit", cid, commit).exec()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun runJail(
        root: Path,
        tmpDir: Path,
        cleanups: ArrayDeque<() -> Unit>
    ) {
        val cacheRoot = cacheRoot()

        val realTmpDir = tmpDir.toRealPath()

        nmount("nullfs", realTmpDir, root)
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

        prepareRoot(realTmpDir)

        ProcessBuilder(
            "jail",
            "-c",
            "name=${tmpDir.name}",
            "path=${realTmpDir.pathString}",
            "mount.devfs",
            *if (isJailed()) {
                arrayOf("devfs_ruleset=0")
            } else {
                arrayOf("devfs_ruleset=4")
            },
            "allow.chflags=1",
            "ip4=inherit",
            "ip6=inherit",
            "persist"
        ).exec()
        cleanups += {
            val cleanupArgs = buildList {
                add(interceptRcJail)
                logger.logFile?.let {
                    add("--log=$it")
                }
                logger.logFormat?.let {
                    add("--log-format=$it")
                }
                logger.logLevel?.let {
                    add("--log-level=$it")
                }
                add("cleanup")
                add("-j")
                add(tmpDir.name)
            }
            logger.trace(TraceEvent.Exec(cleanupArgs))
            logger.close()
            try {
                ProcessBuilder(cleanupArgs).exec()
            } finally {
                logger.open()
            }
            ProcessBuilder("jail", "-r", tmpDir.name).exec()
        }
        val jail = runBlocking {
            readJailParameters().singleOrNull { p ->
                p.name == tmpDir.name
            }
        } ?: error("jail ${tmpDir.name} vanished after create")

        val tmpPkgDbDir = createMountPoint(realTmpDir, pkgDbDir)
        val tmpPkgCacheDir = createMountPoint(realTmpDir, pkgCacheDir)
        val tmpPkgConfDir = createMountPoint(realTmpDir, pkgRepoConfDir)
        val tmpEtcDir = createMountPoint(realTmpDir, Path("etc"))
        val tmpResolvConf = tmpEtcDir / "resolv.conf"

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

        for (input in mount) {
            val source = input.substringBefore(':')
            val destinationAndOptions = input.substringAfter(':', source)
            val destination = destinationAndOptions.substringBefore(':')
            val options = destinationAndOptions.substringAfter(':', "")
            val realDestination = createMountPoint(
                realTmpDir,
                Path(destination.trimStart('/'))
            )
            nmount(
                "nullfs",
                realDestination,
                Path(source),
                readOnly = options == "ro"
            )
        }

        execStart?.let { cmd ->
            runCmdInJail(jail, "/bin/sh", "-c", cmd)
        }

        var startIndex = 0
        while (startIndex < args.size) {
            val cmdArgs = buildList {
                while (startIndex < args.size) {
                    when (val arg = args[startIndex++]) {
                        "--" -> break
                        else -> add(arg)
                    }
                }
            }
            if (cmdArgs.first() == "sh") {
                runCmdInJail(
                    jail,
                    "/bin/sh",
                    "-c",
                    *cmdArgs.subList(1, cmdArgs.size).toTypedArray()
                )
            } else {
                val allArgs = listOf(
                    pkgBin?.pathString ?: "pkg",
                    "-j", jail.jid.toString()
                ) + cmdArgs
                val pb = ProcessBuilder(allArgs)
                pb.environment()["REPO_AUTOUPDATE"] = "false"
                pb.exec { rc ->
                    if (rc != 0) {
                        throw ProgramResult(rc)
                    }
                }
            }
        }

        execStop?.let { cmd ->
            runCmdInJail(jail, "/bin/sh", "-c", cmd)
        }
    }

    private fun prepareRoot(root: Path) {
        val cacheRoot = cacheRoot()

        createMountPoint(root, Path("dev"))
        val etcMp = createMountPoint(root, Path("etc"))
        val usrBinMp = createMountPoint(root, Path("usr/bin"))
        val varDbMp = createMountPoint(root, Path("var/db"))
        createMountPoint(root, pkgDbDir / "repos")
        createMountPoint(root, pkgCacheDir)
        createMountPoint(root, pkgRepoConfDir)

        val rootResolvConf = etcMp / "resolv.conf"
        if (!rootResolvConf.exists()) {
            rootResolvConf.writeText("")
        }

        val runtimePkg by lazy {
            runPkg("fetch", "--quiet", "-y", "FreeBSD-runtime")
            val pkgName = runPkgSearchVersion("FreeBSD-runtime")
            cacheRoot / pkgCacheDir / "$pkgName.pkg"
        }

        // pkg want's to read ABI from /usr/bin/uname (via elf parser)
        val rootUname = usrBinMp / "uname"
        if (!rootUname.exists()) {
            ProcessBuilder(
                "tar",
                "-C", usrBinMp.pathString,
                "-xpf", runtimePkg.pathString,
                "-s", "|^/usr/bin/||", "/usr/bin/uname"
            ).exec()
        }

        val rootGroupDb = etcMp / "group"
        if (!rootGroupDb.exists()) {
            ProcessBuilder(
                "tar",
                "-C", etcMp.pathString,
                "-xpf", runtimePkg.pathString,
                "-s", "|^/etc/||", "/etc/group"
            ).exec()
        }

        val rootPwdDb = etcMp / "pwd.db"
        if (!rootPwdDb.exists()) {
            if (!(etcMp / "master.passwd").exists()) {
                ProcessBuilder(
                    "tar",
                    "-C", etcMp.pathString,
                    "-xpf", runtimePkg.pathString,
                    "-s", "|^/etc/||", "/etc/master.passwd"
                ).exec()
            }
            ProcessBuilder(
                "pwd_mkdb", "-i", "-p",
                "-d", etcMp.pathString,
                (etcMp / "master.passwd").pathString
            ).exec()
        }

        val rootServicesDb = varDbMp / "services.db"
        if (!rootServicesDb.exists()) {
            if (!(etcMp / "services").exists()) {
                ProcessBuilder(
                    "tar",
                    "-C", etcMp.pathString,
                    "-xpf", runtimePkg.pathString,
                    "-s", "|^/etc/||", "/etc/services"
                ).exec()
            }
            ProcessBuilder(
                "services_mkdb", "-l", "-q",
                "-o", rootServicesDb.pathString,
                (etcMp / "services").pathString
            ).exec()
        }
    }

    private fun runPkg(vararg arg: String) {
        val cacheRoot = cacheRoot()
        val pb = ProcessBuilder(
            pkgBin?.pathString ?: "pkg",
            "-C", (cacheRoot / pkgConf).pathString,
            "-R", (cacheRoot / pkgRepoConfDir).pathString,
            *arg
        )
        val env = pb.environment()
        env["INSTALL_AS_USER"] = "yes"
        env["PKG_DBDIR"] = (cacheRoot / pkgDbDir).pathString
        env["PKG_CACHEDIR"] = (cacheRoot / pkgCacheDir).pathString
        pb.exec()
    }

    private fun runPkgSearchVersion(
        name: String,
        repository: String = "base"
    ): String {
        val cacheRoot = cacheRoot()
        val pb = ProcessBuilder(
            pkgBin?.pathString ?: "pkg",
            "-C", (cacheRoot / pkgConf).pathString,
            "-R", (cacheRoot / pkgRepoConfDir).pathString,
            "search", "--quiet", "--no-repo-update",
            "-r", repository, "-S", "name", "-L", "pkg-name",
            "--exact", name
        )
        val env = pb.environment()
        env["INSTALL_AS_USER"] = "yes"
        env["PKG_DBDIR"] = (cacheRoot / pkgDbDir).pathString
        env["PKG_CACHEDIR"] = (cacheRoot / pkgCacheDir).pathString
        val pkgName = pb.pReadLines { lines -> lines.last() }
        return pkgName.trim()
    }

    private fun runCmdInJail(jail: JailParameters, vararg cmd: String) {
        ProcessBuilder("jexec", "${jail.jid}", *cmd).exec()
    }

    private fun createMountPoint(root: Path, relative: Path): Path {
        val realRoot = root.toRealPath()
        require(realRoot.isDirectory())
        val relCount = relative.nameCount
        require(!relative.isAbsolute)
        require(relCount > 0)

        var pwd = realRoot

        val chkLoop = mutableSetOf<Path>()
        val todo = ArrayDeque<Path>(
            (0 until relCount).map(relative::getName)
        )

        while (todo.isNotEmpty()) {
            val next = pwd / todo.removeFirst()
            if (!next.startsWith(realRoot)) {
                pwd = realRoot
                continue
            }
            if (!next.exists()) {
                next.createDirectories()
                pwd = next
                continue
            }
            if (next.isSymbolicLink()) {
                require(next !in chkLoop)
                chkLoop.add(next)
                var target = next.readSymbolicLink()
                if (target.isAbsolute) {
                    pwd = realRoot
                    target = target.relativeTo(target.root)
                }
                (0 until target.nameCount)
                    .reversed()
                    .map(target::getName)
                    .forEach { todo.addFirst(it) }
            } else {
                require(next.isDirectory())
                pwd = next
            }
        }

        return pwd
    }

    private val buildahHome: Path by lazy {
        val v = try {
            runPkgSearchVersion("buildah", repository = "FreeBSD-latest")
        } catch (ex: Exception) {
            runPkgSearchVersion("buildah", repository = "FreeBSD-release")
        }
        val cacheRoot = cacheRoot()
        val binPath = cacheRoot / "usr/local/$v/bin/buildah"
        val pkgs = if (binPath.exists()) {
            emptyList()
        } else {
            listOf("gpgme", "libgpg-error", "libassuan", "buildah")
        }
        if (pkgs.isNotEmpty()) {
            runPkg("fetch", "-y", *pkgs.toTypedArray())
        }
        for (pkg in pkgs) {
            val name = try {
                runPkgSearchVersion(pkg, repository = "FreeBSD-latest")
            } catch (ex: Exception) {
                runPkgSearchVersion(pkg, repository = "FreeBSD-release")
            }
            val include = if (pkg == "buildah") {
                "/usr/local/bin"
            } else {
                "/usr/local/lib"
            }
            ProcessBuilder(
                "tar",
                "-C", cacheRoot.pathString,
                "-xf", (cacheRoot / pkgCacheDir / "$name.pkg").pathString,
                "-s", "|^/usr/local|usr/local/$v|",
                include
            ).exec()
        }
        cacheRoot / "usr/local/$v"
    }

    private fun buildahProcess(vararg arg: String): ProcessBuilder {
        val p = ProcessBuilder((buildahHome / "bin/buildah").pathString, *arg)
        p.environment()["LD_LIBRARY_PATH"] = (buildahHome / "lib").pathString
        return p
    }

    private fun <T> ProcessBuilder.pReadLines(
        errorHandler: (Int, String) -> Unit = ::defaultErrorHandler,
        block: (Sequence<String>) -> T,
    ): T {
        redirectOutput(ProcessBuilder.Redirect.PIPE)
        redirectError(ProcessBuilder.Redirect.PIPE)
        logger.trace(TraceEvent.Exec(command()))
        return runBlocking(Dispatchers.IO) {
            val p = start()
            val errors = async {
                p.errorStream.use { String(it.readBytes()) }
            }
            val result = p.inputStream.use { `in` ->
                val result = block(`in`.bufferedReader().lineSequence())
                while (true) {
                    val len = `in`.skip(Long.MAX_VALUE)
                    if (len <= 0) {
                        break
                    }
                }
                result
            }
            val rc = p.waitFor()
            errorHandler(rc, errors.await())
            result
        }
    }

    private fun ProcessBuilder.exec(
        errorHandler: (Int) -> Unit = ::defaultErrorHandler,
    ) {
        inheritIO()
        logger.trace(TraceEvent.Exec(command()))
        val rc = start().waitFor()
        errorHandler(rc)
    }
}

private fun ProcessBuilder.defaultErrorHandler(rc: Int) {
    if (rc != 0) {
        val prog = command().firstOrNull()
        throw PrintMessage(
            "$prog terminated with exit code $rc",
            rc,
            printError = true
        )
    }
}

private fun ProcessBuilder.defaultErrorHandler(rc: Int, errors: String) {
    if (rc != 0) {
        val prog = command().firstOrNull()
        throw PrintMessage(
            "$prog terminated with exit code $rc: $errors",
            rc,
            printError = true
        )
    }
}
