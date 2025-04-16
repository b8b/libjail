package org.cikit.oci.jail

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.theme
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.runBlocking
import org.cikit.libjail.*
import org.cikit.oci.OciLogger
import java.nio.file.Path
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

    private val pkgBin by option(envvar = "JPKG_PKG_BIN")
        .help("path to pkg binary")
        .path(canBeDir = false)

    private val pkgCacheBase by option(envvar = "JPKG_CACHE_BASE")
        .help("base path to local cache")
        .path(canBeFile = false)

    private val root by option("-r", "--rootdir")
        .help("path to jail root directory")
        .path(canBeFile = false)
        .required()

    private val args by argument("PKG_ARG").multiple()

    private val osRelDate = sysctlByNameInt32("kern.osreldate")!!
    private val arch = sysctlByNameString("hw.machine_arch")!!

    private fun cacheRoot(): Path {
        val base = pkgCacheBase ?: Path("/var/cache/jpkg")
        val maj = osRelDate / 100_000
        val min = osRelDate / 1_000 - maj * 100
        return base / "FreeBSD-$maj.$min-RELEASE-$arch"
    }

    @OptIn(ExperimentalPathApi::class)
    override fun run() {
        currentContext.findOrSetObject {
            OciLogger(
                logFile = log,
                logFormat = logFormat,
                logLevel = logLevel
            )
        }

        val cacheRoot = cacheRoot()
        val pkgDbDir = Path("var/db/pkg/repos")
        val pkgCacheDir = Path("var/cache/pkg")
        val pkgConfDir = Path("usr/local/etc/pkg/repos")

        // prepare root
        (root / "dev").createDirectories()
        (root / pkgDbDir).createDirectories()
        (root / pkgCacheDir).createDirectories()
        (root / pkgConfDir).createDirectories()
        val rootResolvConf = (root / "etc").createDirectories() / "resolv.conf"
        if (!rootResolvConf.exists()) {
            rootResolvConf.writeText("")
        }
        val rootUname = (root / "usr/bin").createDirectories() / "uname"
        val runtimePkg by lazy {
            runPkg("fetch", "--quiet", "-y", "FreeBSD-runtime")
            val pkgName = runPkgSearchVersion("FreeBSD-runtime")
            cacheRoot / pkgCacheDir / "$pkgName.pkg"
        }
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

        var cleanup = {}
        Runtime.getRuntime().addShutdownHook(
            thread(start = false) {
                cleanup()
            }
        )
        val tmpDir = createTempDirectory("jpkg-").toRealPath()
        cleanup = {
            if (tmpDir.exists()) {
                tmpDir.deleteIfExists()
            }
        }

        nmount("nullfs", tmpDir, root)
        cleanup = {
            //TODO unbound by fsid
            unmount(tmpDir.pathString, force = true)
            if (tmpDir.exists()) {
                tmpDir.deleteIfExists()
            }
        }

        val tmpPkgDbDir = tmpDir / pkgDbDir
        val tmpPkgCacheDir = tmpDir / pkgCacheDir
        val tmpPkgConfDir = tmpDir / pkgConfDir
        val tmpResolvConf = tmpDir / "etc/resolv.conf"

        ProcessBuilder(
            "jail",
            "-c",
            "name=${tmpDir.name}",
            "path=$tmpDir",
            "mount.devfs",
            "devfs_ruleset=4", //TODO remove when nested?
            "allow.chflags=1",
            "ip4=inherit",
            "ip6=inherit",
            "persist"
        ).inheritIO().start().waitFor().let { rc ->
            require(rc == 0) { "jail terminated with exit code $rc" }
        }

        cleanup = {
            val rc = ProcessBuilder("jail", "-r", tmpDir.name)
                .inheritIO()
                .start()
                .waitFor()
            require(rc == 0) {
                "jail terminated with exit code $rc"
            }
            if (tmpDir.exists()) {
                tmpDir.deleteIfExists()
            }
        }

        val jail = runBlocking {
            readJailParameters().singleOrNull { p ->
                p.name == tmpDir.name
            }
        } ?: error("jail ${tmpDir.name} vanished after create")

        cleanup = {
            runBlocking {
                cleanup(jail, attach = false)
            }
            jailRemove(jail)
            //TODO unmount by fsid
            unmount(tmpDir.pathString, force = true)
            if (tmpDir.exists()) {
                tmpDir.deleteIfExists()
            }
        }

        nmount("nullfs", tmpPkgCacheDir, cacheRoot / "var/cache/pkg")
        nmount("tmpfs", tmpPkgDbDir)
        (cacheRoot / pkgDbDir).copyToRecursively(
            target = tmpPkgDbDir,
            followLinks = false,
            overwrite = true
        )
        nmount(
            "nullfs",
            tmpPkgConfDir,
            cacheRoot / pkgConfDir,
            readOnly = true
        )
        nmount(
            "nullfs",
            tmpResolvConf,
            Path("/etc/resolv.conf"),
            readOnly = true
        )

        val rc = ProcessBuilder("/bin/sh")
            .inheritIO()
            .apply {
                environment()["JAIL"] = jail.name
                environment()["JID"] = jail.jid.toString()
                environment()["PKG"] = pkgBin?.pathString ?: "pkg"
            }
            .start()
            .waitFor()
        if (rc != 0) {
            throw ProgramResult(rc)
        }
    }

    private fun runPkg(vararg arg: String) {
        val cacheRoot = cacheRoot()
        val rc = ProcessBuilder(
            pkgBin?.pathString ?: "pkg",
            "-C", (cacheRoot / "usr/local/etc/pkg.conf").pathString,
            "-R", (cacheRoot / "usr/local/etc/pkg/repos").pathString,
            *arg
        )
            .inheritIO()
            .apply {
                environment()["INSTALL_AS_USER"] = "yes"
                environment()["PKG_DBDIR"] =
                    (cacheRoot / "var/db/pkg").pathString
                environment()["PKG_CACHEDIR"] =
                    (cacheRoot / "var/cache/pkg").pathString
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
            "-C", (cacheRoot / "usr/local/etc/pkg.conf").pathString,
            "-R", (cacheRoot / "usr/local/etc/pkg/repos").pathString,
            "search", "--quiet", "--no-repo-update",
            "-r", "base", "-S", "name", "-L", "pkg-name",
            "--exact", name
        )
            .inheritIO()
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .apply {
                environment()["INSTALL_AS_USER"] = "yes"
                environment()["PKG_DBDIR"] =
                    (cacheRoot / "var/db/pkg").pathString
                environment()["PKG_CACHEDIR"] =
                    (cacheRoot / "var/cache/pkg").pathString
            }
            .start()
        val pkgName = p.inputStream.use { String(it.readBytes()) }
        val rc = p.waitFor()
        require(rc == 0) {
            "pkg terminated with exit code $rc"
        }
        return pkgName.trim()
    }
}
