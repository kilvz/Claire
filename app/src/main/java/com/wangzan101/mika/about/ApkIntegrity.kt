package com.wangzan101.mika.about

import com.wangzan101.mika.download.DownloadUrls
import java.io.File
import java.security.MessageDigest

/** Why an update package was rejected during verification; the UI gives a different explanation for each. */
class ApkRejected(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        /** Size or SHA-256 does not match, or the package cannot be read at all. */
        Corrupt,
        /** Not Claire, or not newer than the installed version. */
        NotNewer,
        /** The signing certificate differs from the installed Claire. */
        Signer,
    }
}

/**
 * Download URLs are accepted only for HTTPS release assets from the official repository. Later redirects (to GitHub's asset storage) are
 * handled by the system download service and are out of our reach, so every downloaded file is verified again: size and SHA-256 (only when
 * the release metadata provides them), package name, version number and signing certificate.
 */
internal object ApkIntegrity {
    fun requireOfficialUrl(url: String) = DownloadUrls.requireRelease(url, ".apk")

    /**
     * The download URL may only be an official release asset, or "built-in/user-added mirror prefix + official asset URL".
     * Mirrors are still trusted by the user; size, digest and signature are verified after download as usual, so relaxing the
     * URL does not let another package in.
     */
    fun requireTrustedDownloadUrl(url: String, officialUrl: String, mirrors: List<UpdateMirror>) =
        DownloadUrls.requireTrusted(url, officialUrl, mirrors, ".apk")

    /** When a digest is given it must be sha256 and must match; any other form also counts as a mismatch, no exceptions. */
    fun verify(file: File, size: Long?, digest: String?) {
        corruptUnless(file.isFile && file.length() > 0)
        if (size != null) corruptUnless(file.length() == size)
        if (digest != null) {
            corruptUnless(digest.matches(Regex("sha256:[0-9a-fA-F]{64}")))
            corruptUnless(sha256(file).equals(digest.substringAfter(':'), ignoreCase = true))
        }
    }

    fun requireReleaseVersion(archiveVersion: String?, releaseVersion: String) {
        corruptUnless(archiveVersion != null && AppVersion.compare(archiveVersion, releaseVersion) == 0)
    }

    /**
     * The new package's signing certificates (SHA-256 of each certificate) must match the installed ones; when the new package has
     * rotated its signing key, the installed certificate must appear in its signing history.
     * The system installer checks this too; we block it early to give a clearer message.
     */
    fun requireSameSigner(installed: Set<String>, archive: Set<String>, archiveHistory: Set<String>) {
        if (installed.isEmpty() || archive.isEmpty()) throw ApkRejected(ApkRejected.Reason.Signer)
        if (archive != installed && !archiveHistory.containsAll(installed)) throw ApkRejected(ApkRejected.Reason.Signer)
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    private fun sha256(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                sha.update(buffer, 0, count)
            }
        }
        return sha.digest().hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun corruptUnless(ok: Boolean) {
        if (!ok) throw ApkRejected(ApkRejected.Reason.Corrupt)
    }
}
