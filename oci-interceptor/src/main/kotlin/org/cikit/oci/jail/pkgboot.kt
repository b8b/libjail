package org.cikit.oci.jail

import kotlinx.serialization.json.Json
import org.cikit.libjail.TraceEvent
import org.cikit.oci.OciLogger
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.*
import kotlin.io.path.*

const val pkgStaticPath = "usr/local/sbin/pkg-static"
const val pkgSiteDefault = "pkg.FreeBSD.org"
const val pkgKeysDefault = "/usr/share/keys/pkg"

fun fetchPkg(
    logger: OciLogger,
    pkgCacheRoot: Path,
    pkgAbiString: String,
    pkgSite: String = pkgSiteDefault,
    pkgKeys: Path = Path(pkgKeysDefault)
) {
    val baseUri = "https://$pkgSite/$pkgAbiString"
    val fingerPrints = loadFingerPrints(pkgKeys)

    val target = pkgCacheRoot / "var/cache/pkg/pkg.pkg"
    val storeSig = pkgCacheRoot / "var/cache/pkg/pkg.pkg.sig"
    val lockFile = pkgCacheRoot / "var/cache/pkg/pkg.lck"

    lockFile.parent?.let { p ->
        if (!p.exists()) {
            p.createDirectories()
        }
    }

    FileChannel.open(
        lockFile,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.DELETE_ON_CLOSE
    ).use { lockFileHandle ->
        lockFileHandle.lock().use {
            (target.parent)?.let { p ->
                if (!p.exists()) {
                    p.createDirectories()
                }
            }

            val sigData = URI.create("$baseUri/latest/Latest/pkg.pkg.sig")
                .also {
                    logger.trace(TraceEvent.Debug("fetching $it"))
                }
                .toURL()
                .openStream().use { it.readBytes() }
            storeSig.writeBytes(sigData)
            val sigInfo = parseSigData(sigData, fingerPrints)
            val sha256 = URI.create("$baseUri/latest/Latest/pkg.pkg")
                .also {
                    logger.trace(TraceEvent.Debug("fetching $it"))
                }
                .toURL()
                .openStream().use { `in` ->
                    val md = MessageDigest.getInstance("SHA-256")
                    target.outputStream().use { out ->
                        val buffer = ByteArray(1024 * 4)
                        while (true) {
                            val len = `in`.read(buffer)
                            if (len < 0) {
                                break
                            }
                            md.update(buffer, 0, len)
                            out.write(buffer, 0, len)
                        }
                    }
                    md.digest().joinToString("") { "%02x".format(it) }
                }
            if (!sigInfo.verify(sha256.encodeToByteArray())) {
                error("failed to verify signature for pkg.pkg")
            }
            val tarArgs = listOf(
                "tar", "-C", pkgCacheRoot.pathString,
                "-xf", target.pathString,
                "-s", "|^/||",
                "/$pkgStaticPath"
            )
            logger.trace(TraceEvent.Exec(tarArgs))
            val rc = ProcessBuilder(tarArgs).inheritIO().start().waitFor()
            if (rc != 0) {
                (pkgCacheRoot / pkgStaticPath).deleteIfExists()
                error("failed to extract pkg.pkg: " +
                        "tar terminated with exit code $rc")
            }
        }
    }
}

private data class FingerPrints(
    val trusted: Set<String>,
    val revoked: Set<String>
)

private fun loadFingerPrints(path: Path): FingerPrints {
    val trusted = (path / "trusted").listDirectoryEntries().mapNotNull { f ->
        f.takeIf { it.isReadable() }?.let(::loadFingerPrint)
    }
    val revoked = (path / "revoked").listDirectoryEntries().mapNotNull { f ->
        f.takeIf { it.isReadable() }?.let(::loadFingerPrint)
    }
    return FingerPrints(
        trusted = trusted.toSet(),
        revoked = revoked.toSet()
    )
}

private fun loadFingerPrint(path: Path): String {
    var function = ""
    var fingerPrint = ""
    path.useLines { lines ->
        for (line in lines) {
            val lineTr = line.trim()
            if (lineTr.startsWith("function:")) {
                function = Json.decodeFromString<String>(
                    lineTr.substringAfter(':')
                ).trim()
            } else if (line.startsWith("fingerprint:")) {
                fingerPrint = Json.decodeFromString<String>(
                    lineTr.substringAfter(':')
                ).trim()
            } else if (line.isNotBlank()) {
                error("unrecognized line: $line")
            }
        }
    }
    require(function == "sha256" || function.isBlank()) {
        "unsupported fingerprint function: $function"
    }
    require(fingerPrint.matches(Regex("[0-9a-f]{64}"))) {
        "unrecognized sha256 fingerprint: $fingerPrint"
    }
    return fingerPrint
}

private class SigData(
    val publicKey: PublicKey,
    val signature: ByteArray,
    val publicKeyPem: String,
    val publicKeySha256Fp: String,
) {
    fun verify(input: ByteArray): Boolean {
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update(input)
        return verifier.verify(signature)
    }
}

private fun parseSigData(
    sigData: ByteArray,
    fingerPrints: FingerPrints
): SigData {
    require(fingerPrints.trusted.isNotEmpty()) {
        "cannot verify signature: no trusted keys provided"
    }
    val prefix = "SIGNATURE\n"
    require(sigData.decodeToString(0, prefix.length) == prefix) {
        "invalid signature"
    }
    val certStart = "\nCERT\n"
    var i = prefix.length
    while (i < sigData.size) {
        if (sigData[i] == certStart.first().code.toByte()) {
            val test = try {
                sigData.decodeToString(i, i + certStart.length)
            } catch (_: Exception) {
                null
            }
            if (test == certStart) {
                break
            }
        }
        i++
    }
    require(i + certStart.length < sigData.size) { "invalid signature" }
    val s = sigData.decodeToString(i + certStart.length, sigData.size).trim()
    val pkPrefix = "-----BEGIN PUBLIC KEY-----\n"
    val pkSuffix = "-----END PUBLIC KEY-----\nEND"
    require(s.startsWith(pkPrefix) && s.endsWith(pkSuffix)) {
        "invalid signature"
    }
    val base64 = s.substring(pkPrefix.length, s.length - pkSuffix.length)
    val pem = "$pkPrefix$base64${pkSuffix.removeSuffix("END")}"
    val fingerPrint = MessageDigest.getInstance("SHA-256").let { md ->
        md.update(pem.encodeToByteArray())
        md.digest().joinToString("") { "%02x".format(it) }
    }
    require(fingerPrint !in fingerPrints.revoked) {
        "signature key '$fingerPrint' is revoked"
    }
    require(fingerPrint in fingerPrints.trusted) {
        "signature key '$fingerPrint' not trusted"
    }
    val kf = KeyFactory.getInstance("RSA")
    val pk = base64.trim()
        .let { Base64.getMimeDecoder().decode(it) }
        .let(::X509EncodedKeySpec)
        .let(kf::generatePublic)
    val signature = sigData.copyOfRange(prefix.length, i)
    return SigData(
        publicKey = pk,
        signature = signature,
        publicKeyPem = pem,
        publicKeySha256Fp = fingerPrint
    )
}
