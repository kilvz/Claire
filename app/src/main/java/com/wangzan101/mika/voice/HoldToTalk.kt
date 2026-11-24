package com.wangzan101.mika.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State machine for hold-to-talk: press, slide up to cancel or send directly, release, finish at the time limit, session events — it computes what the UI should show at this moment.
 * It touches neither the UI nor gestures and is shared by the chat input bar and the floating capsule; the caller opens the session and hands it in ([press]),
 * the caller receives events and forwards them ([onEvent]), and the caller calls [tick] periodically while holding (timing, finishing at [maxMs]; no finish when the session is unlimited).
 * The session may end by itself while the finger is still down (the built-in recognition does not reopen after a segment ends, or an error occurs midway after words were heard): the result is kept first,
 * then handled according to where the finger is when it lifts — resting on cancel drops it, otherwise it is handed off; this way words are never sent out when the user means to cancel.
 * Only called on one thread (the main thread).
 */
class HoldToTalk(private val clock: () -> Long, private val maxMs: Long = 60_000, private val minMs: Long = 500) {
    sealed interface State {
        data object Idle : State

        /**
         * Holding (or just released, with the built-in recognition still finishing up): [stable] is the finalized text, [tail] the few trailing characters that may still change.
         * [ended]: the finger is still down but the session has already ended; the result is kept until the release.
         * [sendArmed]: the finger is on "release to send" (in the input bar's "listening" card), so the text is sent directly on release instead of going into the input field.
         * [unlimited]: this round has no cap (see [SpeechSession.unlimited]), so there is no countdown.
         */
        data class Listening(
            val stable: String,
            val tail: String,
            val level: Float,
            val elapsedMs: Long,
            val cancelArmed: Boolean,
            val live: Boolean,
            val ended: Boolean = false,
            val sendArmed: Boolean = false,
            val unlimited: Boolean = false,
        ) : State

        /** Provider mode: transcribing after release. */
        data object Converting : State

        /** The UI uses it to fill the input field (sent directly when [send] is set), then calls [reset]. */
        data class Done(val text: String, val send: Boolean = false) : State

        /** null = not understood (nothing was recognized, or the press was too short). */
        data class Problem(val why: SpeechFailure?) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var session: SpeechSession? = null
    private var startedAt = 0L
    /** Already released (or timed out) and handed to the session for finishing; later releases or timeouts do not call it again. */
    private var released = false
    /** A result the session already produced while the finger is still down (Done or Problem(null)), handed off once released. */
    private var pending: State? = null
    /** On release the finger was on "release to send": the final result arriving afterwards is sent directly. */
    private var sendOnFinal = false

    fun press(session: SpeechSession) {
        // A previous segment never finished (rare, e.g. it is still transcribing): the user pressed again, so the previous one is dropped
        this.session?.cancel()
        this.session = session
        startedAt = clock()
        released = false
        pending = null
        sendOnFinal = false
        _state.value = State.Listening("", "", 0f, 0, cancelArmed = false, live = session.live, unlimited = session.unlimited)
    }

    /** The finger moved up [upPx] from the press point; beyond [thresholdPx] means "release to cancel", and moving back restores it. */
    fun drag(upPx: Float, thresholdPx: Float) = armCancel(upPx > thresholdPx)

    /** Whether the finger is currently on "cancel"; the floating capsule's landing target is not an upward slide distance, so it reports directly. */
    fun armCancel(armed: Boolean) = aim(cancel = armed, send = false)

    /** The finger's current landing target: cancel, send directly, or neither (on release, fill the input field). Both are never true at once; cancel takes precedence. */
    fun aim(cancel: Boolean, send: Boolean) {
        val s = _state.value as? State.Listening ?: return
        if (released) return
        val sending = send && !cancel
        if (cancel != s.cancelArmed || sending != s.sendArmed) _state.value = s.copy(cancelArmed = cancel, sendArmed = sending)
    }

    fun release() {
        val s = _state.value as? State.Listening ?: return
        if (released) return
        val early = pending
        val current = session
        if (current == null && early == null) return
        when {
            s.cancelArmed -> {
                current?.cancel()
                session = null
                pending = null
                _state.value = State.Idle
            }
            // Too short is most likely an accidental touch: do not transcribe (a provider one would also cost quota); treat it as not understood
            clock() - startedAt < minMs -> {
                current?.cancel()
                session = null
                pending = null
                _state.value = State.Problem(null)
            }
            // The session ended while the finger was still down: hand off the kept result only now
            early != null -> {
                pending = null
                _state.value = if (early is State.Done) early.copy(send = s.sendArmed) else early
            }
            current != null -> {
                released = true
                sendOnFinal = s.sendArmed
                current.release()
                // Provider mode: the recorder takes a moment to stop before the Converting event arrives; show transcribing right at the release so the input field does not flash back to the placeholder first
                if (!s.live) _state.value = State.Converting
            }
        }
    }

    /** Called periodically while holding: updates the elapsed time; at [maxMs] it is treated as a release — recording stops by itself at that limit without a separate notification, so the text would be lost without finishing. */
    fun tick() {
        val s = _state.value as? State.Listening ?: return
        if (released || (session == null && pending == null)) return
        val elapsed = clock() - startedAt
        if (!s.unlimited && elapsed >= maxMs) {
            _state.value = s.copy(elapsedMs = maxMs)
            release()
        } else if (elapsed != s.elapsedMs) {
            _state.value = s.copy(elapsedMs = elapsed)
        }
    }

    fun onEvent(e: SpeechEvent) {
        if (session == null) return
        val now = _state.value
        when (e) {
            // After switching to transcription, the provider's engine may deliver a late level event: accept it only while listening
            is SpeechEvent.Level -> if (now is State.Listening) _state.value = now.copy(level = e.value)
            is SpeechEvent.Partial -> if (now is State.Listening) _state.value = now.copy(stable = e.stable, tail = e.tail)
            SpeechEvent.Converting -> _state.value = State.Converting
            is SpeechEvent.Final -> {
                session = null
                val text = e.text.trim()
                val result = if (text.isEmpty()) State.Problem(null) else State.Done(text, send = released && sendOnFinal)
                if (released || now !is State.Listening) {
                    _state.value = result
                } else {
                    // Finger still down: keep it for now and let the UI keep showing what was heard; on release, drop it if resting on cancel, otherwise hand it off
                    pending = result
                    _state.value = now.copy(stable = text, tail = "", level = 0f, ended = true)
                }
            }
            is SpeechEvent.Failed -> {
                session = null
                val result = State.Problem(e.why)
                // On error there is no need to wait for the release (no more text will come while still holding); only keep it while the finger rests on cancel, so releasing on cancel shows no prompt
                if (!released && now is State.Listening && now.cancelArmed) {
                    pending = result
                    _state.value = now.copy(level = 0f, ended = true)
                } else {
                    _state.value = result
                }
            }
        }
    }

    /** Leaving the screen, rotating, app going to the background: drop this segment and return to idle. Safe to call repeatedly. */
    fun dispose() {
        session?.cancel()
        session = null
        released = false
        pending = null
        sendOnFinal = false
        _state.value = State.Idle
    }

    /** The UI returns to idle after it is done with [State.Done]/[State.Problem]; does nothing while listening or transcribing. */
    fun reset() {
        val s = _state.value
        if (s is State.Done || s is State.Problem) _state.value = State.Idle
    }

    companion object {
        /**
         * Appends the spoken text after the text already in the input field: when the adjacent characters on both sides are CJK characters or full-width punctuation, join them directly,
         * otherwise put a space in between (none is added when the existing text already ends with whitespace).
         */
        fun join(existing: String, spoken: String): String {
            if (existing.isEmpty()) return spoken
            if (spoken.isEmpty()) return existing
            val left = existing.last()
            if (left.isWhitespace()) return existing + spoken
            return if (isCjk(left) && isCjk(spoken.first())) existing + spoken else "$existing $spoken"
        }

        private fun isCjk(ch: Char): Boolean {
            val block = Character.UnicodeBlock.of(ch)
            if (block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
            ) return true
            return when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HANGUL, Character.UnicodeScript.BOPOMOFO -> true
                else -> false
            }
        }
    }
}
