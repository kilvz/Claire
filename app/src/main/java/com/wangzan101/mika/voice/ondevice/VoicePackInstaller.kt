package com.wangzan101.mika.voice.ondevice

import android.content.Context
import android.net.ConnectivityManager
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.about.ApkIntegrity
import com.wangzan101.mika.about.MirrorSettings
import com.wangzan101.mika.about.UpdateMirrors
import com.wangzan101.mika.core.net.NetProxy
import com.wangzan101.mika.download.DownloadFile
import com.wangzan101.mika.download.DownloadProgress
import com.wangzan101.mika.download.DownloadUrls
import com.wangzan101.mika.download.VerifiedDownload
import com.wangzan101.mika.voice.model.VoiceCatalogStore
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Downloading, manual import and deletion of voice packs, called by the voice-pack cards in settings (it has no UI of its own).
 *
 * - **One operation per pack at a time**: downloads and imports exclude each other; a second request does not queue, `download` returns false and `import` returns [ImportResult.Busy];
 *   [busy] lets the UI check. Deletion is not a second request: it cancels a running download first, waits for that task to end completely (or for a running import to finish), then deletes.
 * - Downloads and imports both create staging through [VoicePackStore] and call [VoicePackStore.commit] once everything passes, then write the model into the voice catalog
 *   ([VoiceCatalogStore.installVoicePack]). Failures and cancellations call [VoicePackStore.abandon] and leave no staging; if the commit fails, the old version stays usable and the catalog is unchanged.
 *   `commit` can also throw while deleting old versions after the new one is renamed into place; check [VoicePackStore.installed] again then: if it is installed, count it as success
 *   (leftover old versions are removed by the next launch's `cleanup`).
 * - Downloads start only when the user taps ([download] is never called automatically); they use the app proxy and do not bind to Wi-Fi. The UI calls [preflight] first.
 * - Hashing and file I/O run on [io].
 */
class VoicePackInstaller(
    private val catalog: VoicePackCatalog,
    private val store: VoicePackStore,
    private val download: VerifiedDownload,
    private val mirrors: () -> MirrorSettings,
    /** Whether the current network is metered; in the product this is `ConnectivityManager.isActiveNetworkMetered()`. */
    private val metered: () -> Boolean,
    private val voiceCatalog: VoiceCatalogStore,
    private val engines: OnDeviceEngines,
    private val backends: OnDeviceBackends = OnDeviceBackends(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Result of the pre-download check, in order: unsupported device → not enough space → metered confirmation. */
    sealed interface Preflight {
        data object Ready : Preflight
        /** Metered network: tell the user [bytes] will be downloaded and call [download] after confirmation. */
        data class ConfirmMetered(val bytes: Long) : Preflight
        data class NoSpace(val needed: Long, val free: Long) : Preflight
        data object UnsupportedDevice : Preflight
    }

    /** One import input: a file the user picked (a single model file or a full zip). [name] is the display name; [open] reads from the start each time. */
    data class ImportInput(val name: String, val open: () -> InputStream)

    sealed interface ImportResult {
        data object Installed : ImportResult
        /** The picked files do not contain these files from the manifest. */
        data class Missing(val files: List<String>) : ImportResult
        /** This file's size or SHA-256 does not match the manifest. */
        data class Mismatch(val file: String) : ImportResult
        /** This pack is downloading or importing. */
        data object Busy : ImportResult
        data class NoSpace(val needed: Long, val free: Long) : ImportResult
        data object UnsupportedDevice : ImportResult
        /** Cannot read (file unreadable, corrupt zip) or cannot write. */
        data class Failed(val error: Throwable) : ImportResult
    }

    /** One lock per pack, held from the start of a download until the task ends completely, for a whole import, and while deleting. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lock(entry: VoicePackEntry) = locks.getOrPut(entry.id) { Mutex() }

    /** A download or import is running for this pack. */
    fun busy(entry: VoicePackEntry): Boolean = lock(entry).isLocked

    /**
     * The download sources for one file, in order: the user's mirrors (when enabled, in the user's order) + release address, the official release address, and the pinned upstream commit address.
     * Each passes [DownloadUrls.requireTrusted]; invalid ones (for example a mirror the user entered without HTTPS) are skipped.
     */
    fun sources(entry: VoicePackEntry, file: VoicePackFile): List<String> {
        val settings = mirrors()
        val release = catalog.releaseUrl(entry, file)
        val upstream = catalog.upstreamUrl(entry, file)
        val candidates = UpdateMirrors.candidates(release, settings).map { it to release } + (upstream to upstream)
        return candidates.filter { (url, official) ->
            try {
                DownloadUrls.requireTrusted(url, official, settings.mirrors, null)
                true
            } catch (_: IllegalArgumentException) {
                false
            }
        }.map { it.first }.distinct()
    }

    /** "Copy download address": the official release address of each file, one per line. */
    fun downloadUrls(entry: VoicePackEntry): String = entry.files.joinToString("\n") { catalog.releaseUrl(entry, it) }

    fun preflight(entry: VoicePackEntry): Preflight {
        if (!supported(entry)) return Preflight.UnsupportedDevice
        space(entry)?.let { (needed, free) -> return Preflight.NoSpace(needed, free) }
        if (metered()) return Preflight.ConfirmMetered(entry.totalBytes)
        return Preflight.Ready
    }

    /**
     * Starts a download (after [preflight], when the user taps "Download"). If the pack is already downloading or importing, does nothing and returns false;
     * also returns false when no source is usable. Watch [progress] for progress.
     *
     * On the caller's thread it only takes the pack lock and computes addresses (reading mirror settings the first time) and never touches staging: staging (which deletes previous leftovers) is created inside the download task
     * on a background thread. The lock is held until the task ends completely; abandoning staging and releasing the lock are hooked to the task's own completion ([VerifiedDownload.start]'s onEnd),
     * not to a separate coroutine, so a cancelled scope cannot miss them.
     */
    fun download(entry: VoicePackEntry): Boolean {
        val lock = lock(entry)
        if (!lock.tryLock()) return false
        var started = false
        try {
            val sources = entry.files.map { sources(entry, it) }
            if (sources.any { it.isEmpty() }) return false
            val staging = store.stagingPath(entry)
            val files = entry.files.mapIndexed { i, f ->
                DownloadFile(File(staging, f.name), sources[i], verify = { verify(it, f) }, size = f.size)
            }
            started = download.start(
                key(entry), files,
                prepare = {
                    // A fresh empty directory, registered as in use; only this locked operation touches it
                    check(store.stagingDir(entry) == staging) { "unexpected staging dir" }
                },
                onEnd = {
                    // On success staging has been renamed away and this does nothing; on failure or cancellation it clears staging and releases the registration.
                    try {
                        store.abandon(entry)
                    } finally {
                        lock.unlock()
                    }
                },
            ) { install(entry, staging) }
        } finally {
            // Did not start (no source, key held, start threw): staging was never created, so just release the lock
            if (!started) lock.unlock()
        }
        return started
    }

    fun progress(entry: VoicePackEntry): StateFlow<DownloadProgress> = download.progress(key(entry))

    /** Cancels the download; staging is removed once the task stops completely, and [busy] turns false afterwards. */
    fun cancel(entry: VoicePackEntry) = download.cancel(key(entry))

    /**
     * Imports from files the user picked: [inputs] can be individual files or zips (entries may include one `<packId>/` directory), or a mix.
     * Names are matched by manifest name; release asset names `<packId>--<fileName>` are also accepted; files not in the manifest are ignored.
     * Zip entries are accepted only as "file name" or "`<packId>/`file name"; `..`, absolute paths and other subdirectories are always skipped, so only manifest file names inside staging are ever written.
     * Each file is written at most up to its manifest size and then stops with [ImportResult.Mismatch] (zip-bomb guard). It commits only when every size and SHA-256 matches.
     */
    suspend fun import(entry: VoicePackEntry, inputs: List<ImportInput>): ImportResult {
        val lock = lock(entry)
        if (!lock.tryLock()) return ImportResult.Busy
        try {
            if (!supported(entry)) return ImportResult.UnsupportedDevice
            return withContext(io) {
                space(entry)?.let { (needed, free) -> return@withContext ImportResult.NoSpace(needed, free) }
                try {
                    val staging = store.stagingDir(entry)
                    importInto(entry, staging, inputs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Cannot open (file gone, no permission), corrupt zip, bad entry-name encoding, or cannot write
                    ImportResult.Failed(e)
                } finally {
                    // On success staging has been renamed away and this does nothing
                    runCatching { store.abandon(entry) }
                }
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * Deletes this pack: cancels a running download and waits for it to stop completely, and waits for an import to finish; then releases loaded models, deletes files and removes it from the voice catalog
     * (if it was selected, voice input returns to "Auto"). If deleting files fails it is still removed from the catalog, and the error is rethrown.
     */
    suspend fun delete(entry: VoicePackEntry) {
        download.cancel(key(entry))
        val lock = lock(entry)
        lock.lock()
        try {
            download.join(key(entry))
            engines.release(entry.id)
            withContext(io) {
                try {
                    store.abandon(entry)
                    store.delete(entry.id)
                } finally {
                    voiceCatalog.removeVoicePack(entry.id)
                }
            }
        } finally {
            lock.unlock()
        }
    }

    private suspend fun importInto(entry: VoicePackEntry, staging: File, inputs: List<ImportInput>): ImportResult {
        val manifest = entry.files.associateBy { it.name }
        val written = HashSet<String>()
        val mismatched = HashSet<String>()

        /** Writes a stream as the manifest's [file]; skips it when an already matching copy exists. */
        fun accept(file: VoicePackFile, input: InputStream) {
            if (file.name in written) return
            val target = File(staging, file.name)
            if (copyVerified(input, target, file)) {
                written += file.name
                mismatched -= file.name
            } else {
                target.delete()
                mismatched += file.name
            }
        }

        for (input in inputs) {
            coroutineContext.ensureActive()
            val direct = packFileName(input.name, entry.id, allowDir = false)?.let(manifest::get)
            if (direct != null) {
                input.open().use { accept(direct, it) }
                continue
            }
            if (!isZip(input)) continue
            ZipInputStream(input.open().buffered()).use { zip ->
                while (true) {
                    coroutineContext.ensureActive()
                    val item = zip.nextEntry ?: break
                    if (item.isDirectory) continue
                    val file = packFileName(item.name, entry.id, allowDir = true)?.let(manifest::get) ?: continue
                    accept(file, zip)
                }
            }
        }
        entry.files.firstOrNull { it.name in mismatched }?.let { return ImportResult.Mismatch(it.name) }
        val missing = entry.files.map { it.name }.filter { it !in written }
        if (missing.isNotEmpty()) return ImportResult.Missing(missing)
        install(entry, staging)
        return ImportResult.Installed
    }

    /**
     * Commits staging and writes it into the voice catalog, then releases the pack's loaded model (so an old model does not stay in memory after a version change; the next use loads the new one).
     * If the commit throws while staging is gone (the rename succeeded) and the new version is complete, deleting old versions failed afterwards and it counts as success;
     * otherwise the error is rethrown and the old version and catalog stay. When re-downloading the same version the rename can fail and the old copy is moved back, so `installed` is not empty either;
     * checking `installed` alone is not enough; check whether staging still exists.
     */
    private fun install(entry: VoicePackEntry, staging: File) {
        try {
            store.commit(entry, staging)
        } catch (e: IOException) {
            if (staging.exists() || store.installed(entry) == null) throw e
        }
        voiceCatalog.installVoicePack(entry)
        engines.release(entry.id)
    }

    /** Writes the stream into [target], stopping at the manifest size plus one byte; returns true when size and SHA-256 match. */
    private fun copyVerified(input: InputStream, target: File, file: VoicePackFile): Boolean {
        val sha = MessageDigest.getInstance("SHA-256")
        var total = 0L
        target.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val room = (file.size - total + 1).coerceAtMost(buf.size.toLong()).toInt()
                val n = input.read(buf, 0, room)
                if (n < 0) break
                total += n
                if (total > file.size) return false
                out.write(buf, 0, n)
                sha.update(buf, 0, n)
            }
        }
        return total == file.size && sha.digest().joinToString("") { "%02x".format(it) }.equals(file.sha256, ignoreCase = true)
    }

    private fun isZip(input: ImportInput): Boolean = input.open().use { s ->
        val head = ByteArray(4)
        var read = 0
        while (read < 4) {
            val n = s.read(head, read, 4 - read)
            if (n < 0) break
            read += n
        }
        read == 4 && head.contentEquals(ZIP_MAGIC)
    }

    private fun verify(file: File, manifest: VoicePackFile) = ApkIntegrity.verify(file, manifest.size, "sha256:" + manifest.sha256)

    private fun supported(entry: VoicePackEntry): Boolean =
        backends.find(entry.backend)?.let { it.supports(entry.family) && it.abiSupported() } == true

    /** Returns (needed, free) when space is short. */
    private fun space(entry: VoicePackEntry): Pair<Long, Long>? {
        val needed = entry.totalBytes + VoicePackStore.SPACE_MARGIN
        val free = store.freeBytes()
        return if (free < needed) needed to free else null
    }

    companion object {
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4b, 0x03, 0x04)

        fun key(entry: VoicePackEntry) = "voice-pack:${entry.id}"

        /**
         * Which manifest file name a user-provided name maps to: strips one `<packId>/` directory ([allowDir], only for zip entries) or a release asset's
         * `<packId>--` prefix. Any other path (`..`, absolute, deeper directories, backslashes) returns null.
         */
        internal fun packFileName(name: String, id: String, allowDir: Boolean): String? {
            val parts = name.split('/')
            val base = when {
                parts.size == 1 -> parts[0]
                allowDir && parts.size == 2 && parts[0] == id -> parts[1]
                else -> return null
            }
            val plain = base.removePrefix("$id--")
            return plain.takeIf { VoicePackCatalog.isSafeName(it) }
        }

        /**
         * Called at app start, after [VoicePackStore.cleanup] (on a background thread): registers packs whose files are present (any version) but which have no model in the voice catalog.
         * This happens when the process is killed after a commit renames the new version but before the catalog is written; without this the pack would be installed but unselectable. Registered packs are left alone and nothing is written.
         */
        fun restoreInstalled(catalog: VoicePackCatalog, store: VoicePackStore, voiceCatalog: VoiceCatalogStore) {
            voiceCatalog.restoreVoicePacks(catalog.packs.filter { store.installedAny(it) != null })
        }

        @Volatile
        private var shared: VoicePackInstaller? = null

        /** One shared instance in the app (same [VoicePackStore], manifest and voice catalog as [VoiceModelEnvironment]). */
        fun get(context: Context): VoicePackInstaller = shared ?: synchronized(this) {
            shared ?: create(context.applicationContext).also { shared = it }
        }

        private fun create(context: Context): VoicePackInstaller {
            val env = VoiceModelEnvironment.get(context)
            val app = context as MikaApp
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            return VoicePackInstaller(
                catalog = env.voicePackCatalog!!(),
                store = env.voicePacks!!,
                // Go through the app proxy (including SOCKS), no Wi-Fi binding: downloads start only when the user taps
                download = VerifiedDownload(NetProxy.shared.client(OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS))),
                mirrors = app.updateMirrors::snapshot,
                // Treat unknown network info as metered; better to ask once too often
                metered = { connectivity?.isActiveNetworkMetered ?: true },
                voiceCatalog = env.catalog,
                engines = OnDeviceEngines.shared,
            )
        }
    }
}
