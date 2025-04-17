package org.cikit.oci.jail

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
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
                    run0(root, tmpDir, cleanups)
                }
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
        )
            .inheritIO()
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .start()
            .let { p ->
                val cid = p.inputStream.use {
                    String(it.readBytes())
                }
                val rc = p.waitFor()
                require(rc == 0) {
                    "buildah terminated with exit code $rc"
                }
                cid.trim()
            }
        cleanups += {
            val p = buildahProcess("rm", cid)
                .inheritIO()
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .start()
            p.inputStream.use { it.readBytes() }
            val rc = p.waitFor()
            require(rc == 0) {
                "buildah terminated with exit code $rc"
            }
        }
        val mp = buildahProcess("mount", cid)
            .inheritIO()
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .start()
            .let { p ->
                val mp = p.inputStream.use {
                    String(it.readBytes())
                }
                val rc = p.waitFor()
                require(rc == 0) {
                    "buildah terminated with exit code $rc"
                }
                mp.trim()
            }
        run0(Path(mp), tmpDir, cleanups)
        if (commit != null) {
            buildahProcess("commit", cid, commit)
                .inheritIO()
                .start()
                .let { p ->
                    val rc = p.waitFor()
                    require(rc == 0) {
                        "buildah terminated with exit code $rc"
                    }
                }
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun run0(
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
            val rc = ProcessBuilder(
                "tar",
                "-C", usrBinMp.pathString,
                "-xpf", runtimePkg.pathString,
                "-s", "|^/usr/bin/||", "/usr/bin/uname"
            ).inheritIO().start().waitFor()
            require(rc == 0) {
                "tar terminated with exit code $rc"
            }
        }

        val rootGroupDb = etcMp / "group"
        if (!rootGroupDb.exists()) {
            val rc = ProcessBuilder(
                "tar",
                "-C", etcMp.pathString,
                "-xpf", runtimePkg.pathString,
                "-s", "|^/etc/||", "/etc/group"
            ).inheritIO().start().waitFor()
            require(rc == 0) {
                "tar terminated with exit code $rc"
            }
        }

        val rootPwdDb = etcMp / "pwd.db"
        if (!rootPwdDb.exists()) {
            if (!(etcMp / "master.passwd").exists()) {
                val rc = ProcessBuilder(
                    "tar",
                    "-C", etcMp.pathString,
                    "-xpf", runtimePkg.pathString,
                    "-s", "|^/etc/||", "/etc/master.passwd"
                ).inheritIO().start().waitFor()
                require(rc == 0) {
                    "tar terminated with exit code $rc"
                }
            }
            ProcessBuilder(
                "pwd_mkdb", "-i", "-p",
                "-d", etcMp.pathString,
                (etcMp / "master.passwd").pathString
            ).inheritIO().start().waitFor().let { rc ->
                require(rc == 0) { "pwd_mkdb terminated with exit code $rc" }
            }
        }

        val rootServicesDb = varDbMp / "services.db"
        if (!rootServicesDb.exists()) {
            if (!(etcMp / "services").exists()) {
                val rc = ProcessBuilder(
                    "tar",
                    "-C", etcMp.pathString,
                    "-xpf", runtimePkg.pathString,
                    "-s", "|^/etc/||", "/etc/services"
                ).inheritIO().start().waitFor()
                require(rc == 0) {
                    "tar terminated with exit code $rc"
                }
            }
            ProcessBuilder(
                "services_mkdb", "-l", "-q",
                "-o", rootServicesDb.pathString,
                (etcMp / "services").pathString
            ).inheritIO().start().waitFor().let { rc ->
                require(rc == 0) {
                    "services_mkdb terminated with exit code $rc"
                }
            }
        }
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

    private fun runPkgSearchVersion(
        name: String,
        repository: String = "base"
    ): String {
        val cacheRoot = cacheRoot()
        val p = ProcessBuilder(
            pkgBin?.pathString ?: "pkg",
            "-C", (cacheRoot / pkgConf).pathString,
            "-R", (cacheRoot / pkgRepoConfDir).pathString,
            "search", "--quiet", "--no-repo-update",
            "-r", repository, "-S", "name", "-L", "pkg-name",
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
        if (!binPath.exists()) {
            val libs = listOf("gpgme", "gpg-error", "assuan")
            runPkg(
                "fetch", "-y",
                "buildah",
                *libs.map { "lib$it" }.toTypedArray()
            )
            ProcessBuilder(
                "tar",
                "-C", cacheRoot.pathString,
                "-xf", (cacheRoot / pkgCacheDir / "$v.pkg").pathString,
                "-s", "|^/usr/local/bin/buildah|usr/local/$v/bin/buildah|",
                "/usr/local/bin/buildah"
            ).inheritIO().start().waitFor().let { rc ->
                require(rc == 0) {
                    "tar terminated with exit code $rc"
                }
            }
            for (lib in libs) {
                val name = try {
                    runPkgSearchVersion("lib$lib", repository = "FreeBSD-latest")
                } catch (ex: Exception) {
                    runPkgSearchVersion("lib$lib", repository = "FreeBSD-release")
                }
                ProcessBuilder(
                    "tar",
                    "-C", cacheRoot.pathString,
                    "-xf", (cacheRoot / pkgCacheDir / "$name.pkg").pathString,
                    "-s", "|^/usr/local/lib|usr/local/$v/lib|",
                    "/usr/local/lib"
                ).inheritIO().start().waitFor().let { rc ->
                    require(rc == 0) {
                        "tar terminated with exit code $rc"
                    }
                }
            }
        }
        cacheRoot / "usr/local/$v"
    }

    private fun buildahProcess(vararg arg: String): ProcessBuilder {
        val p = ProcessBuilder((buildahHome / "bin/buildah").pathString, *arg)
        p.environment()["LD_LIBRARY_PATH"] = (buildahHome / "lib").pathString
        return p
    }
}
