package com.wangzan101.mika.about

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.net.NetProxy
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Result of this manual update check; automatic checks do not touch it (a failure stays quiet). */
enum class CheckStatus { Idle, Checking, Done, Failed }

/**
 * Update check state. [latest] is the most recently found version (used for update history), [update] is the one newer than the
 * current version (only that shows a badge).
 * [manualRounds] increases by one for every completed manual check, which is how the screen shows messages like "already up to date" or "cannot connect".
 */
data class UpdateState(
    val latest: ReleaseInfo? = null,
    val update: ReleaseInfo? = null,
    val status: CheckStatus = CheckStatus.Idle,
    val manualRounds: Int = 0,
) {
    val hasUpdate: Boolean get() = update != null
}

/**
 * Checks once every time the app opens, only suppressing back-to-back ones: if less than [MIN_GAP_MS] has passed since the last
 * automatic check (switching back and forth a few times, rotating the screen) it does not check again.
 * A last-attempt time later than now (the system clock was changed) also counts as due.
 */
object UpdateSchedule {
    const val MIN_GAP_MS = 2 * 60 * 1000L

    fun due(lastAttempt: Long?, now: Long): Boolean = lastAttempt == null || now - lastAttempt >= MIN_GAP_MS || now < lastAttempt
}

/**
 * Checks for updates with no server of its own: it talks to the GitHub releases API directly, and when mirrors are enabled it also
 * asks the mirrors in the user's order;
 * being unable to connect, rate limiting and unparseable responses all count as "not found this time". When a mirror does not support
 * the API it falls back to the release page for the tag and asset information.
 * The found version and the time of the last automatic check are stored in [file], so the badge is still there after the app is
 * reopened; once a newer version is installed the current version is no longer behind and the badge disappears by itself.
 * This only checks versions; user-initiated download and install are handled by [ApkUpdater].
 */
class UpdateChecker(
    private val file: File,
    private val currentVersion: String,
    private val client: OkHttpClient = defaultClient(),
    private val releaseApi: String = MikaLinks.LATEST_RELEASE_API,
    private val releasesUrl: String = MikaLinks.RELEASES,
    /** Current mirror settings; read once per check, so changing the order mid-check does not affect that check. */
    private val mirrorSettings: () -> MirrorSettings = { MirrorSettings.OFF },
    private val now: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** ABIs this device supports (Build.SUPPORTED_ABIS), used to pick the right APK from a release; when empty the all-architecture package is taken. */
    private val deviceAbis: List<String> = emptyList(),
) {
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var lastAttempt: Long? = null
    private var loaded = false
    // Record the source of the current result; even across restarts a mirror digest must not be treated as an official digest.
    private var latestOfficial = false

    /** Called every time the app opens: checks once in the background when due, silently on failure. */
    fun autoCheckInBackground() = scope.launch { autoCheck() }

    /** The user tapped "Check for updates". */
    fun checkInBackground() {
        scope.launch { check(manual = true) }
    }

    /** Reads the stored result (once only) so the badge does not have to wait for the network. */
    suspend fun load() = mutex.withLock { loadLocked() }

    /**
     * Checks once when due and returns whether a check actually ran. The time is recorded before checking starts: a second call
     * arriving at the same time (while the check is still running) sees the time just recorded,
     * and does not check again.
     */
    suspend fun autoCheck(): Boolean {
        val due = mutex.withLock {
            loadLocked()
            UpdateSchedule.due(lastAttempt, now()).also { if (it) lastAttempt = now() }
        }
        if (due) check(manual = false)
        return due
    }

    suspend fun check(manual: Boolean) {
        mutex.withLock {
            loadLocked()
            if (manual) _state.update { it.copy(status = CheckStatus.Checking) }
            var foundAny = false
            var officialFound = false
            var manualCompleted = false
            fun completeManual(status: CheckStatus) {
                if (manual && !manualCompleted) {
                    manualCompleted = true
                    _state.update { it.copy(status = status, manualRounds = it.manualRounds + 1) }
                }
            }
            try {
                fetchLatest(mirrorSettings()) { found, official ->
                    foundAny = true
                    if (official) {
                        officialFound = true
                        val previous = _state.value.latest
                        val result = if (previous != null && AppVersion.compare(found.version, previous.version) == 0) {
                            mergeRelease(previous, found, previousOfficial = latestOfficial, candidateOfficial = true)
                        } else found
                        latestOfficial = true
                        _state.update { it.withLatest(result) }
                    } else if (!officialFound) {
                        val previous = _state.value.latest
                        val comparison = previous?.let { AppVersion.compare(found.version, it.version) } ?: 1
                        if (comparison > 0) {
                            latestOfficial = false
                            _state.update { it.withLatest(found) }
                        } else if (comparison == 0 && previous != null) {
                            val result = mergeRelease(previous, found, previousOfficial = latestOfficial, candidateOfficial = false)
                            _state.update { it.withLatest(result) }
                        }
                    }
                    if (_state.value.hasUpdate) completeManual(CheckStatus.Done)
                    save()
                }
                completeManual(if (foundAny) CheckStatus.Done else CheckStatus.Failed)
            } finally {
                // Cancelling the check must also clear the manual waiting state, so the screen does not spin forever.
                completeManual(if (foundAny) CheckStatus.Done else CheckStatus.Failed)
                save()
            }
        }
    }

    /** Queries all sources in parallel; results are handed to the same coroutine in arrival order. */
    private suspend fun fetchLatest(settings: MirrorSettings, onResult: (ReleaseInfo, Boolean) -> Unit) = coroutineScope {
        val sources = buildList {
            add("")
            if (settings.enabled) settings.mirrors.forEach { add(it.prefix) }
        }
        val results = Channel<Pair<ReleaseInfo?, Boolean>>(sources.size)
        sources.forEach { source ->
            launch(Dispatchers.IO) {
                val found = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                    try {
                        UpdateProbe.fetch(client, source, releaseApi, releasesUrl, deviceAbis, "Claire/$currentVersion")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
                results.send(found to source.isEmpty())
            }
        }
        repeat(sources.size) {
            val (found, official) = results.receive()
            if (found != null) onResult(found, official)
        }
    }

    /** Fills in missing information for the same version; verification data only belongs to its own APK URL. */
    private fun mergeRelease(previous: ReleaseInfo, candidate: ReleaseInfo, previousOfficial: Boolean, candidateOfficial: Boolean): ReleaseInfo {
        val clearsGuess = candidate.apkListKnown && candidate.apkUrl == null &&
            !previous.apkListKnown && previous.apkSize == null && previous.apkDigest == null
        if (previousOfficial && !candidateOfficial) {
            return if (clearsGuess) previous.copy(apkUrl = null, apkListKnown = true) else previous
        }
        val previousPreference = Releases.apkPreference(previous.apkUrl, deviceAbis)
        val candidatePreference = Releases.apkPreference(candidate.apkUrl, deviceAbis)
        fun completeness(release: ReleaseInfo) = (if (release.apkDigest != null) 2 else 0) + (if (release.apkSize != null) 1 else 0)
        val selected = when {
            candidateOfficial && !previousOfficial && candidate.apkUrl != null -> candidate
            clearsGuess -> candidate
            previous.apkListKnown && previous.apkUrl == null && !candidate.apkListKnown -> previous
            candidate.apkUrl == null -> previous
            previous.apkUrl == null -> candidate
            candidatePreference == 0 && previousPreference > 0 -> previous
            previousPreference == 0 && candidatePreference > 0 -> candidate
            completeness(candidate) != completeness(previous) -> if (completeness(candidate) > completeness(previous)) candidate else previous
            candidatePreference != previousPreference -> if (candidatePreference > previousPreference) candidate else previous
            else -> candidate
        }
        val other = if (selected === previous) candidate else previous
        val sameApk = selected.apkUrl != null && selected.apkUrl == other.apkUrl
        val trustedSelected = !candidateOfficial || previousOfficial || selected === candidate
        val trustedOther = !candidateOfficial || previousOfficial || other === candidate
        return candidate.copy(
            name = candidate.name.takeUnless { it.isBlank() || it == candidate.version } ?: previous.name,
            // Official results take priority: when a mirror proxies the API it may return cached or wrong notes, which the official one overrides.
            notes = if (candidateOfficial && !previousOfficial) {
                candidate.notes.ifBlank { previous.notes }
            } else {
                previous.notes.ifBlank { candidate.notes }
            },
            date = candidate.date ?: previous.date,
            apkUrl = selected.apkUrl,
            apkListKnown = selected.apkListKnown,
            apkSize = selected.apkUrl?.let { candidate.apkSize.takeIf { candidateOfficial && candidate.apkUrl == selected.apkUrl } ?: selected.apkSize.takeIf { trustedSelected } ?: other.apkSize.takeIf { sameApk && trustedOther } },
            apkDigest = selected.apkUrl?.let { candidate.apkDigest.takeIf { candidateOfficial && candidate.apkUrl == selected.apkUrl } ?: selected.apkDigest.takeIf { trustedSelected } ?: other.apkDigest.takeIf { sameApk && trustedOther } },
        )
    }

    private fun UpdateState.withLatest(r: ReleaseInfo?): UpdateState =
        copy(latest = r, update = r?.takeIf { AppVersion.isNewer(it.version, currentVersion) })

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        val o = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return
        lastAttempt = (o["lastAttempt"] as? JsonPrimitive)?.longOrNull
        latestOfficial = (o["latestOfficial"] as? JsonPrimitive)?.content == "true"
        val latest = (o["latest"] as? JsonObject)?.let(Releases::fromJson)?.let {
            // Old records have no source, so their verification data cannot be treated as trusted official data.
            if (o["latestOfficial"] == null) it.copy(apkSize = null, apkDigest = null) else it
        }
        _state.update { it.withLatest(latest) }
    }

    private fun save() {
        val o = buildJsonObject {
            lastAttempt?.let { put("lastAttempt", it) }
            _state.value.latest?.let {
                put("latest", Releases.toJson(it))
                put("latestOfficial", latestOfficial)
            }
        }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(o.toString())
                tmp.delete()
            }
        }
    }

    companion object {
        /** A single source may take at most this long (a mirror may have to go "API → release page → assets page", three requests); the paths run in parallel, so the times do not add up. */
        private const val PROBE_TIMEOUT_MS = 12_000L

        /** Some networks are fast or slow when reaching GitHub: keep the timeout short and switch to a fallback source on failure. See Settings › Network proxy and update mirrors. */
        fun defaultClient(): OkHttpClient = NetProxy.shared.client(OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS))
    }
}
