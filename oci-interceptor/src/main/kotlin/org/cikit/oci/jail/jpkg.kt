package org.cikit.oci.jail

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.provideDelegate
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

private class CommandOptions : OptionGroup(
    name = "Command Options",
    help =
    """At the root level and for pkg sub commands, any options not handled
    by jpkg are passed to pkg. For more information consult the pkg manual 
    pages or use the help sub command.
    
    The following options are available on every sub command.
    """
) {
    val mount by option("--mount", metavar = "source:destination:options")
        .help(
            """Mount a directory into the jail before executing the 
            command. After command execution, the directory is unmounted and 
            removed. All empty parent directories are also removed.
            When specified at the root level, the directory 
            remains mounted for all subsequent command executions.

            This option can be specified multiple times.
            """
        )
        .multiple()

    val commit by option()
        .help(
            """Commit a named image on successful completion of the 
            command. When specified at the root level, commit on successful 
            completion of all commands.

            This option can only be used together with the '--from' option.
            """
        )
}

class JPkgCommand : CliktCommand("jpkg") {

    override val invokeWithoutSubcommand = true
    override val treatUnknownOptionsAsArgs = true
    override val printHelpOnEmptyArgs = true

    init {
        subcommands(
            RunCommand(),
            PkgCommand(),
            *pkgCommands.map { PkgCommand(it) }.toTypedArray()
        )
        context {
            allowInterspersedArgs = false
            helpFormatter = {
                MordantHelpFormatter(it, showDefaultValues = true)
            }
        }
    }

    override fun help(context: Context): String {
        return context.theme.info(
            """Create a temporary jail and perform minimal preparations 
            to allow subsequent pkg invocation(s) using the '--jail' option.

            Without any jail creation option ('--root', '--from'), run pkg
            directly passing all options unaltered. 
            """
        )
    }

    private val log by option()
        .help("Log file")

    private val logFormat by option()
        .help("Log format")

    private val logLevel by option()
        .help("Log level")
        .default("warn")

    val interceptRcJail by option(
        envvar = "INTERCEPT_RC_JAIL",
        hidden = System.getenv("INTERCEPT_RC_JAIL") != null
    )
        .help("Path to intercept-rcjail command.")
        .required()

    private val pkgBin by option(envvar = "JPKG_PKG_BIN")
        .help("Path to pkg binary.")
        .path(canBeDir = false)

    private val pkgCacheBase by option(envvar = "JPKG_CACHE_BASE")
        .help("Base path to local cache.")
        .path(canBeFile = false)
        .default(
            Path("/var/cache/jpkg"),
            System.getenv("JPKG_CACHE_BASE") ?: "/var/cache/jpkg"
        )

    private val src by mutuallyExclusiveOptions(
        option("--from").help(
            """Create and mount a container from the specified image using 
            'buildah'. Then create a jail using the container mount path."""
        ),
        option("--path").path(canBeFile = false).help(
            "Create a jail using an existing root directory."
        )
    )

    private val commandOptions by CommandOptions()

    private val pipeline by option("-P", "--pipeline").flag().help(
        "Run multiple sub commands separated by the pipeline separator."
    )

    private val pipelineSeparator by option().default("--then").help(
        "Argument to be used as separator when running with '--pipeline'."
    )

    private val args by argument().multiple()

    private val logger by lazy {
        OciLogger(
            logFile = log,
            logFormat = logFormat,
            logLevel = logLevel
        )
    }

    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            throw PrintHelpMessage(currentContext)
        }
        val pipelineBuilder = JPkgPipelineBuilder(
            separator = pipelineSeparator.takeIf { pipeline },
            logger = logger,
            pkgBin = pkgBin,
            pkgCacheBase = pkgCacheBase,
            interceptRcJail = interceptRcJail,
            pkgOptions = args,
            mount = commandOptions.mount,
            root = src as? Path,
            from = src as? String,
            commit = commandOptions.commit
        )
        currentContext.findOrSetObject {
            pipelineBuilder
        }
        currentContext.callOnClose {
            val cleanupHook = thread(start = false) {
                pipelineBuilder.cleanup()
            }
            Runtime.getRuntime().addShutdownHook(cleanupHook)
            try {
                pipelineBuilder.run()
            } catch (ex: Throwable) {
                throw PrintMessage(
                    ex.message ?: ex.toString(),
                    statusCode = 1,
                    printError = true
                )
            } finally {
                pipelineBuilder.cleanup()
                Runtime.getRuntime().removeShutdownHook(cleanupHook)
            }
        }
    }
}

class NextCommand(
    private val pipelineBuilder: JPkgPipelineBuilder
) : CliktCommand("next") {

    override val invokeWithoutSubcommand: Boolean = true

    init {
        subcommands(
            RunCommand(),
            PkgCommand(),
            *pkgCommands.map { PkgCommand(it) }.toTypedArray()
        )
        context {
            allowInterspersedArgs = false
            helpFormatter = {
                MordantHelpFormatter(it, showDefaultValues = true)
            }
        }
    }

    private val then by option().flag()

    override fun run() {
        currentContext.findOrSetObject { pipelineBuilder }
        if (!then) {
            throw PrintHelpMessage(currentContext, error = true, 1)
        }
    }
}

private class RunCommand : CliktCommand("run") {

    init {
        context {
            allowInterspersedArgs = false
        }
    }

    override val treatUnknownOptionsAsArgs = true

    override fun help(context: Context): String {
        return context.theme.info(
            "Run a command in the jail environment."
        )
    }

    private val pipelineBuilder by requireObject<JPkgPipelineBuilder>()

    private val options by CommandOptions()

    private val noExpand by option().flag().help(
        "Do not expand template expressions (currently not implemented)" +
                " within the command arguments."
    )

    private val clean by option("-l", "--clean").flag().help(
        """Execute in a clean environment. See `man jexec` for more 
        information.
        """
    )

    private val shell by option("--shell", "-c").flag().help(
        "Execute `/bin/sh` passing the first argument as script. " +
                "Subsequent arguments are passed as script arguments."
    )

    private val args by argument("ARG").multiple().help(
        "Command and arguments to execute."
    )

    override fun run() {
        val runArgs = mutableListOf<String>()
        val nextArgs = mutableListOf<String>()
        for (arg in args) {
            if (nextArgs.isNotEmpty() || arg == pipelineBuilder.separator) {
                nextArgs.add(arg)
            } else {
                runArgs.add(arg)
            }
        }
        pipelineBuilder.add(
            JPkgPipelineBuilder.Step.Run(
                mount = options.mount,
                commit = options.commit,
                noExpand = noExpand,
                clean = clean,
                shell = shell,
                args = runArgs.toList()
            )
        )
        if (nextArgs.isNotEmpty()) {
            NextCommand(pipelineBuilder).main(nextArgs.toList())
        }
    }
}

private val pkgCommands = setOf(
    "add",
    "alias",
    "annotate",
    "audit",
    "autoremove",
    "check",
    "clean",
    "config",
    "create",
    "delete",
    "fetch",
    "help",
    "info",
    "install",
    "key",
    "lock",
    "plugins",
    "query",
    "register",
    "remove",
    "repo",
    "repositories",
    "rquery",
    "search",
    "set",
    "ssh",
    "shell",
    "shlib",
    "stats",
    "triggers",
    "unlock",
    "update",
    "updating",
    "upgrade",
    "version",
    "which",
)

private class PkgCommand(name: String = "pkg") : CliktCommand(name) {

    init {
        context {
            allowInterspersedArgs = false
        }
    }

    override val treatUnknownOptionsAsArgs = true
    override val hiddenFromHelp: Boolean = commandName != "pkg"

    override fun help(context: Context): String {
        return context.theme.info(
            if (commandName == "pkg") {
                "Run a pkg command with global options. Any pkg sub command " +
                        "can also be used directly $pkgCommands."
            } else {
                "For more information see 'pkg help $commandName'."
            }
        )
    }

    private val pipelineBuilder by requireObject<JPkgPipelineBuilder>()

    private val options by CommandOptions()

    private val args by argument("PKG_ARG").multiple().help(
        "Options and arguments passed to pkg"
    )

    override fun run() {
        val pkgArgs = mutableListOf<String>()
        if (commandName != "pkg") {
            pkgArgs.add(commandName)
        }
        val nextArgs = mutableListOf<String>()
        for (arg in args) {
            if (nextArgs.isNotEmpty() || arg == pipelineBuilder.separator) {
                nextArgs.add(arg)
            } else {
                pkgArgs.add(arg)
            }
        }
        pipelineBuilder.add(
            JPkgPipelineBuilder.Step.Pkg(
                mount = options.mount,
                commit = options.commit,
                args = pkgArgs.toList()
            )
        )
        if (nextArgs.isNotEmpty()) {
            NextCommand(pipelineBuilder).main(nextArgs.toList())
        }
    }
}

private val pkgDbDir = Path("var/db/pkg")
private val pkgCacheDir = Path("var/cache/pkg")
private val pkgRepoConfDir = Path("usr/local/etc/pkg/repos")
private val pkgConf = Path("usr/local/etc/pkg.conf")

class JPkgPipelineBuilder(
    val separator: String?,
    private val logger: OciLogger,
    private val pkgBin: Path?,
    private val pkgCacheBase: Path,
    private val interceptRcJail: String,
    private val pkgOptions: List<String>,
    private val mount: List<String>,
    private val root: Path?,
    private val from: String?,
    private val commit: String?
) {
    private val steps = mutableListOf<Step>()
    private val cleanups: ArrayDeque<() -> Unit> = ArrayDeque()

    sealed class Step(val mount: List<String>, val commit: String?) {
        class Run(
            mount: List<String>,
            commit: String?,
            val noExpand: Boolean,
            val clean: Boolean,
            val shell: Boolean,
            val args: List<String>
        ) : Step(mount, commit)

        class Pkg(
            mount: List<String>,
            commit: String?,
            val args: List<String>
         ) : Step(mount, commit)
    }

    fun add(step: Step) {
        steps += step
    }

    fun run() {
        val tmpDir: Path = createTempDirectory("jpkg-").toRealPath()
        cleanups += {
            if (tmpDir.exists()) {
                tmpDir.deleteIfExists()
            }
        }
        val realTmpDir = tmpDir.toRealPath()
        val buildahHome: Path?
        val cid: String?
        val rootPath = if (from != null) {
            buildahHome = buildahHome()
            cid = buildahProcess(
                buildahHome,
                "from",
                "--name=${tmpDir.name}",
                from
            ).pReadLines { lines -> lines.last() }
            cleanups += {
                buildahProcess(buildahHome, "rm", cid).pReadLines {}
            }
            val mp = buildahProcess(buildahHome, "mount", cid)
                .pReadLines { lines -> lines.last() }
            Path(mp)
        } else {
            buildahHome = null
            cid = null
            root
        }
        val jail = if (rootPath == null) {
            null
        } else {
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
            val lock = lockFile.lock()
            cleanups += {
                lock.close()
            }
            prepareJail(realTmpDir, rootPath, cleanups)
        }
        for (step in steps) {
            mount(realTmpDir, step.mount)
            when (step) {
                is Step.Run -> runCmdInJail(jail, step)
                is Step.Pkg -> runPkgInJail(jail, step)
            }
            //TODO unmount
            step.commit?.let { tag ->
                require(buildahHome != null)
                require(cid != null)
                buildahProcess(buildahHome, "commit", cid, tag)
            }
        }
        commit?.let { tag ->
            require(buildahHome != null)
            require(cid != null)
            buildahProcess(buildahHome, "commit", cid, tag)
        }
    }

    fun cleanup() {
        synchronized(cleanups) {
            while (cleanups.isNotEmpty()) {
                cleanups.last().invoke()
                cleanups.removeLast()
            }
        }
    }

    private fun cacheRoot(): Path {
        val osRelDate = sysctlByNameInt32("kern.osreldate")!!
        val arch = sysctlByNameString("hw.machine_arch")!!
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

    @OptIn(ExperimentalPathApi::class)
    private fun prepareJail(
        realTmpDir: Path,
        rootPath: Path,
        cleanups: ArrayDeque<() -> Unit>
    ): JailParameters {
        val cacheRoot = cacheRoot()
        nmount("nullfs", realTmpDir, rootPath)
        cleanups += {
            val mountInfo = readJailMountInfo() ?: runBlocking {
                readMountInfo()
            }
            val fsId = mountInfo
                .lastOrNull { mount -> mount.node == realTmpDir.pathString }
                ?.parseFsId()
            if (fsId != null) {
                unmount(fsId, force = true)
            } else {
                unmount(realTmpDir.pathString, force = true)
            }
        }
        prepareRoot(realTmpDir)
        ProcessBuilder(
            "jail",
            "-c",
            "name=${realTmpDir.name}",
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
                add(realTmpDir.name)
            }
            logger.trace(TraceEvent.Exec(cleanupArgs))
            logger.close()
            try {
                ProcessBuilder(cleanupArgs).exec()
            } finally {
                logger.open()
            }
            ProcessBuilder("jail", "-r", realTmpDir.name).exec()
        }
        val jail = runBlocking {
            readJailParameters().singleOrNull { p ->
                p.name == realTmpDir.name
            }
        } ?: error("jail ${realTmpDir.name} vanished after create")

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
        mount(realTmpDir, mount)
        return jail
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
            runPkg(listOf("fetch", "--quiet", "-y", "FreeBSD-runtime"))
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

    private fun mount(realTmpDir: Path, mount: List<String>) {
        for (input in mount) {
            val source = input.substringBefore(':')
            val destinationAndOptions = input.substringAfter(':', source)
            val destination = destinationAndOptions.substringBefore(':')
            val options = destinationAndOptions.substringAfter(':', "")
                .splitToSequence(',').mapNotNull { o ->
                    o.trim().takeUnless { it.isBlank() }
                }
            val realDestination = createMountPoint(
                realTmpDir,
                Path(destination.trimStart('/'))
            )
            nmount(
                "nullfs",
                realDestination,
                Path(source),
                readOnly = "ro" in options
            )
            //TODO add cleanup to prune empty dirs
        }
    }

    private fun runPkgInJail(jail: JailParameters?, step: Step.Pkg) {
        val pb = ProcessBuilder(
            buildList {
                add(pkgBin?.pathString ?: "pkg")
                if (jail != null) {
                    add("-j")
                    add(jail.name)
                }
                addAll(pkgOptions)
                addAll(step.args)
            }
        )
        val env = pb.environment()
        env["REPO_AUTOUPDATE"] = "false"
        pb.exec()
    }

    private fun runCmdInJail(jail: JailParameters?, step: Step.Run) {
        ProcessBuilder(
            buildList {
                if (jail != null) {
                    add("jexec")
                    add(jail.name)
                    if (step.clean) {
                        add("-l")
                    }
                } else {
                    require(!step.clean) {
                        "cannot run with clean environment without a jail"
                    }
                }
                if (step.shell) {
                    add("/bin/sh")
                    add("-c")
                }
                addAll(step.args)
            }
        ).exec()
    }

    private fun runPkg(args: List<String>) {
        val cacheRoot = cacheRoot()
        val pb = ProcessBuilder(
            buildList {
                add(pkgBin?.pathString ?: "pkg")
                add("-C")
                add((cacheRoot / pkgConf).pathString)
                add("-R")
                add((cacheRoot / pkgRepoConfDir).pathString)
                addAll(args)
            }
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

    private fun buildahHome(): Path {
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
            runPkg(listOf("fetch", "-y", *pkgs.toTypedArray()))
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
        return cacheRoot / "usr/local/$v"
    }

    private fun buildahProcess(
        buildahHome: Path,
        vararg arg: String
    ): ProcessBuilder {
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
