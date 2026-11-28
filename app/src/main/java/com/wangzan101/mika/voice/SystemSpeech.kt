package com.wangzan101.mika.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.wangzan101.mika.i18n.AppLanguage
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/** A recognition service registered on the phone, see [SystemSpeech.services]. [component] is the short "package/class" form. */
data class RecognizerService(val component: String, val open: Boolean, val permission: String?)

/** The phone's built-in recognition: capability detection, how error codes are classified, and the language used for recognition. */
object SystemSpeech {
    /** Google's speech app ("Speech Recognition & Synthesis", formerly "Speech Services by Google"); also the Play Store package. */
    const val SPEECH_APP_PACKAGE = "com.google.android.tts"

    /** The Google app, whose recognition service is Google's main network recognizer on most phones. */
    const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"

    /** Android System Intelligence, which hosts on-device recognition on Android 12 and newer. */
    const val SYSTEM_INTELLIGENCE_PACKAGE = "com.google.android.as"

    /**
     * Packages that can carry a Google recognition service. "Speech Recognition & Synthesis" comes first because it is
     * the smallest, purpose-built install; the Google app and Android System Intelligence also provide one on many phones.
     */
    private val SPEECH_APP_PACKAGES = listOf(SPEECH_APP_PACKAGE, GOOGLE_APP_PACKAGE, SYSTEM_INTELLIGENCE_PACKAGE)

    /**
     * A Google speech package installed on the phone, or null. Android can still offer no recognition service when the app
     * is disabled, its recognition component is turned off, or the installed build only carries TTS; then telling the
     * user to install it again is wrong, and the sheet opens the app's settings instead.
     */
    fun installedSpeechApp(context: Context): String? = SPEECH_APP_PACKAGES.firstOrNull {
        runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess
    }

    /**
     * Starting with Android 11, querying RecognitionService must be declared in the manifest, otherwise this is always unavailable.
     * [silent] is the mark that the service was tried before and did not respond (see [SpeechSettings.systemSilent]): the system can only tell whether one exists, whether it answers has to be tried.
     * A registered service is usable by naming it even when it is not the system default, so it counts as available here;
     * [SystemCapability.defaultService] records separately whether the system has a default entry (the one a null attempt asks for).
     */
    fun capability(context: Context, silent: Boolean = false): SystemCapability {
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        val online = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
        // Only ask the package manager when the system reports nothing: a registered service that is not the default
        // is still usable by name, so an installed recognizer keeps built-in recognition available.
        val installed = (online || onDevice) || services(context).any { it.open }
        return SystemCapability(
            available = installed,
            onDevice = onDevice,
            responsive = !silent,
            defaultService = SpeechServiceMic.defaultService(context)?.flattenToShortString(),
        )
    }

    /**
     * Which kind of failure a recognition service error code maps to; null means "no speech heard in this segment" (no match, nothing spoken), so reopen while holding and return the existing text after release.
     * Codes outside the list (server errors, unsupported language, vendor-specific codes and the like) are treated as the service having a problem.
     * These errors are only reported after the service is registered and recognition has started, so they must not be described as "this phone has no built-in recognition":
     * that is the verdict of the capability check ([capability]), which the settings page shows as available at the same time, and the two must agree (#795).
     */
    fun failureOf(code: Int): SpeechFailure? = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, ERROR_TOO_MANY_REQUESTS -> SpeechFailure.Busy
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER, ERROR_SERVER_DISCONNECTED -> SpeechFailure.Network
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechFailure.NoPermission
        SpeechRecognizer.ERROR_AUDIO -> SpeechFailure.AudioError
        SpeechRecognizer.ERROR_CLIENT -> SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemFailed)
        else -> SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemFailed)
    }

    /**
     * Whether on-device recognition should fall back to online recognition when it reports this code having heard nothing yet: services that failed themselves fall back (an on-device model not ready,
     * a request it does not accept and the like); network, busy, permission and microphone errors would fail the same with another service, so they do not, and "nothing heard" reopens as usual.
     */
    fun onDeviceFallsBack(code: Int): Boolean = failureOf(code) is SpeechFailure.Unavailable

    /** What to do once an error code arrives: reopen, wrap up with what was heard, or report a failure. */
    sealed interface Outcome {
        data object Retry : Outcome
        /** The current entry is unusable; try the next recognition service of this session instead. */
        data object NextService : Outcome
        data object Finish : Outcome
        data class Fail(val why: SpeechFailure) : Outcome
    }

    /**
     * [holding] means the finger is still down, [heard] means some text has already been heard. Text already heard is never lost: after release, or on a mid-way error while holding, wrap up with what was heard.
     * After release some services report ERROR_CLIENT following stopListening; when nothing was heard that means no speech was recognized, not that the microphone is broken.
     */
    fun afterError(code: Int, holding: Boolean, heard: Boolean, canTryNext: Boolean = false): Outcome {
        val failure = failureOf(code)
        return when {
            failure == null && holding -> Outcome.Retry
            failure == null || heard -> Outcome.Finish
            !holding && code == SpeechRecognizer.ERROR_CLIENT -> Outcome.Finish
            code == SpeechRecognizer.ERROR_CLIENT && canTryNext -> Outcome.NextService
            else -> Outcome.Fail(failure)
        }
    }

    /**
     * What to do when the recognition service gives no callback at all within [SystemSpeechSession.NO_RESPONSE_MS] after listening starts.
     * [answered] means this recognizer responded before (so the service is fine and only this segment got stuck): wrap up with what was heard; if nothing was heard it simply did not understand anything.
     * Never having responded means this phone's recognition service ignores requests from third-party apps and waiting longer will not produce text; report [SpeechEngineSpec.Why.SystemNoResponse].
     */
    fun afterSilence(answered: Boolean): Outcome =
        if (answered) Outcome.Finish else Outcome.Fail(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemNoResponse))

    /**
     * How to wrap up when there is still no final result after waiting another [SystemSpeechSession.FINAL_WAIT_MS] following release.
     * Same as [afterSilence]: if it responded before, wrap up with what was heard; if it never responded, it is unresponsive.
     * Doing nothing just because it never responded is not an option, otherwise the floating capsule would stay at "transcribing…".
     */
    fun afterReleaseWait(answered: Boolean): Outcome = afterSilence(answered)

    /**
     * Recognition services registered on the phone (components that declare RecognitionService). [RecognizerService.open] is whether it is open to other apps (exported);
     * [RecognizerService.permission] is the permission required to bind it. Used for diagnostics and [attempts].
     * The query is cached briefly: capability checks happen several times per press, and installing an app in the meantime is rare.
     */
    fun services(context: Context): List<RecognizerService> {
        val now = System.currentTimeMillis()
        servicesCache?.let { (at, list) ->
            if (now - at in 0..SERVICES_TTL_MS) return list
        }
        val found = runCatching {
            context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).map { r ->
                val s = r.serviceInfo
                RecognizerService(ComponentName(s.packageName, s.name).flattenToShortString(), s.exported, s.permission)
            }
        }.getOrDefault(emptyList())
        // Package visibility can hide a service from queryIntentServices on some OEM builds even with the manifest
        // query declared. Add Google's known components by package when they are installed, enabled and exported;
        // they are the recognizers this app is meant to use.
        val known = KNOWN_GOOGLE_SERVICES.mapNotNull { component -> installedService(context, component) }
        val all = (found + known).distinctBy { ComponentName.unflattenFromString(it.component) ?: it.component }
        servicesCache = now to all
        return all
    }

    @Volatile
    private var servicesCache: Pair<Long, List<RecognizerService>>? = null

    private const val SERVICES_TTL_MS = 5_000L

    /** Google's recognition components; the app names these directly when the system query comes back empty. */
    private val KNOWN_GOOGLE_SERVICES = listOf(
        "$GOOGLE_APP_PACKAGE/com.google.android.voicesearch.serviceapi.GoogleRecognitionService",
        "$SPEECH_APP_PACKAGE/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService",
        "$SYSTEM_INTELLIGENCE_PACKAGE/com.google.android.apps.miphone.aiai.app.AiAiSpeechRecognitionService",
    )

    /** A known Google service only when it is present, enabled and exported (otherwise another app cannot bind it). */
    private fun installedService(context: Context, component: String): RecognizerService? = runCatching {
        val name = ComponentName.unflattenFromString(component) ?: return null
        val info = context.packageManager.getServiceInfo(name, 0)
        if (!info.exported || !info.enabled) return null
        if (!context.packageManager.getApplicationInfo(name.packageName, 0).enabled) return null
        RecognizerService(ComponentName(info.packageName, info.name).flattenToShortString(), true, info.permission)
    }.getOrNull()

    /**
     * The recognition services tried in order during one session; null is the system default one (not named explicitly, left to the system to pick).
     * One previously tried by name that responded ([remembered], still on the phone) comes first; then the system entry, then the other registered services, Google's first.
     * [hasSystemEntry] is false when the phone reports no default service (and no on-device entry): asking for "the default" would only wait for something that is not there,
     * so the installed services are named directly instead.
     * With a system entry and a single registered service, only the default is tried (that service is presumably it); in compatibility mode the named candidate is kept anyway.
     * At most [MAX_ATTEMPTS] are tried, so the person holding the button does not wait for nothing.
     */
    fun attempts(
        services: List<RecognizerService>,
        remembered: String?,
        compatibility: Boolean = false,
        hasSystemEntry: Boolean = true,
    ): List<String?> {
        val known = remembered?.takeIf { r -> services.any { it.component == r } }
        val named = services
            .filter { it.component != known }
            .sortedWith(compareBy({ googleRank(it) }, { !it.open }))
            .map { it.component }
        // With a single service and a system entry, that service is the default: try only the default instead of naming it and waiting again for nothing.
        val others = if (hasSystemEntry && services.size < 2 && !compatibility) emptyList() else named
        val systemEntry = if (hasSystemEntry) listOf<String?>(null) else emptyList()
        return (listOfNotNull(known) + systemEntry + others).take(MAX_ATTEMPTS).ifEmpty { listOf(null) }
    }

    /** Google's recognizers are preferred among named candidates, whichever app currently hosts them. */
    private fun googleRank(service: RecognizerService): Int = when {
        service.component.startsWith("$GOOGLE_APP_PACKAGE/") -> 0
        service.component.startsWith("$SPEECH_APP_PACKAGE/") -> 1
        service.component.startsWith("$SYSTEM_INTELLIGENCE_PACKAGE/") -> 2
        else -> 3
    }

    /**
     * Enable the recognition service compatibility path only for verified device families, without whitelisting individual models.
     * It is identified via the public manufacturer and incremental build number, without reading or modifying the system's default recognition settings; other devices still use the original call path.
     */
    fun serviceCompatibility(
        manufacturer: String = Build.MANUFACTURER,
        incremental: String = Build.VERSION.INCREMENTAL,
        sdk: Int = Build.VERSION.SDK_INT,
    ): Boolean = sdk >= 34 && manufacturer.trim().lowercase(Locale.ROOT) in setOf("xiaomi", "redmi", "poco") &&
        Regex("""^OS[0-9]+\..*""").matches(incremental.trim())

    /** How many recognition services one session may try (including the default). */
    const val MAX_ATTEMPTS = 3

    /** On-device recognition says this language is unavailable (language pack not installed, etc.): switch to network recognition and try again. */
    internal fun languageMissing(code: Int) = code == ERROR_LANGUAGE_NOT_SUPPORTED || code == ERROR_LANGUAGE_UNAVAILABLE

    /** The recognition language (BCP-47) for the app language; when following the system, use the system language. When the selected language matches the system language (except Chinese), keep the system's region. */
    fun speechTag(language: AppLanguage, system: Locale = Locale.getDefault()): String = when {
        language == AppLanguage.System -> languageRegion(system)
        // Chinese takes its region from the selected simplified/traditional variant, not from the system
        language.language == "zh" -> language.speechTag!!
        // When the system uses this language too, use the system region (British English, Mexican Spanish, …), otherwise use this language's most common region.
        // After picking a language in the app the process default locale is just that language without a region (en): there is then no region to inherit, and sending bare "en" is not accepted by recognition services
        AppLanguage.modernCode(system.language) == language.language && system.country.isNotEmpty() -> languageRegion(system)
        else -> language.speechTag!!
    }

    /**
     * Keep only "language-region": system locales often carry a script subtag (zh-Hans-CN), while recognition services generally accept only forms like zh-CN,
     * where an extra part may be treated as an unsupported language. Extensions and variants are stripped as well.
     */
    private fun languageRegion(locale: Locale): String =
        Locale.Builder().setLanguage(locale.language).setRegion(locale.country).build().toLanguageTag()

    /**
     * Among the languages installed for on-device recognition ([installed], from checkRecognitionSupport), the spelling that is the same language and region as [tag]; null if none.
     * Vendors register spellings differently: Pixel's on-device recognition registers Taiwanese Chinese as zh-Hant-TW and cmn-Hant-TW, and reports an error for zh-TW as "not installed".
     * The comparison ignores script subtags and treats cmn (Mandarin) as zh; when [tag] has no region, any spelling of the same language counts. If [tag] itself is installed, use it.
     */
    fun installedTag(tag: String, installed: List<String>): String? {
        if (tag in installed) return tag
        val (language, region) = languageAndRegion(tag)
        return installed.firstOrNull { languageAndRegion(it) == language to region }
            ?: installed.firstOrNull { region.isEmpty() && languageAndRegion(it).first == language }
    }

    private fun languageAndRegion(tag: String): Pair<String, String> {
        val locale = Locale.forLanguageTag(tag)
        val language = AppLanguage.modernCode(locale.language)
        return (if (language == "cmn") "zh" else language) to locale.country
    }

    /** The ISO-639-1 language code the transcription APIs expect. */
    fun isoCode(tag: String): String = Locale.forLanguageTag(tag).language

    // Error codes that only exist since Android 12; written as numeric values so older versions are unaffected when they never receive them
    private const val ERROR_TOO_MANY_REQUESTS = 10
    private const val ERROR_SERVER_DISCONNECTED = 11
    private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    private const val ERROR_LANGUAGE_UNAVAILABLE = 13
}

/**
 * One session of the phone's built-in recognition. [SpeechRecognizer] can only be created and called on the main thread, so all operations on it here are posted to the main thread.
 * If the recognition service ends a segment by itself while holding (final result or "nothing heard"), it is immediately reopened and the segments are joined by [Segments];
 * after release, stopListening is called and the final result is awaited for at most [FINAL_WAIT_MS]; if it does not arrive, the last partial result is used.
 * If the service gives no callback within [NO_RESPONSE_MS] after each startListening: while the finger is still down and [attempts] still has untried services, switch to the next one to listen;
 * if all have been tried, wrap up per [SystemSpeech.afterSilence]. When it never responded, [onNoResponse] is called; when it responded, [onAnswered] is called once with the service that responded
 * (null is the system default): settings count consecutive non-responses from this, and only record once enough, also remembering the one tried by name that worked.
 * When on-device recognition reports the language as unavailable, it first asks which languages are installed: if one of the same language with a different spelling exists (see [SystemSpeech.installedTag])
 * it switches to that spelling and keeps using on-device recognition, and only switches to network recognition if there is none.
 */
class SystemSpeechSession(
    context: Context,
    private val onDevice: Boolean,
    private val languageTag: String,
    private val onNoResponse: () -> Unit = {},
    private val onAnswered: (String?) -> Unit = {},
    /** The recognition services tried in order, see [SystemSpeech.attempts]; null is the system default. */
    private val attempts: List<String?> = listOf(null),
    /** Off by default: enabled only by the entry point for devices confirmed to need compatibility. */
    private val compatibility: Boolean = false,
    /**
     * Asks, when the recognition service reports no permission, whether the service used this time itself lacks the recording permission. It is given the package name of the service named this time (null if not named)
     * and whether on-device recognition is used; it returns it only when it is determined to lack the permission (see [SpeechServiceMic.missing]), otherwise null.
     */
    private val serviceWithoutMic: (String?, Boolean) -> SpeechServiceTarget? = { _, _ -> null },
) : SpeechSession {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val out = SessionEvents()
    private val segments = Segments()
    override val events: Flow<SpeechEvent> = out.flow
    override val live = true

    // The following are only read and written on the main thread
    private var recognizer: SpeechRecognizer? = null
    private var serviceLease: SpeechServiceLease? = null
    private var bindingPending = false
    /** Invalidates stale connection callbacks when the recognizer changes or the session ends. */
    private var generation = 0L
    private var usingOnDevice = false
    /** The language sent to the recognition service this time; switched to that spelling when on-device recognition has another one installed, see [retag]. */
    private var tag = languageTag
    /** Whether this session has already asked on-device recognition which languages are installed; asked only once. */
    private var retagged = false
    /** Currently waiting for on-device recognition to answer which languages are installed. */
    private var asking = false
    /** Index into [attempts] currently being tried. */
    private var attempt = 0
    private var holding = true
    private val restarts = Restarts()
    private var restartPending = false
    /** Whether the current recognizer has responded (determines how to wrap up); errors are not remembered as the preferred service for next time based on this. */
    private var answered = false
    /** Whether [onAnswered] has already been reported for the current recognizer; reported only once per recognizer, recorded again after switching, so settings are not written on every volume callback. */
    private var reported = false
    /** Whether this session has already recorded one non-response. The post-release wait and the silence timer may fire one after the other, so it must not be recorded twice. */
    private var notedSilent = false
    // Wait another [FINAL_WAIT_MS] after release. If it responded, wrap up with what was heard; it must end even with no callbacks at all, never stay at "transcribing…"
    private val timeout = Runnable { concludeRelease() }
    private val silence = Runnable {
        if (done()) return@Runnable
        if (!answered && holding && attempt + 1 < attempts.size) {
            // This service gave no callback at all: the finger is still down, switch to the next service and keep listening
            attempt++
            open(preferOnDevice = false)
            return@Runnable
        }
        concludeSilence()
    }

    /** The extra binding also needs a wait limit; cancelling/releasing must not leave a session waiting for a connection. */
    private val bindingTimeout = Runnable {
        if (!bindingPending || done()) return@Runnable
        bindingPending = false
        if (holding && attempt + 1 < attempts.size) {
            attempt++
            open(preferOnDevice = false)
        } else concludeSilence()
    }

    /** The silence timer fired and no further service will be switched to: wrap up according to whether it responded. A non-response is recorded only once per session. */
    private fun concludeSilence() {
        if (done()) return
        when (val o = SystemSpeech.afterSilence(answered)) {
            is SystemSpeech.Outcome.Fail -> {
                noteSilent()
                finish(SpeechEvent.Failed(o.why))
            }
            else -> finish(SpeechEvent.Final(segments.full()))
        }
    }
    // Asked on-device recognition which languages are installed and no answer by the deadline: stop waiting, switch to network recognition
    private val askTimeout = Runnable {
        if (!asking || done()) return@Runnable
        asking = false
        toOnline()
    }
    private val restart = Runnable {
        restartPending = false
        if (done()) return@Runnable
        if (holding) listen() else concludeRelease()
    }

    /** The post-release wait expired, or a reopen found the finger already lifted: this session must end. */
    private fun concludeRelease() {
        if (done()) return
        when (val o = SystemSpeech.afterReleaseWait(answered)) {
            is SystemSpeech.Outcome.Fail -> {
                noteSilent()
                finish(SpeechEvent.Failed(o.why))
            }
            else -> finish(SpeechEvent.Final(segments.full()))
        }
    }

    private fun noteSilent() {
        if (notedSilent) return
        notedSilent = true
        onNoResponse()
    }

    override fun release() = onMain {
        if (!holding || done()) return@onMain
        holding = false
        if (bindingPending) {
            // Not connected yet, so not listening yet: do not suddenly start recording after release.
            finish(SpeechEvent.Final(segments.full()))
            return@onMain
        }
        if (restartPending) {
            // The previous segment already ended and has not been reopened: nothing is listening, so wrap up directly with what was heard
            finish(SpeechEvent.Final(segments.full()))
            return@onMain
        }
        try {
            // Nothing is listening while on-device recognition is being asked which languages are installed; stopping is deferred until it listens again afterwards, see [afterSwitch]
            if (!asking) recognizer?.stopListening()
        } catch (_: RuntimeException) {
            handleError(SpeechRecognizer.ERROR_CLIENT)
        } finally {
            // A synchronous callback inside stopListening may already have ended the session or removed the wait; if not ended, always schedule it again
            if (!done()) main.postDelayed(timeout, FINAL_WAIT_MS)
        }
    }

    override fun cancel() {
        // Close the event outlet first (takes effect immediately on any thread), then clean up the recognizer on the main thread
        if (!out.cancel()) return
        onMain {
            try { recognizer?.cancel() } catch (_: RuntimeException) {
                // The extra binding must be released even if cancelling fails.
            } finally { teardown() }
        }
    }

    private fun done() = out.finished || out.cancelled

    private fun open(preferOnDevice: Boolean) {
        if (done()) return
        teardown()
        answered = false
        reported = false
        val thisGeneration = generation
        val local = if (preferOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
        } else null
        usingOnDevice = local != null
        if (local != null) {
            startRecognizer(local)
            return
        }
        val named = attempts.getOrNull(attempt)?.let(ComponentName::unflattenFromString)
        fun begin() {
            if (done() || generation != thisGeneration) return
            val rec = runCatching { SpeechRecognizer.createSpeechRecognizer(context, named) }.getOrNull()
            if (rec == null) handleError(SpeechRecognizer.ERROR_CLIENT) else startRecognizer(rec)
        }
        // The default entry is still bound by the system. For public services named on the compatibility path, the caller's existing foreground capability is carried for the duration of this voice session;
        // listening starts only once the connection is established, the default service is not changed, permissions are not granted on the user's behalf, and teardown unbinds it at the end.
        val lease = if (compatibility && named != null) SpeechServiceLease.forService(context, named,
            onReady = {
                if (generation == thisGeneration && !done() && bindingPending) {
                    bindingPending = false
                    main.removeCallbacks(bindingTimeout)
                    begin()
                }
            },
            onUnavailable = {
                if (generation == thisGeneration && !done()) {
                    bindingPending = false
                    main.removeCallbacks(bindingTimeout)
                    handleError(SpeechRecognizer.ERROR_CLIENT)
                }
            },
        ) else null
        if (lease != null) {
            serviceLease = lease
            bindingPending = true
            main.postDelayed(bindingTimeout, NO_RESPONSE_MS)
            if (lease.start() || done() || generation != thisGeneration) return
            serviceLease = null
            bindingPending = false
            main.removeCallbacks(bindingTimeout)
            lease.close()
        }
        begin()
    }

    private fun startRecognizer(rec: SpeechRecognizer) {
        recognizer = rec
        try {
            rec.setRecognitionListener(listenerFor(rec))
            listen()
        } catch (_: RuntimeException) {
            handleError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    /** An initialization failure may switch to another recognition service while the finger is down; text already heard and wrapping up after release take priority, and recording is never started again. */
    private fun handleError(error: Int) {
        if (done()) return
        if (usingOnDevice && SystemSpeech.languageMissing(error) && segments.full().isEmpty()) {
            heard(remember = false)
            if (!retag()) toOnline()
            return
        }
        if (usingOnDevice && holding && segments.full().isEmpty() && SystemSpeech.onDeviceFallsBack(error)) {
            android.util.Log.i(TAG, "on-device recognition failed with code $error; trying online recognition")
            heard(remember = false)
            toOnline()
            return
        }
        val outcome = SystemSpeech.afterError(error, holding, heard = segments.full().isNotEmpty(),
            canTryNext = attempt + 1 < attempts.size)
        if (outcome == SystemSpeech.Outcome.NextService) {
            attempt++
            open(preferOnDevice = false)
            return
        }
        // An error callback alone does not prove the service is usable, so the component must not be remembered as the preferred service for next time based on it.
        heard(remember = SystemSpeech.failureOf(error) == null)
        when (outcome) {
            SystemSpeech.Outcome.Retry -> emptySegment()
            SystemSpeech.Outcome.Finish -> finish(SpeechEvent.Final(segments.full()))
            is SystemSpeech.Outcome.Fail -> finish(SpeechEvent.Failed(explained(outcome.why)))
            SystemSpeech.Outcome.NextService -> Unit
        }
    }

    /**
     * The recognition service reports no permission: Claire's recording permission was checked before the session started, so it is most likely the recognition service itself that lacks it. If that can be determined, say so truthfully;
     * if not, keep the original wording. It must be asked before tearing down the recognizer: only then is it known which entry was used this time.
     */
    private fun explained(why: SpeechFailure): SpeechFailure {
        if (why != SpeechFailure.NoPermission) return why
        val named = attempts.getOrNull(attempt)?.let(ComponentName::unflattenFromString)?.packageName
        return serviceWithoutMic(named, usingOnDevice)?.let { SpeechFailure.ServiceNoPermission(it) } ?: why
    }

    private fun request(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // For network recognition: if the phone supports on-device recognition, ask the service to prefer offline recognition
            if (!usingOnDevice && onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    private fun listen() {
        val intent = request()
        main.removeCallbacks(silence)
        main.postDelayed(silence, NO_RESPONSE_MS)
        try {
            recognizer?.startListening(intent)
        } catch (_: RuntimeException) {
            handleError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    /**
     * On-device recognition reports this language as unavailable: ask which languages are installed (Android 13+), and if one of the same language has a different spelling, switch to it and listen again.
     * A false return means the question cannot be asked (system too old, already asked in this session, the call failed), and the caller switches to network recognition.
     */
    private fun retag(): Boolean {
        val rec = recognizer ?: return false
        if (retagged || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        retagged = true
        val callback = object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) = answer(support.installedOnDeviceLanguages)
            override fun onError(error: Int) = answer(emptyList())

            private fun answer(installed: List<String>) {
                if (!asking || recognizer !== rec || done()) return
                asking = false
                main.removeCallbacks(askTimeout)
                val other = SystemSpeech.installedTag(tag, installed)
                if (other == null || other == tag) {
                    toOnline()
                    return
                }
                tag = other
                listen()
                afterSwitch()
            }
        }
        asking = runCatching { rec.checkRecognitionSupport(request(), context.mainExecutor, callback) }.isSuccess
        if (asking) main.postDelayed(askTimeout, NO_RESPONSE_MS)
        return asking
    }

    /** On-device recognition cannot use this language: switch to network recognition and send the language in its original spelling. */
    private fun toOnline() {
        tag = languageTag
        open(preferOnDevice = false)
        afterSwitch()
    }

    /** After listening again with a different recognizer or language spelling: if already released, only wrap up, and the wait limit is restarted. */
    private fun afterSwitch() {
        if (holding || done()) return
        recognizer?.stopListening()
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, FINAL_WAIT_MS)
    }

    private fun finish(e: SpeechEvent) {
        main.removeCallbacks(timeout)
        out.finish(e)
        teardown()
    }

    /** A segment came back empty while holding: [Restarts] decides how long to wait before reopening, or to stop reopening and wrap up with what was heard. Reopening is always posted to the main thread for later, never done directly inside the callback. */
    private fun emptySegment() {
        when (val d = restarts.onEmpty(android.os.SystemClock.elapsedRealtime())) {
            Restarts.Decision.GiveUp -> finish(SpeechEvent.Final(segments.full()))
            is Restarts.Decision.Restart -> {
                restartPending = true
                main.postDelayed(restart, d.delayMs)
            }
        }
    }

    /** The service responded: this segment does not count as a non-response. */
    private fun heard(remember: Boolean = true) {
        answered = true
        main.removeCallbacks(silence)
        if (remember && !reported) {
            reported = true
            onAnswered(if (usingOnDevice) null else attempts.getOrNull(attempt))
        }
    }

    private fun teardown() {
        generation++
        bindingPending = false
        main.removeCallbacks(bindingTimeout)
        main.removeCallbacks(timeout)
        main.removeCallbacks(silence)
        main.removeCallbacks(restart)
        main.removeCallbacks(askTimeout)
        asking = false
        restartPending = false
        val rec = recognizer
        recognizer = null
        // Detach first, then destroy: destroy may synchronously call back onError, and the engine must not be switched at that point, otherwise a reopen would nest inside the failure handling
        usingOnDevice = false
        rec?.let { runCatching { it.destroy() } }
        val lease = serviceLease
        serviceLease = null
        lease?.close()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun firstText(results: Bundle?): String =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun listenerFor(rec: SpeechRecognizer) = object : RecognitionListener {
        // A default entry may report two client errors in a row; stale callbacks after a switch must not end or pollute the new recognizer.
        private fun current() = recognizer === rec && !done()
        override fun onReadyForSpeech(params: Bundle?) { if (current()) heard() }
        override fun onBeginningOfSpeech() { if (current()) heard() }
        override fun onBufferReceived(buffer: ByteArray?) { if (current()) heard() }
        override fun onEndOfSpeech() { if (current()) heard() }
        override fun onEvent(eventType: Int, params: Bundle?) { if (current()) heard() }

        override fun onRmsChanged(rmsdB: Float) {
            if (!current()) return
            heard()
            // The recognition service reports roughly -2..10 dB; compress to 0..1 for the waveform
            out.send(SpeechEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!current()) return
            heard()
            val text = firstText(partialResults)
            if (text.isBlank()) return
            restarts.onSpeech()
            out.send(Segments.split(segments.partial(text)))
        }

        override fun onResults(results: Bundle?) {
            if (!current()) return
            heard()
            val text = firstText(results)
            segments.segmentDone(text)
            if (holding) {
                // The recognition service thinks the sentence is finished, but the finger is still down: keep listening for the next segment
                if (text.isBlank()) {
                    emptySegment()
                } else {
                    restarts.onSpeech()
                    out.send(Segments.split(segments.full()))
                    restartPending = true
                    main.post(restart)
                }
            } else {
                finish(SpeechEvent.Final(segments.full()))
            }
        }

        override fun onError(error: Int) {
            if (!current()) return
            handleError(error)
        }
    }

    // Placed after all properties: onMain runs immediately on the main thread, so the timers and other properties used by open are already initialized at this point
    init {
        onMain { open(onDevice && attempts.firstOrNull() == null) }
    }

    companion object {
        private const val TAG = "ClaireSystemSpeech"

        /** Upper bound for waiting for the final result after release. */
        const val FINAL_WAIT_MS = 2_000L

        /**
         * Upper bound for waiting for the recognition service's first callback after listening starts (usually onReadyForSpeech, normally arriving within a few hundred milliseconds).
         * Leaves room for a cold start of the recognition service process; beyond this the segment counts as a non-response.
         */
        const val NO_RESPONSE_MS = 3_000L
    }
}
