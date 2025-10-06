package org.cikit.oci.jail

import com.github.ajalt.clikt.core.PrintMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.cikit.libjail.*
import org.cikit.oci.OciLogger
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlin.io.path.*

private val pkgDbDir = Path("var/db/pkg")
private val pkgCacheDir = Path("var/cache/pkg")
private val pkgRepoConfDir = Path("usr/local/etc/pkg/repos")
private val pkgLocalRepoDir = Path("var/db/packages/local")
private val pkgFingerPrintsDir = Path("usr/share/keys/pkg")

class PkgbuildPipeline(
    val separator: String?,
    val logger: OciLogger,
    pkgBin: Path?,
    val pkgCacheBase: Path,
    val pkgSite: String,
    val pkgKeys: Path?,
    val hostMajorVersion: Int,
    val hostMinorVersion: Int,
    val hostPatchVersion: Int,
    val hostArch: String,
    val jailAbi: String,
    jailBasePkgDir: String,
    portPkgDir: String = "latest",
    private val interceptRcJail: String,
    val pkgOptions: List<String>,
    val mount: List<String>,
    val source: Source,
    val commit: String?
) {
    private val steps = mutableListOf<Step>()
    private val cleanups: ArrayDeque<() -> Unit> = ArrayDeque()

    private val jailPkgCacheRoot = pkgCacheBase / jailAbi.replace(":", "-")

    private val jailPkgConfig = PkgConfig(
        pkgSite = pkgSite,
        pkgKeys = pkgKeys,
        basePkgDir = jailBasePkgDir,
        portPkgDir = portPkgDir,
        pkgCacheRoot = jailPkgCacheRoot
    )

    private val jailPkgOptions = jailPkgConfig.configure()

    private val hostPkgCacheRoot =
        pkgCacheBase / "FreeBSD-$hostMajorVersion-$hostArch"
    private val hostAbi = "FreeBSD:$hostMajorVersion:$hostArch"

    private val hostPkgConfig = if (hostPkgCacheRoot == jailPkgCacheRoot) {
        jailPkgConfig
    } else {
        PkgConfig(
            pkgSite = pkgSite,
            pkgKeys = pkgKeys,
            basePkgDir = null,
            portPkgDir = portPkgDir,
            pkgCacheRoot = hostPkgCacheRoot
        )
    }

    private val hostPkgOptions = hostPkgConfig.configure()

    private val pkgBinPath = pkgBin ?: (hostPkgCacheRoot / pkgStaticPath)

    private val autoUpdate = System.getenv("REPO_AUTOUPDATE") != "false"
    private var updateJailPkgDb = autoUpdate

    init {
        if (pkgBinPath !== pkgBin) {
            var doFetch = !pkgBinPath.isExecutable()
            if (!doFetch) {
                if (autoUpdate) {
                    val latestVersion = runPkgSearchVersion(
                        hostPkgOptions,
                        "pkg"
                    ).removePrefix("pkg-")
                    if (hostPkgOptions.pkgDbDir == jailPkgOptions.pkgDbDir) {
                        updateJailPkgDb = false
                    }
                    val currentVersion = runPkgReportVersion(hostPkgOptions)
                    if (latestVersion.trim() != currentVersion.trim()) {
                        logger.warn(
                            "updating pkg: $currentVersion -> $latestVersion"
                        )
                        doFetch = true
                    }
                }
            }
            if (doFetch) {
                fetchPkg(
                    logger = logger,
                    pkgCacheRoot = hostPkgCacheRoot,
                    pkgAbiString = hostAbi,
                    pkgSite = pkgSite,
                    pkgKeys = pkgKeys ?: Path(pkgKeysDefault)
                )
            }
        }
        val currentVersion = runPkgReportVersion(hostPkgOptions)
        logger.info("using pkg '$pkgBinPath' version $currentVersion")
    }

    sealed class Source {
        class From(val image: String) : Source()
        class Root(val path: Path) : Source()
    }

    sealed class Step(val mount: List<String>, val commit: String?) {
        class Run(
            mount: List<String>,
            commit: String?,
            val noExpand: Boolean,
            val clean: Boolean,
            val shell: Boolean,
            val args: List<String>
        ) : Step(mount, commit)

        class BuildPackage(
            mount: List<String>,
            commit: String?,
            val origin: String,
            val makeEnv: Map<String, String> = emptyMap(),
            val makeVars: Map<String, String> = emptyMap(),
            val portOptions: Set<String> = emptySet(),
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
        val tmpDir: Path = createTempDirectory("pkgbuild-").toRealPath()
        cleanups += {
            if (tmpDir.exists()) {
                tmpDir.deleteIfExists()
            }
        }
        val realTmpDir = tmpDir.toRealPath()
        val buildahHome: Path?
        val cid: String?
        val rootPath = when (source) {
            is Source.From -> {
                buildahHome = buildahHome()
                cid = buildahProcess(
                    buildahHome,
                    "from",
                    "--name=${tmpDir.name}",
                    source.image
                ).useLines { lines -> lines.last() }
                cleanups += {
                    buildahProcess(buildahHome, "rm", cid).useLines {}
                }
                val mp = buildahProcess(buildahHome, "mount", cid)
                    .useLines { lines -> lines.last() }
                Path(mp)
            }
            is Source.Root -> {
                buildahHome = null
                cid = null
                source.path
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
            } catch (_: Throwable) {
                //ignore
            }
            Path("$tmpDir.lock").deleteIfExists()
        }
        val lock = lockFile.lock()
        cleanups += {
            lock.close()
        }
        val jail = prepareJail(realTmpDir, rootPath, cleanups)
        if (updateJailPkgDb) {
            if (jailPkgOptions.lastModified == null ||
                jailPkgOptions.lastModified.toInstant()
                    .plusSeconds(30)
                    .isAfter(Instant.now())) {
                runPkg(jailPkgOptions, listOf("update"))
            }
        }
        for (step in steps) {
            mount(realTmpDir, step.mount)
            when (step) {
                is Step.Run -> runCmdInJail(jail, step)
                is Step.BuildPackage -> runBuildPackageInJail(
                    jail,
                    realTmpDir,
                    step
                )
                is Step.Pkg -> runPkgInJail(jail, step)
            }
            unmount(realTmpDir, step.mount)
            step.commit?.let { tag ->
                require(buildahHome != null)
                require(cid != null)
                buildahProcess(buildahHome, "commit", cid, tag).useLines {}
            }
        }
        commit?.let { tag ->
            require(buildahHome != null)
            require(cid != null)
            buildahProcess(buildahHome, "commit", cid, tag).useLines {}
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

    @OptIn(ExperimentalPathApi::class)
    private fun prepareJail(
        realTmpDir: Path,
        rootPath: Path,
        cleanups: ArrayDeque<() -> Unit>
    ): JailParameters {
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
                add("--log-level=debug")
                add("cleanup")
                add("-j")
                add(realTmpDir.name)
            }
            logger.trace(TraceEvent.Exec(cleanupArgs))
            logger.close()
            try {
                ProcessBuilder(cleanupArgs).useLines { lines ->
                    for (line in lines) {
                        logger.trace(
                            TraceEvent.Debug("$interceptRcJail: $line")
                        )
                    }
                }
            } catch (ex: Throwable) {
                logger.error("jail cleanup failed", ex)
                throw ex
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
        val tmpRepoConfDir = createMountPoint(realTmpDir, pkgRepoConfDir)
        val tmpLocalRepoDir = createMountPoint(realTmpDir, pkgLocalRepoDir)
        val tmpTrustedDir = createMountPoint(
            realTmpDir,
            Path("$pkgFingerPrintsDir/trusted")
        )
        val tmpRevokedDir = createMountPoint(
            realTmpDir,
            Path("$pkgFingerPrintsDir/revoked")
        )
        val tmpEtcDir = createMountPoint(realTmpDir, Path("etc"))
        val tmpEtcLocalDir = createMountPoint(realTmpDir, Path("usr/local/etc"))
        val tmpResolvConf = tmpEtcDir / "resolv.conf"
        val tmpPkgConf = tmpEtcLocalDir / "pkg.conf"

        // setup /var/cache/pkg
        nmount("nullfs", tmpPkgCacheDir, jailPkgOptions.pkgCacheDir)

        // setup pkg.conf
        logger.info("mounting /usr/local/etc/pkg.conf")
        if (!tmpPkgConf.exists(LinkOption.NOFOLLOW_LINKS)) {
            tmpPkgConf.writeText("")
        }
        nmount(
            "nullfs",
            tmpPkgConf,
            jailPkgOptions.configFile,
            readOnly = true
        )

        // setup pkg repos
        pkgKeys?.let { p ->
            val trusted = (p / "trusted").listDirectoryEntries().map {
                it.name
            }.toSet()
            for (entry in tmpTrustedDir.listDirectoryEntries()) {
                if (entry.name !in trusted) {
                    entry.deleteIfExists()
                }
            }
            for (entry in trusted) {
                (p / "trusted" / entry).copyTo(
                    target = tmpTrustedDir / entry,
                    overwrite = true
                )
            }
            if ((p / "revoked").exists()) {
                for (f in (p / "revoked").listDirectoryEntries()) {
                    f.copyTo(tmpRevokedDir / f.fileName, overwrite = true)
                }
            }
        }
        val haveTrusted = tmpTrustedDir.listDirectoryEntries().any {
            !it.isDirectory() && it.isReadable()
        }
        nmount("tmpfs", tmpRepoConfDir)
        for ((name, config) in jailPkgOptions.repoConfigFiles) {
            if (name != "local.conf") {
                logger.info("setting up repository config '$name'")
                require(haveTrusted) {
                    "refuse to configure repository '$name': " +
                            "no trusted fingerprints available"
                }
                (tmpRepoConfDir / name).writeText(
                    config.copy(
                        fingerPrints = Path("/") / pkgFingerPrintsDir
                    ).toUcl()
                )
            }
        }

        // setup local repo
        logger.info("mounting /$pkgLocalRepoDir")
        if (!jailPkgOptions.localRepoDir.exists()) {
            jailPkgOptions.localRepoDir.createDirectories()
        }
        nmount(
            "nullfs",
            tmpLocalRepoDir,
            jailPkgOptions.localRepoDir,
            readOnly = true
        )
        configureLocalRepo(realTmpDir)

        // setup /var/db/pkg
        logger.info("setting up /$pkgDbDir")
        nmount("tmpfs", tmpPkgDbDir / "repos")
        val pkgDbRepos = jailPkgOptions.pkgDbDir / "repos"
        if (pkgDbRepos.exists()) {
            //TODO need some lock while copying
            for (r in pkgDbRepos.listDirectoryEntries()) {
                val name = r.name
                if (name == "local" || !r.isDirectory()) {
                    continue
                }
                r.copyToRecursively(
                    target = tmpPkgDbDir / "repos" / name,
                    followLinks = false,
                    overwrite = true
                )
            }
        }

        // setup /etc/resolv.conf
        logger.info("mounting /etc/resolv.conf")
        nmount(
            "nullfs",
            tmpResolvConf,
            Path("/etc/resolv.conf"),
            readOnly = true
        )

        mount(realTmpDir, mount)
        return jail
    }

    private fun configureLocalRepo(realTmpDir: Path) {
        val tmpRepoConfDir = createMountPoint(realTmpDir, pkgRepoConfDir)
        val tmpLocalRepoConf = tmpRepoConfDir / "local.conf"
        if (PkgConfig.repoIsEmpty(jailPkgOptions.localRepoDir)) {
            tmpLocalRepoConf.deleteIfExists()
        } else {
            logger.info("setting up repository config 'local.conf'")
            tmpLocalRepoConf.writeText(
                PkgConfig.RepoConfig(
                    name = "local",
                    url = "file:///" +
                            pkgLocalRepoDir.invariantSeparatorsPathString,
                    fingerPrints = null
                ).toUcl()
            )
        }
    }

    private fun prepareRoot(root: Path) {
        createMountPoint(root, Path("dev"))
        val etcMp = createMountPoint(root, Path("etc"))
        val usrBinMp = createMountPoint(root, Path("usr/bin"))
        val varDbMp = createMountPoint(root, Path("var/db"))
        createMountPoint(root, pkgDbDir / "repos")
        createMountPoint(root, pkgCacheDir)
        createMountPoint(root, pkgRepoConfDir)

        val rootResolvConf = etcMp / "resolv.conf"
        if (!rootResolvConf.exists(LinkOption.NOFOLLOW_LINKS)) {
            rootResolvConf.writeText("")
        }

        val runtimePkg by lazy {
            runPkg(
                jailPkgOptions,
                listOf("fetch", "--quiet", "-y", "FreeBSD-runtime")
            )
            val pkgName = runPkgSearchVersion(
                jailPkgOptions,
                "FreeBSD-runtime",
                "FreeBSD-base"
            )
            jailPkgCacheRoot / pkgCacheDir / "$pkgName.pkg"
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
        }
    }

    private fun unmount(realTmpDir: Path, mount: List<String>) {
        val mountInfo = readJailMountInfo() ?: runBlocking {
            readMountInfo()
        }
        for (input in mount) {
            val source = input.substringBefore(':')
            val destinationAndOptions = input.substringAfter(':', source)
            val destination = destinationAndOptions.substringBefore(':')
            val realDestination = createMountPoint(
                realTmpDir,
                Path(destination.trimStart('/'))
            )
            val fsId = mountInfo
                .lastOrNull { m -> m.node == realDestination.pathString }
                ?.parseFsId()
            if (fsId != null) {
                unmount(fsId, force = true)
            } else {
                unmount(realDestination.pathString, force = true)
            }
        }
    }

    private fun runPkgInJail(jail: JailParameters, step: Step.Pkg) {
        val pb = ProcessBuilder(
            buildList {
                add(pkgBinPath.pathString)
                add("-j")
                add(jail.name)
                addAll(pkgOptions)
                addAll(step.args)
            }
        )
        pb.exec()
    }

    private fun runCmdInJail(jail: JailParameters, step: Step.Run) {
        ProcessBuilder(
            buildList {
                add("jexec")
                if (step.clean) {
                    add("-l")
                }
                add(jail.name)
                if (step.shell) {
                    add("/bin/sh")
                    add("-c")
                }
                addAll(step.args)
            }
        ).exec()
    }

    private fun runBuildPackageInJail(
        jail: JailParameters,
        realTmpDir: Path,
        step: Step.BuildPackage,
    ) {
        val workDir = Path("pkgbuild")
        val realWorkDir = createMountPoint(realTmpDir, workDir)
        val portsDir = Path("usr/ports")
        val distFiles = workDir / "distfiles"
        val packages = workDir / "packages"
        createMountPoint(realTmpDir, packages)
        val hostDistFiles = pkgCacheBase / distFiles.fileName
        if (!hostDistFiles.exists()) {
            hostDistFiles.createDirectories()
        }
        val mounts = listOf(
            "${hostDistFiles}:/${distFiles.pathString}"
        )
        mount(realTmpDir, mounts)

        val envArgs = if (step.makeEnv.isEmpty()) {
            emptyList()
        } else {
            listOf("/usr/bin/env") + step.makeEnv.map { (k, v) -> "$k=$v" }
        }

        val make = envArgs + listOf(
            "make",
            "BATCH=yes",
            "USE_PACKAGE_DEPENDS_ONLY=yes",
            "PORT_OPTIONS=${step.portOptions.joinToString(" ")}",
            "DISTDIR=/${distFiles.invariantSeparatorsPathString}",
            "PACKAGES=/${packages.invariantSeparatorsPathString}",
            "WRKDIRPREFIX=/${workDir.invariantSeparatorsPathString}",
            *step.makeVars.entries.map { (k, v) -> "$k=$v" }.toTypedArray(),
            "-C", "/${portsDir.invariantSeparatorsPathString}/${step.origin}"
        )

        val pkgName = ProcessBuilder(
            buildList {
                add("jexec")
                add("-l")
                add(jail.name)
                addAll(make)
                add("-VPKGNAME")
            }
        ).useLines { lines ->
            lines.last()
        }

        logger.info("starting package build for '$pkgName'")

        val dependencyDirs = ProcessBuilder(
            buildList {
                add("jexec")
                add("-l")
                add(jail.name)
                addAll(make)
                add("build-depends-list")
                add("run-depends-list")
            }
        ).useLines { lines ->
            lines.toList()
        }

        val dependencies = dependencyDirs.map { dir ->
            val dependencyName = ProcessBuilder(
                buildList {
                    add("jexec")
                    add("-l")
                    add(jail.name)
                    addAll(envArgs)
                    add("make")
                    add("BATCH=yes")
                    add("-C")
                    add(dir)
                    add("-VPKGNAMEPREFIX")
                    add("-VPORTNAME")
                    add("-VPKGNAMESUFFIX")
                }
            ).useLines { lines -> lines.joinToString("") }
            logger.info("'$pkgName' depends on $dir -> '$dependencyName'")
            dependencyName
        }

        runPkgInJail(
            jail,
            Step.Pkg(
                mount = step.mount,
                commit = step.commit,
                args = listOf("install", "-A", "-y") + dependencies
            )
        )

        runCmdInJail(
            jail,
            Step.Run(
                mount = emptyList(),
                commit = null,
                noExpand = true,
                clean = true,
                shell = false,
                make + listOf("package")
            )
        )

        val targetDir = jailPkgOptions.localRepoDir / "All"
        if (!targetDir.exists()) {
            targetDir.createDirectories()
        }
        val pkg = realWorkDir /
                packages.relativeTo(workDir) /
                "All" /
                "$pkgName.pkg"
        logger.info("copying $pkg to local repository")
        pkg.copyTo(target = targetDir / pkg.fileName, overwrite = true)

        runPkg(
            jailPkgOptions,
            listOf(
                "repo", jailPkgOptions.localRepoDir.pathString
            )
        )

        configureLocalRepo(realTmpDir)

        runPkgInJail(
            jail,
            Step.Pkg(
                mount = step.mount,
                commit = step.commit,
                args = listOf("install", "-y", pkgName)
            )
        )

        unmount(realTmpDir, mounts)
    }

    private fun runPkg(
        pkgOptions: PkgConfig.PkgOptions,
        args: List<String>
    ) {
        runPkgProcessBuilder(pkgOptions, args).exec()
    }

    private fun runPkgProcessBuilder(
        pkgOptions: PkgConfig.PkgOptions,
        args: List<String>
    ): ProcessBuilder {
        val pb = ProcessBuilder(
            buildList {
                add(pkgBinPath.pathString)
                add("-C")
                add(pkgOptions.configFile.pathString)
                add("-R")
                add(pkgOptions.repoConfigDir.pathString)
                addAll(args)
            }
        )
        val env = pb.environment()
        if (pkgOptions == jailPkgOptions) {
            env["ABI"] = jailAbi
            env["OSVERSION"] = jailAbi
                .substringAfter(':', "")
                .substringBefore(':') + "00000"
            env["IGNORE_OSVERSION"] = "yes"
        }
        env["INSTALL_AS_USER"] = "yes"
        env["PKG_DBDIR"] = pkgOptions.pkgDbDir.pathString
        env["PKG_CACHEDIR"] = pkgOptions.pkgCacheDir.pathString
        return pb
    }

    private fun runPkgSearchVersion(
        pkgOptions: PkgConfig.PkgOptions,
        name: String,
        repository: String = "FreeBSD"
    ): String {
        val pb = runPkgProcessBuilder(
            pkgOptions,
            listOf(
                "search", "--quiet",
                "-r", repository, "-S", "name", "-L", "pkg-name",
                "--exact", name
            )
        )
        val pkgName = pb.useLines { lines -> lines.last() }
        return pkgName.trim()
    }

    private fun runPkgReportVersion(
        pkgOptions: PkgConfig.PkgOptions,
    ): String {
        val pb = runPkgProcessBuilder(pkgOptions, listOf("-v"))
        val v = pb.useLines { lines -> lines.last() }
        return v.trim()
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
            if (!next.exists(LinkOption.NOFOLLOW_LINKS)) {
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
        System.getenv("BUILDAH_HOME")?.let {
            logger.info("using configured BUILDAH_HOME=$it")
            return Path(it)
        }
        val v = try {
            runPkgSearchVersion(
                hostPkgOptions,
                "buildah",
                repository = "local"
            )
        } catch (_: Exception) {
            try {
                runPkgSearchVersion(hostPkgOptions, "buildah")
            } catch (ex: Exception) {
                throw RuntimeException("failed to fetch buildah: $ex", ex)
            }
        }
        val binPath = hostPkgCacheRoot / "usr/local/$v/bin/buildah"
        val pkgs = if (binPath.exists()) {
            emptyList()
        } else {
            logger.info("fetching $v")
            listOf("gpgme", "libgpg-error", "libassuan", "buildah")
        }
        if (pkgs.isNotEmpty()) {
            runPkg(
                hostPkgOptions,
                listOf("fetch", "-y", *pkgs.toTypedArray())
            )
        }
        for (pkg in pkgs) {
            val name = try {
                runPkgSearchVersion(
                    hostPkgOptions,
                    pkg,
                    repository = "local"
                )
            } catch (_: Exception) {
                runPkgSearchVersion(hostPkgOptions, pkg)
            }
            val include = if (pkg == "buildah") {
                "/usr/local/bin"
            } else {
                "/usr/local/lib"
            }
            val pkgFiles = listOf(
                (hostPkgOptions.pkgCacheDir / "$name.pkg"),
                (hostPkgOptions.localRepoDir / "All" / "$name.pkg")
            )
            ProcessBuilder(
                "tar",
                "-C",
                hostPkgCacheRoot.pathString,
                "-xf",
                pkgFiles.firstOrNull { it.exists() }?.pathString
                    ?: error("package '$name' not found after fetch"),
                "-s", "|^/usr/local|usr/local/$v|",
                include
            ).exec()
        }
        logger.info(
            "using BUILDAH_HOME=${hostPkgCacheRoot / "usr" / "local" / v}"
        )
        return hostPkgCacheRoot / "usr" / "local" / v
    }

    private fun buildahProcess(
        buildahHome: Path,
        vararg arg: String
    ): ProcessBuilder {
        val p = ProcessBuilder((buildahHome / "bin/buildah").pathString, *arg)
        p.environment()["LD_LIBRARY_PATH"] = (buildahHome / "lib").pathString
        return p
    }

    private fun <T> ProcessBuilder.useLines(
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
                val result = try {
                    Result.success(block(`in`.bufferedReader().lineSequence()))
                } catch (ex: Throwable) {
                    Result.failure(ex)
                }
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
            result.getOrThrow()
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
