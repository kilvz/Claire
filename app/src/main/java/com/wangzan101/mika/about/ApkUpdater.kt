package com.wangzan101.mika.about

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Network
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.wangzan101.mika.core.net.NetProxy
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class DownloadPhase { Idle, Downloading, Paused, Ready, Failed }

data class ApkDownloadState(
    val phase: DownloadPhase = DownloadPhase.Idle,
    val bytes: Long = 0,
    val total: Long = -1,
) {
    val percent: Int? get() = if (total > 0) ((bytes.toDouble() / total) * 100).toInt().coerceIn(0, 100) else null
}

/**
 * Manual direct downloads are handed to the system; the task id is saved so it can still be queried, cancelled or installed after a
 * process restart. Methods are called on the IO thread.
 * Files live in this app's external files directory (no other app can read or write them), only one at a time; switching version,
 * cancelling, and after install they are all cleaned up.
 * Automatic downloads and proxied manual downloads run inside Claire's process so they can bind to Wi-Fi or use the app proxy ([ProxiedApkDownload]).
 */
class ApkUpdater(
    private val context: Context,
    private val net: NetProxy = NetProxy.shared,
    /** Current mirror settings; read once when a download starts, so changing the switch or order mid-download does not affect this one. */
    private val mirrorSettings: () -> MirrorSettings = { MirrorSettings.OFF },
) {
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("apk-update", Context.MODE_PRIVATE)
    private val directory: File get() = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: error("external files unavailable"), "updates")
    private val file: File? get() = prefs.getString("file", null)?.let { File(directory, it) }
    /** This download is performed by Claire itself. */
    private val viaApp: Boolean get() = prefs.getBoolean("viaApp", false)

    @Synchronized
    fun state(release: ReleaseInfo): ApkDownloadState =
        if (prefs.getString("url", null) != release.apkUrl) ApkDownloadState() else recorded()

    /** Current status of the recorded download task; a task the system cannot find counts as failed. */
    private fun recorded(): ApkDownloadState {
        if (viaApp) return file?.let(proxied::state) ?: ApkDownloadState(DownloadPhase.Failed)
        val id = prefs.getLong("id", -1)
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (cursor.moveToFirst()) {
                fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
                val phase = when (number(DownloadManager.COLUMN_STATUS).toInt()) {
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING -> DownloadPhase.Downloading
                    DownloadManager.STATUS_PAUSED -> DownloadPhase.Paused
                    DownloadManager.STATUS_SUCCESSFUL -> if (file?.isFile == true) DownloadPhase.Ready else DownloadPhase.Failed
                    else -> DownloadPhase.Failed
                }
                return ApkDownloadState(phase, number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR), number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            }
        }
        return ApkDownloadState(DownloadPhase.Failed)
    }

    @Synchronized
    /** Automatic downloads pass the current Wi-Fi; manual downloads use the system default network. */
    fun download(release: ReleaseInfo, wifi: Network? = null) {
        val official = requireNotNull(release.apkUrl)
        ApkIntegrity.requireOfficialUrl(official)
        val settings = mirrorSettings()
        val sources = UpdateMirrors.candidates(official, settings)
        sources.forEach { ApkIntegrity.requireTrustedDownloadUrl(it, official, settings.mirrors) }
        val proxy = net.proxyFor(Uri.parse(official).host.orEmpty())
        // A download's "identity" is always the official URL: if the mirror switch or order changes, an already
        // downloaded copy of the same package need not be fetched again.
        // "Paused" does not count as in flight: the system downloader sometimes gets stuck paused and does not resume
        // by itself, so pressing download again cancels and restarts, giving the user a way to continue manually.
        if (prefs.getString("url", null) == official && state(release).phase in
            listOf(DownloadPhase.Downloading, DownloadPhase.Ready)) return
        cancel()
        val folder = directory
        check(folder.mkdirs() || folder.isDirectory)
        // Also remove old files left behind when the process was killed or the record lost, so only this attempt's file remains.
        folder.listFiles()?.forEach { it.delete() }
        // remove's background cleanup may finish after the next download starts, so the old task's file name cannot be reused.
        val target = File(folder, "update-${UUID.randomUUID()}.apk")
        // There may be more than one mirror to try within a single task; the system downloader cannot do that, so with
        // mirrors enabled Claire downloads it herself.
        if (wifi != null || proxy != null || sources.size > 1) {
            check(prefs.edit().putBoolean("viaApp", true).putString("url", official).putString("file", target.name).commit()) { "could not save download" }
            proxied.start(sources, target, wifi) {
                verifyApk(it, release)
            }
            return
        }
        val url = official
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Claire ${release.version}")
            .setMimeType("application/vnd.android.package-archive")
            // Show the notification only while downloading: tapping the completed notification would bypass the
            // verification below and open the package directly, so it is not kept.
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(target))
        val id = manager.enqueue(request)
        if (!prefs.edit().putLong("id", id).putString("url", url).putString("file", target.name).commit()) {
            manager.remove(id)
            error("could not save download")
        }
    }

    @Synchronized
    fun cancel() {
        if (viaApp) proxied.cancel()
        val id = prefs.getLong("id", -1)
        if (id != -1L) manager.remove(id)
        val target = file
        check(target == null || !target.exists() || target.delete())
        check(prefs.edit().clear().commit())
    }

    /** Once installed (or a newer version is installed) the downloaded package is useless: clear it at startup. In-progress, paused and failed downloads are left to the About screen. */
    @Synchronized
    fun dropInstalled() {
        val target = file ?: return
        if (recorded().phase != DownloadPhase.Ready) return
        val archive = archiveInfo(target, 0)
        if (archive != null && archive.longVersionCode > installedInfo(0).longVersionCode) return
        cancel()
    }

    /**
     * Accepts only a complete asset from this app, of a higher version, with the same signature; on failure the file is deleted and [ApkRejected] is thrown.
     * The system installer verifies the signature again afterwards and asks the user to confirm.
     */
    @Synchronized
    fun installIntent(release: ReleaseInfo): Intent {
        check(state(release).phase == DownloadPhase.Ready)
        ApkIntegrity.requireOfficialUrl(requireNotNull(release.apkUrl))
        val target = requireNotNull(file)
        try {
            verifyApk(target, release)
        } catch (e: ApkRejected) {
            cancel()
            throw e
        }
        val uri = if (viaApp) FileProvider.getUriForFile(context, "${context.packageName}.updatefiles", target)
            else manager.getUriForDownloadedFile(prefs.getLong("id", -1)) ?: error("missing APK")
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun verifyApk(target: File, release: ReleaseInfo) {
        ApkIntegrity.verify(target, release.apkSize, release.apkDigest)
        val archive = archiveInfo(target, SIGNING_FLAGS) ?: throw ApkRejected(ApkRejected.Reason.Corrupt)
        val installed = installedInfo(SIGNING_FLAGS)
        if (archive.packageName != context.packageName || archive.longVersionCode <= installed.longVersionCode) {
            throw ApkRejected(ApkRejected.Reason.NotNewer)
        }
        ApkIntegrity.requireReleaseVersion(archive.versionName, release.version)
        ApkIntegrity.requireSameSigner(installed.signers(), archive.signers(), archive.signerHistory())
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(target: File, flags: Int): PackageInfo? =
        context.packageManager.getPackageArchiveInfo(target.path, flags)

    @Suppress("DEPRECATION")
    private fun installedInfo(flags: Int): PackageInfo = context.packageManager.getPackageInfo(context.packageName, flags)

    /** Current signing certificates; on a few systems reading an APK does not provide signingInfo, so fall back to the legacy signatures field. */
    @Suppress("DEPRECATION")
    private fun PackageInfo.signers(): Set<String> =
        (signingInfo?.apkContentsSigners ?: signatures).orEmpty().map { ApkIntegrity.sha256(it.toByteArray()) }.toSet()

    /** Packages that rotated their signing key carry a certificate history; there is none with multiple signers. */
    private fun PackageInfo.signerHistory(): Set<String> {
        val info = signingInfo ?: return emptySet()
        if (info.hasMultipleSigners()) return emptySet()
        return info.signingCertificateHistory.orEmpty().map { ApkIntegrity.sha256(it.toByteArray()) }.toSet()
    }

    private companion object {
        /** Only one update download at a time per process. */
        val proxied by lazy {
            ProxiedApkDownload(NetProxy.shared.client(OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS)))
        }

        @Suppress("DEPRECATION")
        const val SIGNING_FLAGS = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
    }
}
