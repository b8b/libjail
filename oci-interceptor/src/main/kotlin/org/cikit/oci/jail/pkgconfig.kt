package org.cikit.oci.jail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import kotlin.io.path.*

class PkgConfig(
    val pkgSite: String,
    val basePkgDir: String?,
    val portPkgDir: String,
    val pkgCacheRoot: Path,
) {
    private val pkgConf: Path = Path("etc/pkg.conf")
    private val pkgRepoConfDir: Path = Path("etc/pkg/repos")
    private val pkgCacheDir = Path("var/cache/pkg")
    private val pkgDbDir = Path("var/db/pkg")
    private val pkgFingerPrintsDirPath = Path("/usr/share/keys/pkg")

    data class RepoConfig(
        val name: String,
        val url: String,
        val fingerPrints: Path?,
        val priority: Int? = null
    ) {
        fun toUcl(): String {
            require(name.all { ch -> ch.isLetterOrDigit() || ch in "_-" }) {
                "illegal repository name: $name"
            }
            val urlEscaped = Json.encodeToString(JsonPrimitive(url))
            return buildString {
                appendLine("$name: {")
                appendLine("    url: $urlEscaped,")
                appendLine("    mirror_type: \"srv\",")
                if (fingerPrints != null) {
                    val fingerPrintsEscaped = Json.encodeToString(
                        JsonPrimitive(
                            fingerPrints.invariantSeparatorsPathString
                        )
                    )
                    appendLine("    signature_type: \"fingerprints\",")
                    appendLine("    fingerprints: $fingerPrintsEscaped,")
                }
                if (priority != null) {
                    appendLine("    priority: $priority,")
                }
                appendLine("    enabled: yes")
                appendLine("}")
            }
        }
    }

    class PkgOptions(
        val pkgCacheRoot: Path,
        val configFile: Path,
        val repoConfigDir: Path,
        val repoConfigFiles: Map<String, RepoConfig>,
        val localRepoDir: Path,
        val pkgCacheDir: Path,
        val pkgDbDir: Path,
        val lastModified: FileTime?
    )

    fun configure(): PkgOptions {
        val configFiles = generateConfigFiles()
        val serializedConfigFiles = configFiles.mapValues { (_, v) ->
            v.toUcl()
        }
        val baseDir = pkgCacheRoot / sha256(serializedConfigFiles)
        if (baseDir.exists()) {
            require(verifyConfig(baseDir, serializedConfigFiles)) {
                "failed to verify pkg config '$baseDir'"
            }
        } else {
            writeConfig(baseDir, serializedConfigFiles)
        }
        val dbDir = baseDir / pkgDbDir
        if (!dbDir.exists()) {
            dbDir.createDirectories()
        }
        val lastModified = dbDir.walk()
            .map { it.getLastModifiedTime(LinkOption.NOFOLLOW_LINKS) }
            .maxOrNull()
        return PkgOptions(
            pkgCacheRoot = pkgCacheRoot,
            configFile = baseDir / pkgConf,
            repoConfigDir = baseDir / pkgRepoConfDir,
            repoConfigFiles = configFiles,
            localRepoDir = pkgCacheRoot / "local",
            pkgCacheDir = pkgCacheRoot / pkgCacheDir,
            pkgDbDir = dbDir,
            lastModified = lastModified
        )
    }

    private fun generateConfigFiles(): Map<String, RepoConfig> = buildMap {
        // Repo configs are written into the jail's /usr/local/etc/pkg/repos/ so
        // they override the stock /etc/pkg/FreeBSD.conf: pkg's REPOS_DIR searches
        // /etc/pkg/ then /usr/local/etc/pkg/repos/ and overwrites same-named
        // repos, so these repo names/urls/fingerprints take precedence.
        // ${ABI}, ${VERSION_*} are left literal for pkg to expand at runtime.
        this += "FreeBSD-ports.conf" to RepoConfig(
            name = "FreeBSD-ports",
            url = $$"pkg+https://$${pkgSite}/${ABI}/$${portPkgDir}",
            fingerPrints = pkgFingerPrintsDirPath
        )
        this += "FreeBSD-ports-kmods.conf" to RepoConfig(
            name = "FreeBSD-ports-kmods",
            url = $$"pkg+https://$${pkgSite}/${ABI}/kmods_latest_${VERSION_MINOR}",
            fingerPrints = pkgFingerPrintsDirPath
        )
        if (basePkgDir != null) {
            this += "FreeBSD-base.conf" to RepoConfig(
                name = "FreeBSD-base",
                url = $$"pkg+https://$${pkgSite}/${ABI}/$${basePkgDir}",
                fingerPrints = Path($$"/usr/share/keys/pkgbase-${VERSION_MAJOR}")
            )
        }
        val localRepo = pkgCacheRoot / "local"
        if (!repoIsEmpty(localRepo)) {
            this += "local.conf" to RepoConfig(
                name = "local",
                url = "file://${localRepo.absolutePathString()}",
                fingerPrints = null,
                priority = 10
            )
        }
    }

    private fun sha256(configFiles: Map<String, String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(pkgConf.invariantSeparatorsPathString.encodeToByteArray())
        md.update(0)
        md.update(0)
        for ((fileName, contents) in configFiles.entries.sortedBy { it.key }) {
            val relPath = pkgRepoConfDir / fileName
            md.update(relPath.invariantSeparatorsPathString.encodeToByteArray())
            md.update(0)
            md.update(contents.encodeToByteArray())
            md.update(0)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verifyConfig(
        baseDir: Path,
        filesWanted: Map<String, String>
    ): Boolean {
        if (!(baseDir / pkgConf).exists()) {
            return false
        }
        if (!(baseDir / pkgConf).readText().isBlank()) {
            return false
        }
        if (!(baseDir / pkgRepoConfDir).exists()) {
            return false
        }
        val filesActual = (baseDir / pkgRepoConfDir)
            .listDirectoryEntries("*.conf")
            .map { it.name }
        if (filesWanted.keys.size != filesActual.size) {
            return false
        }
        if (!filesWanted.keys.containsAll(filesActual)) {
            return false
        }
        for ((fileName, contentsWanted) in filesWanted) {
            val contentsActual = (baseDir / pkgRepoConfDir / fileName)
                .readText()
            if (contentsActual != contentsWanted) {
                return false
            }
        }
        return true
    }

    private fun writeConfig(
        baseDir: Path,
        filesWanted: Map<String, String>
    ) {
        (baseDir / pkgConf).let { p ->
            p.createParent()
            p.writeText("")
        }
        val filesExisting = (baseDir / pkgRepoConfDir)
            .takeIf { it.exists() }
            ?.listDirectoryEntries("*.conf")
            ?.map { it.name }
            ?: emptyList()

        for (fileName in filesWanted.keys + filesExisting) {
            val contents = filesWanted[fileName]
            if (contents == null) {
                (baseDir / pkgRepoConfDir / fileName).deleteIfExists()
            } else {
                (baseDir / pkgRepoConfDir / fileName).let { p ->
                    p.createParent()
                    p.writeText(contents)
                }
            }
        }
    }

    private fun Path.createParent() = parent?.let { parent ->
        if (!parent.exists()) {
            parent.createDirectories()
        }
    }

    companion object {
        fun repoIsEmpty(path: Path) = path
            .takeIf { it.exists() }
            ?.listDirectoryEntries("packagesite.*")
            ?.isEmpty()
            ?: true
    }
}
