package com.wangzan101.mika.capsule

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.chat.ui.ChatCopy
import com.wangzan101.mika.chat.ui.Spinner
import com.wangzan101.mika.voice.ui.VoiceCopy
import com.wangzan101.mika.voice.ui.VoiceWave
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.voice.HoldToTalk
import com.wangzan101.mika.voice.SpeechEngineSpec
import com.wangzan101.mika.voice.VoiceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hold-to-talk on the floating capsule: where the finger lands on release ([CapsuleVoice.drop]), the line flashed on the capsule after sending ([CapsuleVoice.sent]),
 * the session and UI state of one utterance ([CapsuleTalk], whose state machine is the same [HoldToTalk] as the input bar),
 * and the panel that floats next to the capsule while holding ([CapsuleVoicePanel]: who it is talking to, live text, the waveform, and the two drop targets 'cancel' and 'edit').
 * Who it is handed to and how it is sent are decided by [CapsuleController] (the same target lookup as double tap to send the screen).
 */
object CapsuleVoice {
    /** On release: send directly, open the card to keep editing, or discard. */
    enum class Drop { Send, Edit, Cancel }

    /** Maximum number of characters carried in the line flashed on the capsule. */
    private const val SENT_CHARS = 12

    /**
     * Where the finger's position on screen ([x],[y]) falls: each of the two circles counts as a hit expanded outward by [slopPx], everything else sends.
     * While the circles have not been laid out yet (empty), nothing counts as a hit, so the first moment the panel appears is not misjudged.
     */
    fun drop(x: Float, y: Float, cancel: Rect, edit: Rect, slopPx: Int): Drop {
        fun Rect.hit() = !isEmpty && x >= left - slopPx && x <= right + slopPx && y >= top - slopPx && y <= bottom + slopPx
        return when {
            cancel.hit() -> Drop.Cancel
            edit.hit() -> Drop.Edit
            else -> Drop.Send
        }
    }

    /** The line on the capsule after sending: steered, answered, or sent, followed by the first characters of what was said. */
    fun sent(kind: CardInputKind, text: String): String {
        val head = text.trim().let { if (it.length > SENT_CHARS) it.take(SENT_CHARS) + "…" else it }
        return when (kind) {
            CardInputKind.Steer -> tr(R.string.capsule_voice_steered, head)
            CardInputKind.Answer -> tr(R.string.capsule_voice_answered, head)
            CardInputKind.Continue, CardInputKind.Start -> tr(R.string.capsule_voice_sent, head)
        }
    }

    /** The line at the top of the panel: who it is talking to. Consistent with who the card's input row is addressed to; without an input row (needs takeover, approval) or for a new conversation it is 'listening'. */
    fun title(card: CardView?): String {
        val input = card?.let(CapsuleCards::input)?.kind ?: return tr(R.string.voice_listening)
        val goal = when (card) {
            is CardView.Detail -> card.run.run.goal
            is CardView.Chat -> card.run?.run?.goal ?: card.title.takeIf { card.target != null }
            is CardView.Kept -> null
        }?.takeIf { it.isNotBlank() }
        return when (input) {
            CardInputKind.Steer -> goal?.let { tr(R.string.voice_steer_to, it) } ?: tr(R.string.voice_listening)
            CardInputKind.Answer -> tr(R.string.capsule_voice_answer_title)
            CardInputKind.Continue, CardInputKind.Start -> goal?.let { tr(R.string.capsule_voice_continue_title, it) } ?: tr(R.string.voice_listening)
        }
    }
}

/**
 * Waiting for the result after release: if nobody has cancelled by [ms], call [onTimeout].
 * [arm] restarts the timer (the previous one is voided); [cancel] when the session hands over a result, when holding again, or when the panel collapses.
 * It is separated out so it can be tested: when the engine is stuck, the panel must always take itself down.
 */
internal class VoiceWait(private val scope: CoroutineScope, private val onTimeout: () -> Unit) {
    private var job: Job? = null

    fun arm(ms: Long) {
        cancel()
        job = scope.launch {
            delay(ms)
            onTimeout()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}

/**
 * One utterance on the capsule: open a session ([begin]), collect events, tick the timer while holding, compute the drop target as the finger moves ([move]), release ([finish]).
 * While the user holds with a finger ([byTouch]) the drop target follows the finger; the screen reader's 'talk' has no finger and only sends. When done ([HoldToTalk.State.Done],
 * [HoldToTalk.State.Problem]) it is handed to [onResult] together with the drop target at release. If the session finishes while the finger is still down, [HoldToTalk] keeps the result
 * and it is handled with the drop target from when the finger leaves (cancel discards, edit opens the card, everything else sends); the screen reader has no finger, so once the session ends it counts as 'send'.
 * Releasing while transcribing and pressing again: the old segment is dropped and listening starts over. Used on the main thread only.
 */
internal class CapsuleTalk(
    private val context: Context,
    private val voice: () -> VoiceSource,
    private val scope: CoroutineScope,
    private val onResult: (CapsuleVoice.Drop, HoldToTalk.State) -> Unit,
) {
    /** The result of [begin]: started, or why it did not start. */
    sealed interface Begin {
        data object Started : Begin
        data object NoPermission : Begin
        data class Unavailable(val why: SpeechEngineSpec.Why) : Begin
    }

    // 'Too short' is measured from the finger going down: HOLD_MS has already elapsed when listening starts, so the state machine only counts the remainder
    val hold = HoldToTalk(clock = SystemClock::elapsedRealtime, maxMs = MAX_MS, minMs = MIN_TOTAL_MS - HOLD_MS)

    /** The panel is showing (holding, or transcribing after release). */
    var active by mutableStateOf(false)
        private set
    /** Already released (or the screen reader's 'send'), waiting for text. */
    var released by mutableStateOf(false)
        private set
    var byTouch by mutableStateOf(true)
        private set
    var aim by mutableStateOf(CapsuleVoice.Drop.Send)
        private set
    var title by mutableStateOf("")
    var engine by mutableStateOf("")
        private set

    /** The positions of the two drop targets on screen, reported once the panel has been laid out. */
    var cancelAt = Rect.Zero
    var editAt = Rect.Zero

    private var drop: CapsuleVoice.Drop? = null
    private var collector: Job? = null
    private var ticker: Job? = null
    private var watcher: Job? = null
    /** Fallback for waiting on the result after release, see [armWatchdog]; [waitMs] is decided by the engine used this time. */
    private val watchdog = VoiceWait(scope) {
        if (!active || !released) return@VoiceWait
        val at = drop ?: aim
        abort()
        onResult(at, HoldToTalk.State.Problem(null))
    }
    private var waitMs = WAIT_PROVIDER_MS
    private var startedAt = 0L

    /** Whether it can be used and whether the recording permission is there: listen only when both hold. The capsule is not an Activity, so it cannot show the system permission dialog. */
    fun begin(title: String, byTouch: Boolean): Begin {
        if (active && !released) return Begin.Started
        val source = voice()
        val spec = source.spec()
        if (spec is SpeechEngineSpec.Unavailable) return Begin.Unavailable(spec.why)
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return Begin.NoPermission
        // The previous segment is still transcribing after release (the provider is stuck, or on-device recognition is wrapping up): the user pressed again, so the old one is dropped; the session is cancelled by press
        if (active) stop()
        hold.reset()
        val session = source.start()
        this.title = title
        this.byTouch = byTouch
        engine = VoiceCopy.engine(spec)
        waitMs = when (spec) {
            is SpeechEngineSpec.System -> WAIT_SYSTEM_MS
            is SpeechEngineSpec.Provider -> WAIT_PROVIDER_MS
            else -> WAIT_STREAM_MS
        }
        aim = CapsuleVoice.Drop.Send
        drop = null
        released = false
        cancelAt = Rect.Zero
        editAt = Rect.Zero
        startedAt = SystemClock.elapsedRealtime()
        hold.press(session)
        active = true
        collector = scope.launch { session.events.collect { hold.onEvent(it) } }
        watcher = scope.launch {
            hold.state.collect { s ->
                // The screen reader has no finger to wait for: when the session ends by itself, treat it as pressing 'send'
                if (s is HoldToTalk.State.Listening && s.ended && !this@CapsuleTalk.byTouch) finish(CapsuleVoice.Drop.Send)
                if (s is HoldToTalk.State.Done || s is HoldToTalk.State.Problem) {
                    val d = drop ?: aim
                    stop()
                    hold.reset()
                    onResult(d, s)
                }
            }
        }
        ticker = scope.launch {
            while (active && !released) {
                delay(TICK_MS)
                // Finish at this moment's drop target (the state machine also releases itself on timeout, but it does not know the drop target)
                val unlimited = (hold.state.value as? HoldToTalk.State.Listening)?.unlimited == true
                if (!unlimited && SystemClock.elapsedRealtime() - startedAt >= MAX_MS) finish(aim) else hold.tick()
            }
        }
        return Begin.Started
    }

    /** The finger moved to ([x],[y]) on screen. */
    fun move(x: Float, y: Float, slopPx: Int) {
        if (!active || released || !byTouch) return
        aim = CapsuleVoice.drop(x, y, cancelAt, editAt, slopPx)
        // The state machine also needs to know whether the finger is over cancel: when the session errors while holding, it stays silent while over cancel
        hold.armCancel(aim == CapsuleVoice.Drop.Cancel)
    }

    /** Release (or the screen reader's 'send'): discard when over cancel, otherwise wrap up and wait for text. */
    fun finish(at: CapsuleVoice.Drop) {
        if (!active || released) return
        if (at == CapsuleVoice.Drop.Cancel) {
            abort()
            return
        }
        aim = at
        drop = at
        released = true
        hold.armCancel(false)
        hold.release()
        armWatchdog()
    }

    /**
     * Fallback after release: beyond the engine's own wrap-up limit (2 seconds for on-device recognition, about 20 seconds for a streaming connection with wrap-up, 30 seconds for whole-segment transcription) leave a little margin,
     * and if no result has been handed over by then, collapse the panel and treat it as not heard. When the session is stuck the panel does not stay on screen showing 'converting…' forever.
     */
    private fun armWatchdog() {
        if (!active || !released) return
        watchdog.arm(waitMs)
    }

    /** This segment is no longer wanted (dragged to cancel, the capsule was taken down, the touch was taken away by the system). May be called repeatedly. */
    fun abort() {
        stop()
        hold.dispose()
    }

    private fun stop() {
        active = false
        released = false
        drop = null
        ticker?.cancel()
        ticker = null
        watcher?.cancel()
        watcher = null
        collector?.cancel()
        collector = null
        watchdog.cancel()
    }

    companion object {
        /** How long it must be held without moving to count as a long press (milliseconds). */
        const val HOLD_MS = 400L
        /** Measured from the finger going down; releasing before this counts as not heard. */
        const val MIN_TOTAL_MS = 500L
        const val MAX_MS = 60_000L
        private const val TICK_MS = 100L
        /** Upper bound for waiting on the result after release, with a little margin on top of each engine's own wrap-up limit (see [armWatchdog]). */
        private const val WAIT_SYSTEM_MS = 8_000L
        private const val WAIT_STREAM_MS = 25_000L
        private const val WAIT_PROVIDER_MS = 35_000L
    }
}

/** How far the panel and the two circles sit from the capsule and from each other; how big the two circles are. */
private val PanelGap = 16.dp
private val TargetsGap = 18.dp
private val TargetSize = 62.dp
private val PanelShape = RoundedCornerShape(24.dp)
private val HeadText = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp)
private val TimeText = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontFeatureSettings = "tnum")
private val ChipText = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp)
private val SaidText = TextStyle(fontSize = 17.5.sp, lineHeight = 26.sp)
private val FootText = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
private val TargetText = TextStyle(
    fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
    shadow = Shadow(Color.Black.copy(alpha = 0.4f), Offset(0f, 1f), 3f),
)
/** Dim the app behind a little so attention stays on the panel. */
private const val DIM = 0.28f

/**
 * The full-screen layer while holding the capsule to talk (does not take touches): the background is dimmed a little, and the panel hugs the side of the capsule ([anchorTop]..[anchorBottom], screen y coordinates),
 * above the capsule when it fits, otherwise below; the two drop targets sit outside the panel (farther from the capsule) and the finger drags onto them.
 * [insetTop]/[insetBottom] are the heights taken by the status bar and the bottom gesture bar. Once the two circles are laid out, their screen positions are reported to [talk].
 */
@Composable
internal fun CapsuleVoicePanel(talk: CapsuleTalk, anchorTop: Int, anchorBottom: Int, insetTop: Int, insetBottom: Int) {
    if (!talk.active) return
    val c = MikaTheme.colors
    val state by talk.hold.state.collectAsState()
    val view = LocalView.current
    val dim by animateFloatAsState(DIM, if (MikaTheme.reduceMotion) snap() else tween(200), label = "voice-dim")
    var below by remember { mutableStateOf(false) }
    // The circles' screen positions: coordinates inside the window plus the window's position on screen
    fun Rect.onScreen(): Rect {
        val at = IntArray(2).also(view::getLocationOnScreen)
        return translate(Offset(at[0].toFloat(), at[1].toFloat()))
    }
    Layout(
        content = {
            Targets(talk, show = talk.byTouch && !talk.released) { cancel, edit ->
                talk.cancelAt = cancel.onScreen()
                talk.editAt = edit.onScreen()
            }
            Panel(talk, state, below)
        },
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)).testTag("capsule-voice"),
    ) { measurables, cons ->
        val loose = cons.copy(minWidth = 0, minHeight = 0)
        val targets = measurables[0].measure(loose)
        val panel = measurables[1].measure(loose)
        val gap = PanelGap.roundToPx()
        val between = TargetsGap.roundToPx()
        val total = targets.height + between + panel.height
        val top = insetTop + gap
        val bottom = cons.maxHeight - insetBottom - gap
        // Place above the capsule if it fits (the two circles at the very top), otherwise below (the two circles at the very bottom)
        val goBelow = anchorTop - gap - total < top
        if (goBelow != below) below = goBelow
        val groupTop = (if (goBelow) anchorBottom + gap else anchorTop - gap - total)
            .coerceAtMost(bottom - total).coerceAtLeast(top)
        layout(cons.maxWidth, cons.maxHeight) {
            if (goBelow) {
                panel.place((cons.maxWidth - panel.width) / 2, groupTop)
                targets.place((cons.maxWidth - targets.width) / 2, groupTop + panel.height + between)
            } else {
                targets.place((cons.maxWidth - targets.width) / 2, groupTop)
                panel.place((cons.maxWidth - panel.width) / 2, groupTop + targets.height + between)
            }
        }
    }
}

/** The 'cancel' and 'edit' circles; the one the finger moves onto grows and changes color. Hidden while transcribing after release (their positions are kept so the panel does not jump). */
@Composable
private fun Targets(talk: CapsuleTalk, show: Boolean, onPlaced: (Rect, Rect) -> Unit) {
    // The laid-out positions of the two circles (window coordinates); report only once both exist
    val at = remember { arrayOf(Rect.Zero, Rect.Zero) }
    Row(
        Modifier.alpha(if (show) 1f else 0f),
        horizontalArrangement = Arrangement.spacedBy(56.dp),
    ) {
        Target(MikaIcons.Close, stringResource(R.string.capsule_voice_cancel), hot = show && talk.aim == CapsuleVoice.Drop.Cancel, bad = true) {
            at[0] = it
            if (!at[1].isEmpty) onPlaced(at[0], at[1])
        }
        Target(MikaIcons.Edit, stringResource(R.string.capsule_voice_edit), hot = show && talk.aim == CapsuleVoice.Drop.Edit, bad = false) {
            at[1] = it
            if (!at[0].isEmpty) onPlaced(at[0], at[1])
        }
    }
}

@Composable
private fun Target(icon: ImageVector, label: String, hot: Boolean, bad: Boolean, onPlaced: (Rect) -> Unit) {
    val c = MikaTheme.colors
    val scale by animateFloatAsState(if (hot) 1.18f else 1f, if (MikaTheme.reduceMotion) snap() else tween(150), label = "target")
    val fill = when {
        !hot -> c.surface.copy(alpha = 0.95f)
        bad -> c.bad
        else -> c.accent
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(TargetSize)
                .onGloballyPositioned { onPlaced(it.boundsInWindow()) }
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .glass(c, CircleShape, need = false)
                .background(fill, CircleShape)
                .testTag(if (bad) "capsule-voice-cancel" else "capsule-voice-edit"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(24.dp), tint = if (hot) c.onAccent else c.ink)
        }
        Text(label, style = TargetText, color = Color.White, maxLines = 1)
    }
}

/** The panel: the recording dot, who it is talking to, which transcriber is used, and the elapsed time at the top; the live text in the middle; the waveform; and what releasing will do at the bottom. */
@Composable
private fun Panel(talk: CapsuleTalk, state: HoldToTalk.State, below: Boolean) {
    val c = MikaTheme.colors
    val listening = state as? HoldToTalk.State.Listening
    val converting = talk.released || state is HoldToTalk.State.Converting
    val aim = if (converting) CapsuleVoice.Drop.Send else talk.aim
    val cancel = aim == CapsuleVoice.Drop.Cancel
    val edit = aim == CapsuleVoice.Drop.Edit
    Column(
        Modifier.padding(horizontal = 14.dp).widthIn(max = 520.dp).fillMaxWidth()
            .glass(c, PanelShape, need = false)
            .then(if (cancel) Modifier.border(1.5.dp, c.bad, PanelShape) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
            .testTag("capsule-voice-panel"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val dot = when {
                cancel -> c.bad
                edit || converting -> c.ink2
                else -> c.accent
            }
            Box(Modifier.size(8.dp).drawBehind { drawCircle(dot) })
            Text(talk.title, Modifier.weight(1f, fill = false), style = HeadText, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (talk.engine.isNotEmpty()) {
                Text(
                    talk.engine, Modifier.background(c.fill, RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                    style = ChipText, color = c.ink2, maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            listening?.let { Text(ChatCopy.clock(it.elapsedMs), style = TimeText, color = c.ink3) }
        }
        val heard = listening?.let { it.stable + it.tail }.orEmpty()
        val ghost = when {
            converting -> ""
            listening?.live == true -> stringResource(R.string.voice_say_now)
            else -> stringResource(R.string.voice_recording)
        }
        if (converting && heard.isEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 52.dp).testTag("capsule-voice-converting"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                Spinner(Modifier.size(15.dp), spinning = !MikaTheme.reduceMotion, track = c.accentSoft, head = c.accent)
                Text(stringResource(R.string.voice_converting), style = SaidText, color = c.ink2, maxLines = 1)
            }
        } else {
            val style = if (cancel) SaidText.copy(textDecoration = TextDecoration.LineThrough) else SaidText
            val modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 52.dp).alpha(if (cancel) 0.4f else 1f)
            if (heard.isEmpty()) Text(ghost, modifier, style = style, color = c.ink3, maxLines = 5, overflow = TextOverflow.Ellipsis)
            // When there is too much text, drop whole sentences already spoken from the front so the latest text stays visible (same approach as the input bar)
            else com.wangzan101.mika.voice.ui.HeardText(listening?.stable.orEmpty(), listening?.tail.orEmpty(), style, maxLines = 5, modifier)
        }
        if (!converting) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                VoiceWave(listening?.level ?: 0f, flat = cancel, reduceMotion = MikaTheme.reduceMotion, color = if (cancel || edit) c.ink3 else c.accent)
            }
        }
        // While transcribing the middle already says 'converting…', so do not repeat it below
        if (converting) return@Column
        val foot = when {
            !talk.byTouch -> stringResource(R.string.capsule_voice_foot_a11y)
            cancel -> stringResource(R.string.capsule_voice_foot_cancel)
            edit -> stringResource(R.string.capsule_voice_foot_edit)
            listening?.live == true -> stringResource(if (below) R.string.capsule_voice_foot_send_down else R.string.capsule_voice_foot_send_up)
            else -> stringResource(if (below) R.string.capsule_voice_foot_convert_down else R.string.capsule_voice_foot_convert_up)
        }
        Text(foot, Modifier.fillMaxWidth().padding(top = 8.dp), style = FootText, color = c.ink3, maxLines = 2)
    }
}
