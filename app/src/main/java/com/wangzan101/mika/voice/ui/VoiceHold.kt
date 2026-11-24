package com.wangzan101.mika.voice.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.GlassBackdrop
import com.wangzan101.mika.ui.components.GlassBar
import com.wangzan101.mika.ui.components.GlassBlurSupported
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.floatShadow
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.voice.HoldToTalk
import com.wangzan101.mika.voice.SpeechEngineSpec
import com.wangzan101.mika.voice.SpeechFailure
import com.wangzan101.mika.voice.SpeechServiceMic
import com.wangzan101.mika.voice.SystemSpeech
import com.wangzan101.mika.voice.VoiceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.wangzan101.mika.browser.ui.findActivity
import com.wangzan101.mika.chat.ui.ChatCopy
import com.wangzan101.mika.chat.ui.ComposerMode
import com.wangzan101.mika.chat.ui.Spinner
import com.wangzan101.mika.settings.ui.models.edit

/**
 * Push-to-talk on the conversation composer: the gesture on the input field ([holdToTalk]), the input bar that turns blue while holding ([VoiceBarCover]),
 * the "listening" card above the input bar and the "slide up to send/cancel" hint, light toasts such as "didn't catch that" ([VoiceFloat]),
 * and the explanations for "voice input not available yet" and the recording permission ([VoiceSheetHost]). All state lives in [HoldToTalk]; this file only draws and wires things up.
 */

/** Listen only after holding about this long (milliseconds); anything shorter is a tap for typing. */
private const val HOLD_MS = 300L

/** Measured from the moment the finger goes down, releasing sooner than this (milliseconds) counts as an accidental touch, nothing heard. */
private const val MIN_TOTAL_MS = 500L

/** The most one recording listens; during the last [COUNTDOWN_MS] the card counts down in its bottom-right corner. */
private const val MAX_MS = 60_000L
private const val COUNTDOWN_MS = 10_000L

/** While the "listening" card is not laid out yet: sliding the finger up this far means "release to cancel". */
private val CancelLift = 80.dp

/** The finger must slide up this far and stay inside the "listening" card for "release to send"; a slight thumb movement does not send right away. */
private val SendLift = 32.dp

/** Where the finger lands on release: cancel, send directly, or neither (the text fills the input box). */
internal enum class VoiceAim { Fill, Send, Cancel }

/**
 * Where the finger is during push-to-talk. [finger] and [card] (the "listening" card) are in root-layout coordinates, and [upPx] is how far it has moved up from the press point.
 * While the card is not laid out yet (empty), only the upward distance counts as before: past [cancelLiftPx] means cancel.
 * Once laid out: a finger above the card's top edge is cancel (the "slide up to send/cancel" pill sits there); inside the card and moved up past [sendLiftPx] sends directly;
 * everything else (still on the input bar, or between the card and the input bar) fills the input box. Only the vertical axis matters, since the card is about as wide as the screen anyway.
 */
internal fun voiceAim(finger: Offset, card: Rect, upPx: Float, cancelLiftPx: Float, sendLiftPx: Float): VoiceAim = when {
    card.isEmpty -> if (upPx > cancelLiftPx) VoiceAim.Cancel else VoiceAim.Fill
    finger.y < card.top -> VoiceAim.Cancel
    finger.y <= card.bottom && upPx > sendLiftPx -> VoiceAim.Send
    else -> VoiceAim.Fill
}

/** Bottom sheet: which explanation it shows. */
internal sealed interface VoiceSheet {
    data object Permission : VoiceSheet
    /** Recording is already allowed but the recognition service still reports no permission or an audio error: explain the system-wide microphone toggle and permission management, or that another app holds it. */
    data object MicBlocked : VoiceSheet
    data class Unavailable(val why: SpeechEngineSpec.Why) : VoiceSheet
    /** It is the recognition service (another app) that lacks the recording permission, not Claire: explain this, and "Open settings" opens its settings page. */
    data class ServicePermission(val service: com.wangzan101.mika.voice.SpeechServiceTarget) : VoiceSheet
}

/**
 * Text heard while holding: settled text in a darker color, still-changing text lighter. When it does not fit [maxLines] lines, [VoiceCopy.fitHeard] drops finished sentences from the front,
 * so the latest text stays visible. Shared by the input box and the floating capsule.
 */
@Composable
internal fun HeardText(stable: String, tail: String, style: androidx.compose.ui.text.TextStyle, maxLines: Int, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val (shownStable, shownTail) = remember(stable, tail, width, style, maxLines) {
            VoiceCopy.fitHeard(stable, tail) { st, tl ->
                measurer.measure(
                    androidx.compose.ui.text.AnnotatedString(st + tl), style,
                    constraints = androidx.compose.ui.unit.Constraints(maxWidth = width.coerceAtLeast(1)),
                ).lineCount <= maxLines
            }
        }
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = c.ink)) { append(shownStable) }
                withStyle(SpanStyle(color = c.ink2)) { append(shownTail) }
            },
            Modifier.fillMaxWidth(), style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The strings the UI needs during push-to-talk; touches no UI, so unit tests can test it directly. */
internal object VoiceCopy {
    /** Where a sentence ends: break after these punctuation marks. */
    private val sentenceEnd = setOf('。', '！', '？', '；', '!', '?', ';', '.', '…', '\n')

    /**
     * When too much text is heard while holding to fit in the card: drop finished sentences from the front (breaking after periods, question marks, etc. in [stable]) and prefix "…",
     * keeping the last few sentences plus the [tail] still being spoken; if even just that sentence does not fit, keep its end. [fits] measures whether a piece of text (settled plus still-changing) fits.
     * Only the display changes; the text handed out stays the same.
     */
    fun fitHeard(stable: String, tail: String, fits: (String, String) -> Boolean): Pair<String, String> {
        if (fits(stable, tail)) return stable to tail
        val cuts = stable.indices.filter { stable[it] in sentenceEnd && it + 1 < stable.length }.map { it + 1 }
        for (cut in cuts) {
            val rest = ELLIPSIS + stable.substring(cut).trimStart()
            if (fits(rest, tail)) return rest to tail
        }
        if (fits(ELLIPSIS, tail)) return ELLIPSIS to tail
        // The sentence still being spoken alone does not fit: find the longest end that does
        var lo = 0
        var hi = tail.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (fits(ELLIPSIS, tail.takeLast(mid))) lo = mid else hi = mid - 1
        }
        return ELLIPSIS to tail.takeLast(lo)
    }

    private const val ELLIPSIS = "…"

    /** The small label at the top of the card: which transcriber is in use. */
    fun engine(spec: SpeechEngineSpec): String = when (spec) {
        is SpeechEngineSpec.Doubao -> tr(R.string.speech_doubao_model)
        is SpeechEngineSpec.FunAsr -> tr(R.string.speech_funasr_model)
        is SpeechEngineSpec.Bailian -> spec.model
        is SpeechEngineSpec.System -> tr(if (spec.onDevice) R.string.voice_engine_phone_offline else R.string.voice_engine_phone)
        is SpeechEngineSpec.Provider -> spec.model
        is SpeechEngineSpec.Vendor -> spec.model
        is SpeechEngineSpec.Sherpa -> tr(R.string.voice_engine_sherpa)
        is SpeechEngineSpec.Unavailable -> ""
    }

    /** The line above the input bar on failure; permission and unavailable-engine cases normally go through the explanation sheet, so this is only a title fallback. */
    fun failure(why: SpeechFailure): String = when (why) {
        SpeechFailure.Busy -> tr(R.string.voice_busy)
        SpeechFailure.Network -> tr(R.string.voice_network)
        SpeechFailure.MicUnavailable, SpeechFailure.AudioError -> tr(R.string.voice_mic_unavailable)
        // Provider errors reuse the wording used for model errors (quota, key, model not found, …)
        is SpeechFailure.Provider -> tr(R.string.voice_provider_failed, why.error.message.orEmpty())
        SpeechFailure.NoPermission -> tr(R.string.voice_permission_title)
        is SpeechFailure.ServiceNoPermission -> tr(R.string.voice_service_permission_title)
        is SpeechFailure.Unavailable -> tr(R.string.voice_unavailable_title)
    }

    val NO_MATCH get() = tr(R.string.voice_no_match)

    fun unavailable(why: SpeechEngineSpec.Why): String = when (why) {
        SpeechEngineSpec.Why.DoubaoNotConfigured -> tr(R.string.speech_doubao_missing)
        SpeechEngineSpec.Why.BailianNotConfigured -> tr(R.string.speech_bailian_missing)
        SpeechEngineSpec.Why.NoSystemNoModel -> tr(R.string.voice_unavailable_no_engine)
        SpeechEngineSpec.Why.SystemGone -> tr(R.string.voice_unavailable_system_gone)
        SpeechEngineSpec.Why.SystemNoResponse -> tr(R.string.voice_unavailable_system_silent)
        SpeechEngineSpec.Why.SystemFailed -> tr(R.string.voice_unavailable_system_failed)
        SpeechEngineSpec.Why.ProviderGone -> tr(R.string.voice_unavailable_provider_gone)
        SpeechEngineSpec.Why.Off -> tr(R.string.voice_unavailable_off)
        SpeechEngineSpec.Why.SherpaModelMissing -> tr(R.string.voice_unavailable_sherpa_missing)
        SpeechEngineSpec.Why.SherpaFailed -> tr(R.string.voice_unavailable_sherpa_failed)
    }

    /** The countdown for the last ten seconds; null while there is still time. */
    fun countdown(elapsedMs: Long): String? {
        val left = MAX_MS - elapsedMs
        if (left > COUNTDOWN_MS) return null
        return tr(R.string.voice_left, ((left + 999) / 1000).toInt().coerceAtLeast(0))
    }
}

/** The mark that the recording permission was denied: when the system no longer shows a dialog (denied before, and it should not explain again), show the explanation instead, with a button straight to the app's permission page. */
private object MicPermission {
    private const val PREFS = "voice_input"
    private const val DENIED = "mic_denied"

    fun denied(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(DENIED, false)

    fun record(context: Context, granted: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(DENIED, !granted).apply()
}

/**
 * UI state of one push-to-talk on the input bar: whether the finger is still down ([holding]), the engine label on the card, the sheet and the toast.
 * Starting the session, converting events and wrapping up are all delegated to [hold].
 */
@Stable
internal class VoiceHold(private val voice: VoiceSource, private val scope: CoroutineScope) {
    // "Too short" means under half a second from the finger going down: HOLD_MS was already held when listening starts, so the state machine only counts the rest
    val hold = HoldToTalk(clock = SystemClock::elapsedRealtime, maxMs = MAX_MS, minMs = MIN_TOTAL_MS - HOLD_MS)

    /** The engine is currently unavailable (recognition service not responding / no provider configured). Gestures still enter the voice flow (begin shows the explanation sheet); this only decides whether tapping the mic shows a toast or the explanation directly. */
    internal val engineUsable: Boolean get() = voice.spec() !is SpeechEngineSpec.Unavailable

    /** The engine is unavailable when the mic is tapped: show the "voice input not available yet" explanation (reason + a way to pick an engine), so an unavailable feature never plays dead. */
    fun explainUnavailable() {
        toast = null
        (voice.spec() as? SpeechEngineSpec.Unavailable)?.let { sheet = VoiceSheet.Unavailable(it.why) }
    }

    /** The finger has not left since the long press started. */
    var holding by mutableStateOf(false)
        private set
    var engine by mutableStateOf("")
        private set
    var sheet by mutableStateOf<VoiceSheet?>(null)
    var toast by mutableStateOf<String?>(null)

    /** Returns true when the recording permission is granted; otherwise asks for it (or shows the explanation) and returns false, so this attempt does not start. Installed by the UI (needs an Activity). */
    internal var ensureMic: () -> Boolean = { true }
    internal var haptic: () -> Unit = {}
    private var collector: Job? = null

    /**
     * The "listening" card's position in the root layout, reported once the card is laid out. It is kept as is when the card is still there after release (waiting for text):
     * pressing again then does not move the card, so it is not reported again.
     */
    internal var cardAt = Rect.Zero
    /** "Release to send" exists only when the caller can send directly; otherwise a finger inside the card still just fills the input box. */
    internal var canSend = false

    /**
     * When the long press fires: first check availability and permission, and only start listening if both are fine.
     * Pressing again is also allowed while the previous segment is still converting after release (a stuck provider, or built-in recognition wrapping up): the previous segment is abandoned and [HoldToTalk.press] cancels it.
     */
    fun begin() {
        if (holding) return
        toast = null
        hold.reset()
        val spec = voice.spec()
        if (spec is SpeechEngineSpec.Unavailable) {
            sheet = VoiceSheet.Unavailable(spec.why)
            return
        }
        if (!ensureMic()) return
        haptic()
        val session = voice.start()
        engine = VoiceCopy.engine(spec)
        hold.press(session)
        holding = true
        collector?.cancel()
        collector = scope.launch { session.events.collect { hold.onEvent(it) } }
    }

    /** The finger moved to [finger] in the root layout, [upPx] above the press point: compute the target and tell the state machine. */
    fun move(finger: Offset, upPx: Float, cancelLiftPx: Float, sendLiftPx: Float) {
        if (!holding) return
        val aim = voiceAim(finger, cardAt, upPx, cancelLiftPx, sendLiftPx)
        hold.aim(cancel = aim == VoiceAim.Cancel, send = aim == VoiceAim.Send && canSend)
    }

    /** The finger left: hand it to the state machine (cancel, send directly, too short, wrap up). */
    fun end() {
        if (!holding) return
        holding = false
        hold.release()
    }

    /** The gesture was interrupted, the page left, the screen rotated or the app went to the background: this segment is dropped. */
    fun abort() {
        holding = false
        hold.dispose()
        collector?.cancel()
        collector = null
    }
}

private val IdleState = MutableStateFlow<HoldToTalk.State>(HoldToTalk.State.Idle)

/**
 * The [VoiceHold] the input bar needs; null when there is no [voice] or the user picked "off" in settings (the input box can only type).
 * The spoken text goes to [onText]; releasing on "release to send" goes to [onSend] and sends directly. Without [onSend] there is no "release to send".
 */
@Composable
internal fun rememberVoiceHold(voice: VoiceSource?, onText: (String) -> Unit, onSend: ((String) -> Unit)? = null): VoiceHold? {
    val off = remember(voice) {
        val spec = voice?.spec()
        voice == null || (spec is SpeechEngineSpec.Unavailable && spec.why == SpeechEngineSpec.Why.Off)
    }
    val scope = rememberCoroutineScope()
    val vh = remember(voice, off) { if (off || voice == null) null else VoiceHold(voice, scope) } ?: return null
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        MicPermission.record(context, granted)
    }
    vh.haptic = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    vh.canSend = onSend != null
    vh.ensureMic = {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) true
        else {
            // The permission is requested only on the first hold; if it was denied and the system no longer shows a dialog, switch to the explanation + a trip to the system settings
            val activity = context.findActivity()
            val blocked = MicPermission.denied(context) && activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            if (blocked) vh.sheet = VoiceSheet.Permission else launcher.launch(Manifest.permission.RECORD_AUDIO)
            false
        }
    }
    val state by vh.hold.state.collectAsState()
    LaunchedEffect(state) {
        when (val s = state) {
            is HoldToTalk.State.Done -> {
                if (s.send && onSend != null) onSend(s.text) else onText(s.text)
                vh.hold.reset()
            }
            is HoldToTalk.State.Problem -> {
                when (val why = s.why) {
                    null -> vh.toast = VoiceCopy.NO_MATCH
                    is SpeechFailure.Unavailable -> vh.sheet = VoiceSheet.Unavailable(why.why)
                    // The recording permission is not granted yet: show the system dialog or "open settings" as before. It was granted but no permission is still reported, or the recognition service reports an audio error:
                    // show the separate explanation (system-wide microphone toggle, permission management). Other cases where the mic cannot start still show the "may be in use" toast
                    // It can be determined that the recognition service itself lacks the recording permission: say it is that app and take the user to its settings page (Claire's permission page would point to the wrong place)
                    is SpeechFailure.ServiceNoPermission -> vh.sheet = VoiceSheet.ServicePermission(why.service)
                    SpeechFailure.NoPermission -> if (vh.ensureMic()) vh.sheet = VoiceSheet.MicBlocked
                    SpeechFailure.AudioError -> vh.sheet = VoiceSheet.MicBlocked
                    else -> vh.toast = VoiceCopy.failure(why)
                }
                vh.hold.reset()
            }
            else -> Unit
        }
    }
    // While holding, the UI drives it: timing, and wrapping up at 60 seconds (the recording stops on its own at the limit, so without wrapping up the text would be lost)
    val listening = state is HoldToTalk.State.Listening
    LaunchedEffect(listening) {
        while (listening) {
            delay(100)
            vh.hold.tick()
        }
    }
    LaunchedEffect(vh.toast) {
        val t = vh.toast ?: return@LaunchedEffect
        delay(if (t == VoiceCopy.NO_MATCH) 2_000 else 3_500)
        vh.toast = null
    }
    // Leaving the page, rotating the screen or the app going to the background: drop the segment being spoken
    LifecycleStartEffect(vh) { onStopOrDispose { vh.abort() } }
    return vh
}

/** The current state of [vh]; always idle when there is no [vh]. */
@Composable
internal fun voiceState(vh: VoiceHold?): HoldToTalk.State = (vh?.hold?.state ?: IdleState).collectAsState().value

/**
 * The gesture over the unfocused input field: a tap still types ([onTap] focuses and opens the keyboard), holding about 0.3 seconds starts listening,
 * sliding up into the "listening" card while holding sends directly, and sliding past the card's top edge cancels (see [voiceAim]); on release it goes to the state machine.
 */
@Composable
internal fun Modifier.holdToTalk(vh: VoiceHold, onTap: () -> Unit): Modifier {
    val density = LocalDensity.current
    val cancelLift = with(density) { CancelLift.toPx() }
    val sendLift = with(density) { SendLift.toPx() }
    // Position of this gesture area in the root layout: finger coordinates are relative to it, while the card's are in the root layout
    val origin = remember { arrayOf(Offset.Zero) }
    return onGloballyPositioned { origin[0] = it.positionInRoot() }.pointerInput(vh, cancelLift, sendLift) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            // true: a tap; false: the finger moved or lifted, not ours; null: the hold completed and listening starts — begin shows the explanation even when the engine is unavailable, so the feature entry never disappears
            val tapped = withTimeoutOrNull<Boolean>(HOLD_MS) {
                var result: Boolean? = null
                while (result == null) {
                    val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    result = when {
                        ch == null -> false
                        !ch.pressed -> { ch.consume(); true }
                        (ch.position - down.position).getDistance() > viewConfiguration.touchSlop -> false
                        else -> null
                    }
                }
                result
            }
            when (tapped) {
                true -> { onTap(); return@awaitEachGesture }
                false -> return@awaitEachGesture
                null -> Unit
            }
            vh.begin()
            if (!vh.holding) {
                // Listening did not start: the explanation sheet or permission dialog is already showing. Keep the sheet while the finger stays down — that is how users discover the voice entry and get taken to settings;
                // releasing within half a second falls back to a tap as before: close the sheet, treat it as a tap and swallow the gesture
                while (true) {
                    val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (!ch.pressed) {
                        ch.consume()
                        if (ch.uptimeMillis - down.uptimeMillis < MIN_TOTAL_MS) { vh.sheet = null; onTap() }
                        return@awaitEachGesture
                    }
                    ch.consume()
                }
            }
            var ended = false
            try {
                while (true) {
                    val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    if (ch == null || !ch.pressed) {
                        ch?.consume()
                        ended = true
                        vh.end()
                        // Held too briefly (most likely a tap was intended): the state machine already cancelled it as nothing heard, so make it a tap here — open the keyboard / show the prompt, and swallow the gesture
                        if (ch != null && ch.uptimeMillis - down.uptimeMillis < MIN_TOTAL_MS) onTap()
                        break
                    }
                    ch.consume()
                    vh.move(origin[0] + ch.position, down.position.y - ch.position.y, cancelLift, sendLift)
                }
            } finally {
                if (!ended) vh.abort()
            }
        }
    }
}

/** The whole input bar turns blue while holding (red when sliding up to cancel, green when inside the card to send directly), with the waveform and "release to fill" in the middle; it covers the glass input bar. */
@Composable
internal fun VoiceBarCover(vh: VoiceHold, state: HoldToTalk.State, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val s = state as? HoldToTalk.State.Listening
    if (!vh.holding || s == null) return
    val c = MikaTheme.colors
    val label = when {
        s.cancelArmed -> stringResource(R.string.voice_release_cancel)
        s.sendArmed -> stringResource(R.string.voice_release_send)
        s.live -> stringResource(R.string.voice_release_fill)
        else -> stringResource(R.string.voice_release_convert)
    }
    Row(
        modifier.clip(CircleShape).background(aimTone(s, c.accent)).testTag("voice-bar"),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VoiceWave(s.level, flat = s.cancelArmed, reduceMotion = reduceMotion, color = c.onAccent)
        Text(label, style = BarLabel, color = c.onAccent, maxLines = 1)
    }
}

/** The waveform: 14 thin bars whose heights follow the volume; with animations off they only follow the volume and do not swing on their own. Shared by the input bar and the floating capsule's speaking panel. */
@Composable
internal fun VoiceWave(level: Float, flat: Boolean, reduceMotion: Boolean, color: Color) {
    val loud by animateFloatAsState(if (flat) 0f else level.coerceIn(0f, 1f), if (reduceMotion) snap() else tween(120), label = "wave-level")
    val phase = if (reduceMotion || flat) 0f else rememberInfiniteTransition(label = "wave").animateFloat(
        0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "wave-phase",
    ).value
    Row(Modifier.height(22.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(WAVE_BARS) { k ->
            val peak = 8f + kotlin.math.abs(kotlin.math.sin(k * 1.7f)) * 14f
            val swing = if (reduceMotion) 1f else 0.55f + 0.45f * kotlin.math.sin(phase + k * 0.9f)
            val h = if (flat) 4f else 5f + (peak - 5f) * (0.25f + 0.75f * loud) * swing
            Box(Modifier.width(3.dp).height(h.dp).background(color, MikaShapes.Micro))
        }
    }
}

private const val WAVE_BARS = 14

/**
 * The layer floating above the input bar, taking no layout space (placed before the input bar, zero height, drawn upward):
 * the "slide up to send/cancel" hint while holding, the "listening" card, and light toasts such as "didn't catch that".
 * [title] is the line at the top of the card ("listening" normally, "interject to '…'" while a task is running).
 */
@Composable
internal fun VoiceFloat(vh: VoiceHold, state: HoldToTalk.State, title: String, backdrop: GlassBackdrop?, reduceMotion: Boolean) {
    val gap = with(LocalDensity.current) { 12.dp.roundToPx() }
    val listening = state as? HoldToTalk.State.Listening
    // The last moment's content still has to be drawn during the card's collapse animation
    val last = remember { arrayOfNulls<HoldToTalk.State.Listening>(1) }
    if (listening != null) last[0] = listening
    Box(
        Modifier.fillMaxWidth().layout { m, cons ->
            val p = m.measure(cons.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            layout(cons.maxWidth, 0) { p.place(0, -p.height - gap) }
        },
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val armed = listening?.cancelArmed == true
            AnimatedVisibility(vh.holding && listening != null, enter = fadeIn(tween(if (reduceMotion) 0 else 200)), exit = fadeOut(tween(if (reduceMotion) 0 else 150))) {
                CancelHint(armed, Modifier.padding(bottom = 14.dp))
            }
            AnimatedVisibility(
                listening != null,
                enter = if (reduceMotion) fadeIn(snap()) else
                    fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 12 } + scaleIn(tween(200), initialScale = 0.98f),
                exit = fadeOut(tween(if (reduceMotion) 0 else 150)),
            ) {
                last[0]?.let { ListenCard(it, title, vh.engine, backdrop) { at -> vh.cardAt = at } }
            }
            val toast = vh.toast
            if (toast != null && listening == null) VoiceToast(toast)
        }
    }
}

/** The "slide up to send/cancel" pill: sliding up into the card sends, sliding on past the card to here cancels; on reaching it, it turns red and reads "release to cancel". */
@Composable
private fun CancelHint(armed: Boolean, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    val shape = MikaShapes.Card
    Row(
        modifier.floatShadow(c, shape).background(if (armed) c.bad else c.surface, shape)
            .padding(horizontal = 14.dp, vertical = 7.dp).testTag("voice-cancel-hint"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fg = if (armed) c.onAccent else c.ink2
        Icon(MikaIcons.Up, null, Modifier.size(15.dp), tint = fg)
        Text(stringResource(if (armed) R.string.voice_release_cancel else R.string.voice_slide_send_cancel), style = HintText, color = fg)
    }
}

/**
 * The "listening" card: a red dot at the top (recording), who it is being said to, which transcriber is used, and the elapsed time; in the middle the live text (the trailing characters that may still change are lighter);
 * below, what happens after release, plus a countdown in the last ten seconds. When sliding up to cancel the text is struck through and the border turns red; when inside the card to send directly the border turns green.
 * Once laid out it reports its position in the root layout to [onPlaced] (a finger inside the card sends directly).
 */
@Composable
private fun ListenCard(
    s: HoldToTalk.State.Listening, title: String, engine: String, backdrop: GlassBackdrop?, onPlaced: (Rect) -> Unit,
) {
    val c = MikaTheme.colors
    val shape = RoundedCornerShape(MikaRadius.Bubble)
    GlassBar(
        Modifier.fillMaxWidth().testTag("voice-listen").onGloballyPositioned { onPlaced(it.boundsInRoot()) }
            .then(if (s.cancelArmed || s.sendArmed) Modifier.border(1.5.dp, aimTone(s, c.accent), shape) else Modifier),
        backdrop, shape,
    ) {
        Column(Modifier.weight(1f).padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecDot(s)
                Text(title, Modifier.weight(1f, fill = false), style = HeadText, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (engine.isNotEmpty()) {
                    Text(
                        engine, Modifier.background(c.fill, MikaShapes.Tile).padding(horizontal = 8.dp, vertical = 2.dp),
                        style = ChipText, color = c.ink2, maxLines = 1,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(ChatCopy.clock(s.elapsedMs), style = TimeText, color = c.ink3)
            }
            val heard = s.stable + s.tail
            val style = if (s.cancelArmed) ListenText.copy(textDecoration = TextDecoration.LineThrough) else ListenText
            val modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 54.dp).alpha(if (s.cancelArmed) 0.4f else 1f)
            if (heard.isEmpty()) {
                Text(
                    stringResource(if (s.live) R.string.voice_say_now else R.string.voice_recording), modifier,
                    style = style, color = c.ink3, maxLines = 6, overflow = TextOverflow.Ellipsis,
                )
            } else {
                HeardText(s.stable, s.tail, style, maxLines = 6, modifier)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(
                    stringResource(if (s.live) R.string.voice_foot_live else R.string.voice_foot_convert),
                    Modifier.weight(1f), style = FootText, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (!s.unlimited) VoiceCopy.countdown(s.elapsedMs)?.let { Text(it, style = FootText, color = c.warn) }
            }
        }
    }
}

/** The dot at the top of the card: blue rings pulse outward while recording; red when cancelling and green when sending directly, both motionless. */
@Composable
private fun RecDot(s: HoldToTalk.State.Listening) {
    val c = MikaTheme.colors
    val reduce = MikaTheme.reduceMotion
    val armed = s.cancelArmed || s.sendArmed
    val halo = if (armed || reduce) 0f else rememberInfiniteTransition(label = "rec").animateFloat(
        0f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "rec-halo",
    ).value
    val dot = aimTone(s, c.accent)
    val soft = c.accentSoft
    Box(
        Modifier.size(8.dp).drawBehind {
            if (halo > 0f) drawCircle(soft, radius = size.minDimension / 2 + 5.dp.toPx() * halo)
            drawCircle(dot)
        },
    )
}

/** The color of the target: red for cancel, green for direct send, otherwise [idle]. Shared by the input bar, the card border and the dot on the card, using the same style as cancel. */
@Composable
private fun aimTone(s: HoldToTalk.State.Listening, idle: Color): Color {
    val c = MikaTheme.colors
    return when {
        s.cancelArmed -> c.bad
        s.sendArmed -> c.ok
        else -> idle
    }
}

/** A short toast above the input bar (didn't catch that, recognition service busy, …) that disappears on its own after a moment. */
@Composable
private fun VoiceToast(text: String) {
    val c = MikaTheme.colors
    Text(
        text,
        Modifier.background(c.ink.copy(alpha = 0.88f), MikaShapes.Card).padding(horizontal = 16.dp, vertical = 9.dp).testTag("voice-toast"),
        style = ToastText, color = c.bg, maxLines = 2,
    )
}

/** The provider is converting after release: a spinner in the input box plus "transcribing…". */
@Composable
internal fun VoiceConverting(modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    Row(modifier.testTag("voice-converting"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Spinner(Modifier.size(13.dp), spinning = !MikaTheme.reduceMotion, track = c.accentSoft, head = c.accent)
        Text(stringResource(R.string.voice_converting), style = MikaType.Body, color = c.ink2, maxLines = 1)
    }
}

/** The grey text shown when push-to-talk is available: left-aligned, matching the cursor position (this line's primary identity is a text box). */
@Composable
internal fun VoicePlaceholder(mode: ComposerMode, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    Row(modifier.fillMaxWidth()) {
        Text(ChatCopy.voicePlaceholder(mode), style = MikaType.Body, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Blurs and fades the conversation while holding to talk, keeping attention on the card; below Android 12 it only fades. */
@Composable
internal fun Modifier.voiceDim(on: Boolean, reduceMotion: Boolean): Modifier {
    val p by animateFloatAsState(if (on) 1f else 0f, if (reduceMotion) snap() else tween(250), label = "voice-dim")
    if (p == 0f) return this
    return graphicsLayer {
        alpha = 1f - 0.55f * p
        if (GlassBlurSupported) {
            val r = 1.5.dp.toPx() * p
            renderEffect = if (r >= 0.5f) BlurEffect(r, r, TileMode.Clamp) else null
        }
    }
}

/** The "voice input not available yet" explanation ("choose" leads to the transcriber picker in settings) and the recording permission explanation ("open settings" leads to Claire's permission page in the system). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceSheetHost(vh: VoiceHold, onPickSpeech: () -> Unit) {
    val sheet = vh.sheet ?: return
    val c = MikaTheme.colors
    val context = LocalContext.current
    val title: String
    val body: String
    val action: String
    val go: () -> Unit
    // Built-in recognizer missing: offer to install the Play Store app — or, when
    // it is already installed but Android exposes no recognition service, open
    // its settings instead of sending people to a page that says "Installed".
    var fix: Pair<String, () -> Unit>? = null
    when (sheet) {
        is VoiceSheet.Unavailable -> {
            title = stringResource(R.string.voice_unavailable_title)
            // NoSystemNoModel / SystemGone mean the phone has no recognition
            // service at all; the other cases are a broken or busy service.
            if (sheet.why == SpeechEngineSpec.Why.NoSystemNoModel || sheet.why == SpeechEngineSpec.Why.SystemGone) {
                val installed = SystemSpeech.installedSpeechApp(context)
                if (installed != null) {
                    // The app is on the phone but Android is not offering its recognition service
                    // (disabled, its component is off, or this build only carries TTS). Never send
                    // the user back to a Play Store page that already says "Installed".
                    body = stringResource(R.string.voice_unavailable_system_gone)
                    fix = stringResource(R.string.voice_permission_open) to {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", installed, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } catch (_: RuntimeException) {
                            vh.toast = tr(R.string.voice_service_settings_failed, SpeechServiceMic.label(context, installed))
                        }
                    }
                } else {
                    body = stringResource(R.string.voice_unavailable_built_in_missing)
                    fix = stringResource(R.string.voice_get_speech_service) to {
                        // market:// first, web fallback; a phone without a Play
                        // Store must never crash — toast instead.
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${SystemSpeech.SPEECH_APP_PACKAGE}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } catch (_: RuntimeException) {
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${SystemSpeech.SPEECH_APP_PACKAGE}"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            } catch (_: RuntimeException) {
                                vh.toast = tr(R.string.voice_speech_service_store_failed)
                            }
                        }
                    }
                }
            } else {
                body = VoiceCopy.unavailable(sheet.why)
            }
            action = stringResource(R.string.voice_go_pick)
            go = onPickSpeech
        }
        is VoiceSheet.ServicePermission -> {
            val service = sheet.service
            title = stringResource(R.string.voice_service_permission_title)
            body = stringResource(R.string.voice_service_permission_body, service.label)
            action = stringResource(R.string.voice_permission_open)
            // Claire cannot request permissions for another app, only take the user to its settings page. It does not start recording again on return: the next hold tries again.
            // When this page cannot be opened (some systems forbid viewing certain apps' details), tell the user where to look
            go = {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", service.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (_: RuntimeException) {
                    vh.toast = tr(R.string.voice_service_settings_failed, service.label)
                }
            }
        }
        VoiceSheet.Permission, VoiceSheet.MicBlocked -> {
            val blocked = sheet == VoiceSheet.MicBlocked
            title = stringResource(if (blocked) R.string.voice_mic_blocked_title else R.string.voice_permission_title)
            body = stringResource(if (blocked) R.string.voice_mic_blocked_body else R.string.voice_permission_body)
            action = stringResource(R.string.voice_permission_open)
            go = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = { vh.sheet = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = MikaShapes.SheetTop,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 18.dp).testTag("voice-sheet")) {
            Text(title, style = MikaType.Title, color = c.ink)
            Text(body, Modifier.padding(top = 8.dp), style = SheetBody, color = c.ink2)
            fix?.let { (label, open) ->
                MikaButton(label, { vh.sheet = null; open() }, Modifier.fillMaxWidth().padding(top = 18.dp), size = ButtonSize.Medium)
            }
            Row(Modifier.fillMaxWidth().padding(top = if (fix != null) 10.dp else 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MikaButton(stringResource(R.string.ok_got_it), { vh.sheet = null }, Modifier.weight(1f), kind = ButtonKind.Tonal, size = ButtonSize.Medium)
                MikaButton(action, { vh.sheet = null; go() }, Modifier.weight(1f), size = ButtonSize.Medium)
            }
        }
    }
}

private val BarLabel = MikaType.Value.copy(fontWeight = FontWeight.Medium)
private val HintText = MikaType.Label
private val HeadText = MikaType.Hint
private val ChipText = MikaType.Micro
private val TimeText = MikaType.Hint.copy(fontFeatureSettings = "tnum")
private val ListenText = MikaType.Title
private val FootText = MikaType.Hint
private val ToastText = MikaType.Caption
private val SheetBody = MikaType.Body
