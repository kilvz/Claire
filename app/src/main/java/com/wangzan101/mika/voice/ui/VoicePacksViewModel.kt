package com.wangzan101.mika.voice.ui

import android.content.Context
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.about.DownloadPhase
import com.wangzan101.mika.about.UpdateMirrorStore
import com.wangzan101.mika.download.DownloadProgress
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.voice.ondevice.OnDeviceCatalog
import com.wangzan101.mika.voice.ondevice.OnDeviceLanguage
import com.wangzan101.mika.voice.ondevice.VoicePackCatalog
import com.wangzan101.mika.voice.ondevice.VoicePackEntry
import com.wangzan101.mika.voice.ondevice.VoicePackInstaller
import com.wangzan101.mika.voice.ondevice.VoicePackInstaller.ImportInput
import com.wangzan101.mika.voice.ondevice.VoicePackInstaller.ImportResult
import com.wangzan101.mika.voice.ondevice.VoicePackInstaller.Preflight
import com.wangzan101.mika.voice.ondevice.VoicePackStatus
import com.wangzan101.mika.voice.ondevice.VoicePackStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One voice-pack card. [progress] is present only while downloading or after a failed download (not yet installed); [importing] means an import from files is running.
 */
data class VoicePackCard(
    val entry: VoicePackEntry,
    val status: VoicePackStatus,
    val progress: DownloadProgress?,
    val recommended: Boolean,
    val importing: Boolean = false,
)

/** Something the user must confirm before downloading. */
sealed interface VoicePackPrompt {
    /** Metered network: state the size and download only after the user confirms. */
    data class Metered(val entry: VoicePackEntry, val bytes: Long) : VoicePackPrompt
}

/** The one-line result notice above the cards (the latest one). */
sealed interface VoicePackNotice {
    data class NoSpace(val needed: Long, val free: Long) : VoicePackNotice
    data object Unsupported : VoicePackNotice
    /** A download or import is already running, or no source is usable. */
    data object NotStarted : VoicePackNotice
    data class Imported(val result: ImportResult) : VoicePackNotice
    data object DeleteFailed : VoicePackNotice
    data object Copied : VoicePackNotice
    /** The "download through mirrors" switch could not be saved. */
    data object MirrorSaveFailed : VoicePackNotice
}

/** Install operations the cards need; in the app these are [VoicePackInstaller] plus [VoicePackStore], replaced by fakes in tests. */
interface VoicePackOps {
    /** Packs from the manifest (engines and model families this build knows). */
    val packs: List<VoicePackEntry>
    /** Whether this device can run the pack (the engine knows the family and the CPU architecture is supported). */
    fun runnable(entry: VoicePackEntry): Boolean
    fun status(entry: VoicePackEntry): VoicePackStatus
    fun preflight(entry: VoicePackEntry): Preflight
    fun download(entry: VoicePackEntry): Boolean
    fun progress(entry: VoicePackEntry): StateFlow<DownloadProgress>
    fun cancel(entry: VoicePackEntry)
    suspend fun delete(entry: VoicePackEntry)
    suspend fun import(entry: VoicePackEntry, inputs: List<ImportInput>): ImportResult
    fun downloadUrls(entry: VoicePackEntry): String
    /** Download through mirrors first (the same setting as "Use mirrors for downloads" on the About page, shared by app updates and voice packs). */
    val mirrorEnabled: StateFlow<Boolean>
    /** Change this switch; returns false if it could not be saved. Writes a file, so do not call it on the main thread. */
    fun setMirrorEnabled(on: Boolean): Boolean
}

/**
 * State and actions for the "Offline voice packs" section in settings. [recommended] is the pack recommended for the current UI language (see [OnDeviceLanguage.recommended])
 * and is listed first. When no pack can run on the device, [visible] is false and the whole section is hidden.
 * Downloads start only when the user taps: [download] goes through [VoicePackOps.preflight] first, a lack of space is only reported, and metered networks ask first ([prompt]).
 * Card status is derived from files rather than pushed: it is recomputed on every progress change, after import or deletion, and on [refresh] (the page returning to the foreground), on [io].
 */
class VoicePacksViewModel(
    private val ops: VoicePackOps,
    private val recommended: VoicePackEntry?,
    private val scope: CoroutineScope,
    private val copy: (String) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val packs = ops.packs.filter(ops::runnable)
        .sortedBy { if (it.id == recommended?.id) 0 else 1 }

    val visible: Boolean get() = packs.isNotEmpty()

    private val tick = MutableStateFlow(0)
    private val importing = MutableStateFlow(emptySet<String>())

    private val _prompt = MutableStateFlow<VoicePackPrompt?>(null)
    val prompt: StateFlow<VoicePackPrompt?> = _prompt.asStateFlow()

    private val _notice = MutableStateFlow<VoicePackNotice?>(null)
    val notice: StateFlow<VoicePackNotice?> = _notice.asStateFlow()

    val cards: StateFlow<List<VoicePackCard>> =
        if (packs.isEmpty()) MutableStateFlow(emptyList())
        else combine(combine(packs.map(ops::progress)) { it.toList() }, tick, importing) { progress, _, busy -> progress to busy }
            .map { (progress, busy) -> build(progress, busy) }
            .flowOn(io)
            .stateIn(scope, SharingStarted.Eagerly, build(packs.map { ops.progress(it).value }, emptySet()))

    private fun build(progress: List<DownloadProgress>, busy: Set<String>): List<VoicePackCard> =
        packs.mapIndexed { i, entry ->
            val status = ops.status(entry)
            val p = progress[i]
            val shown = when (p.phase) {
                DownloadPhase.Downloading, DownloadPhase.Paused -> p
                // Failed download: keep showing it until installed, and allow a retry
                DownloadPhase.Failed -> p.takeIf { status != VoicePackStatus.Installed }
                DownloadPhase.Idle, DownloadPhase.Ready -> null
            }
            VoicePackCard(entry, status, shown, entry.id == recommended?.id, importing = entry.id in busy)
        }

    /** Called when the page returns to the foreground: files may have been deleted or installed elsewhere. */
    fun refresh() = tick.update { it + 1 }

    fun download(entry: VoicePackEntry) {
        _notice.value = null
        when (val p = ops.preflight(entry)) {
            Preflight.Ready -> start(entry)
            is Preflight.ConfirmMetered -> _prompt.value = VoicePackPrompt.Metered(entry, p.bytes)
            is Preflight.NoSpace -> _notice.value = VoicePackNotice.NoSpace(p.needed, p.free)
            Preflight.UnsupportedDevice -> _notice.value = VoicePackNotice.Unsupported
        }
    }

    fun confirmMetered() {
        val p = _prompt.value as? VoicePackPrompt.Metered ?: return
        _prompt.value = null
        start(p.entry)
    }

    fun dismissPrompt() {
        _prompt.value = null
    }

    private fun start(entry: VoicePackEntry) {
        if (!ops.download(entry)) _notice.value = VoicePackNotice.NotStarted
    }

    fun cancel(entry: VoicePackEntry) {
        ops.cancel(entry)
        refresh()
    }

    fun delete(entry: VoicePackEntry) {
        _notice.value = null
        scope.launch {
            try {
                ops.delete(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _notice.value = VoicePackNotice.DeleteFailed
            }
            refresh()
        }
    }

    fun import(entry: VoicePackEntry, inputs: List<ImportInput>) {
        _notice.value = null
        importing.update { it + entry.id }
        scope.launch {
            val result = try {
                ops.import(entry, inputs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImportResult.Failed(e)
            } finally {
                importing.update { it - entry.id }
            }
            _notice.value = VoicePackNotice.Imported(result)
            refresh()
        }
    }

    /** Download through mirrors first: for when GitHub is slow or unreachable. */
    val mirrorEnabled: StateFlow<Boolean> get() = ops.mirrorEnabled

    fun setMirrorEnabled(on: Boolean) {
        scope.launch(io) {
            if (!ops.setMirrorEnabled(on)) _notice.value = VoicePackNotice.MirrorSaveFailed
        }
    }

    fun copyUrls(entry: VoicePackEntry) {
        copy(ops.downloadUrls(entry))
        _notice.value = VoicePackNotice.Copied
    }

    companion object {
        /** The app instance: manifest, store and installer are the process-wide ones; the recommendation follows the current UI language. */
        fun forApp(context: Context, scope: CoroutineScope, copy: (String) -> Unit): VoicePacksViewModel {
            val ops = AppVoicePackOps.from(context)
            val recommended = ops.catalog?.let { OnDeviceLanguage.recommended(it, AppLanguage.current(context)) }
            return VoicePacksViewModel(ops, recommended, scope, copy)
        }
    }
}

/** App implementation of [VoicePackOps]. When the manifest cannot be read there are no packs (the section is hidden). */
internal class AppVoicePackOps(
    val catalog: VoicePackCatalog?,
    private val store: VoicePackStore?,
    private val environment: VoiceModelEnvironment,
    private val installer: () -> VoicePackInstaller,
    private val mirrors: UpdateMirrorStore,
) : VoicePackOps {
    override val packs: List<VoicePackEntry> = catalog?.packs.orEmpty()
    override fun runnable(entry: VoicePackEntry) = environment.runsVoicePack(entry)
    override fun status(entry: VoicePackEntry) = store?.status(entry) ?: VoicePackStatus.NotInstalled
    override fun preflight(entry: VoicePackEntry) = installer().preflight(entry)
    override fun download(entry: VoicePackEntry) = installer().download(entry)
    override fun progress(entry: VoicePackEntry) = installer().progress(entry)
    override fun cancel(entry: VoicePackEntry) = installer().cancel(entry)
    override suspend fun delete(entry: VoicePackEntry) = installer().delete(entry)
    override suspend fun import(entry: VoicePackEntry, inputs: List<ImportInput>) = installer().import(entry, inputs)
    override fun downloadUrls(entry: VoicePackEntry) = installer().downloadUrls(entry)
    override val mirrorEnabled: StateFlow<Boolean> get() = mirrors.enabled
    override fun setMirrorEnabled(on: Boolean) = mirrors.setEnabled(on) == null

    companion object {
        fun from(context: Context): AppVoicePackOps {
            val env = VoiceModelEnvironment.get(context)
            val mirrors = (context.applicationContext as MikaApp).updateMirrors.also { it.ensureLoaded() }
            return AppVoicePackOps(env.voicePackCatalogOrNull(), env.voicePacks, env, { VoicePackInstaller.get(context) }, mirrors)
        }
    }
}

/** The manifest; null when there is no voice-pack environment (tests) or the manifest cannot be read. */
internal fun VoiceModelEnvironment.voicePackCatalogOrNull(): VoicePackCatalog? = try {
    voicePackCatalog?.invoke()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** Whether this device can run [entry]: its engine exists, knows the family, and the CPU architecture is supported. */
internal fun VoiceModelEnvironment.runsVoicePack(entry: VoicePackEntry): Boolean =
    voicePackBackends?.find(entry.backend)?.let { it.supports(entry.family) && it.abiSupported() } == true

/** Whether any voice pack can run on this device: the "Offline voice packs" section, the selection-page entry and the "Download offline voice pack" prompt all key off it. */
internal fun VoiceModelEnvironment.hasRunnableVoicePacks(): Boolean =
    voicePackCatalogOrNull()?.packs?.any(::runsVoicePack) == true

/**
 * This selection is an on-device pack whose files are missing (restored from backup, deleted): mark it "download required", keep the selection, and require no re-selection after downloading.
 * Only file presence is checked (an older version still counts and remains usable), never checksums.
 */
internal fun VoiceModelEnvironment.voicePackNeedsDownload(ref: VoiceModelRef): Boolean {
    if (!OnDeviceCatalog.isOnDevice(ref)) return false
    val store = voicePacks ?: return false
    val entry = voicePackCatalogOrNull()?.entry(ref.modelId) ?: return true
    return store.installedAny(entry) == null
}
