package com.wangzan101.mika.voicecall

import android.media.MediaRecorder
import com.wangzan101.mika.calls.CallInputPacer
import com.wangzan101.mika.calls.CallPlaybackQueue
import com.wangzan101.mika.calls.CallRealtimeSession
import com.wangzan101.mika.calls.VolcRealtimeEvent
import com.wangzan101.mika.calls.VolcRealtimeException
import com.wangzan101.mika.calls.VolcRealtimeProtocol
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Call state machine: an explicit enumeration, exactly one at any moment, not stitched together from booleans. */
sealed interface VoiceCallState {
    data object Connecting : VoiceCallState
    data object MicCheck : VoiceCallState
    data class Active(val startedAt: Long) : VoiceCallState
    /** [idleMinutes] is meaningful only for [EndReason.Idle]: how many minutes of quiet before hanging up. */
    data class Ended(val reason: EndReason, val idleMinutes: Int = 0) : VoiceCallState
    data class Failed(val error: VoiceCallError, val attempt: Int) : VoiceCallState
}

/**
 * Whose turn it is once connected: listening (waiting for you to speak / you are speaking), thinking (you finished, waiting for an answer), speaking (Claire's voice is playing).
 * Meaningful only inside [VoiceCallState.Active]; the call page uses it to show "listening to you / thinking / Claire speaking".
 */
enum class VoiceCallTurn { Listening, Thinking, Speaking }

/** [Idle]: there has been no new activity for a good while during the call (nobody spoke, nothing was typed, no task progressed), so it hangs up automatically after the configured duration. */
enum class EndReason { Hangup, Notification, IncomingCall, Idle, Background;

    /** The wire value recorded in the call record ([com.wangzan101.mika.chat.ConversationCall.endReason]). */
    val wire: String get() = when (this) {
        Idle -> com.wangzan101.mika.chat.ConversationCall.END_IDLE
        Hangup -> com.wangzan101.mika.chat.ConversationCall.END_HANGUP
        else -> name.lowercase()
    }
}

enum class VoiceCallError { Auth, ConnectTimeout, Disconnected, RateLimited, Provider, MicSilent, AudioFocus, Playback, Busy }

/**
 * In-app real-time voice call orchestration. The full chain of one connection: wait for foreground RESUMED → telephony-busy check → audio focus → connect the real-time session →
 * mic check (VOICE_COMMUNICATION init failure falls back to MIC in the orchestration layer) → start playback → local two-tone prompt tone →
 * cloud greeting (played only once per session) → Active.
 *
 * After Active, three paths run in parallel: event consumption (downstream audio into the 20-second-capped [CallPlaybackQueue]; barge-in invalidates the generation + hardware flush;
 * front desk tool calls that carry user words first register the current-turn transcript and then pass the safety gate), upstream capture (20ms frame pacing against absolute deadlines), and idle hangup (no overall duration cap, see [idleMs]).
 * User barge-in only interrupts what the assistant is saying, never background tasks.
 * Shutdown is idempotent: hangup/end/failure all go through finally to release focus, capture, playback and the realtime session; external cancellation does not count as a failure.
 *
 * The safety gate ([requestGate]) is created once per session and not reset across retries: it must withstand reconnection replays for its callIds.
 */
class VoiceCallSession(
    private val scope: CoroutineScope,
    private val audio: VoiceCallAudio,
    private val foregroundResumed: StateFlow<Boolean>,
    private val connect: suspend (attempt: Int) -> CallRealtimeSession,
    private val greeting: String,
    private val echoWindow: RingBuffer<String> = RingBuffer(ECHO_WINDOW_CAPACITY_CHARS),
    private val requestGate: VoiceCallRequest = VoiceCallRequest(echoWindow),
    /** Front desk tools called by the model (those with words have passed the safety gate and the words are swapped for the passed current-turn transcript); the assembly side replies with the same callId after executing. */
    private val onDeskCall: (callId: String, name: String, args: JsonObject) -> Unit = { _, _, _ -> },
    /** When the conversation cannot take typed text (realtime sessions take no text), hand it to the assembly side to dispatch directly, with the result shown locally. */
    private val onTypedText: (text: String) -> Unit = {},
    private val onUserTranscript: (text: String, isFinal: Boolean) -> Unit = { _, _ -> },
    private val onAssistantTranscript: (text: String, isFinal: Boolean) -> Unit = { _, _ -> },
    /** The assistant's text so far in this turn (accumulated segment by segment), for the call page to display live; settling still goes through [onAssistantTranscript]. */
    private val onAssistantDraft: (text: String) -> Unit = {},
    /**
     * How long without new activity during a call before hanging up; null means no automatic hangup. New activity: you speak (even mid-utterance), typing, Claire speaking, and task progress ([onActivity]).
     * [IDLE_WARNING_MS] before the deadline it first asks whether you are still there: when the conversation can keep replying, the model paraphrases [idleCue]; when it cannot, [idleLine] is read out as-is;
     * the reminder itself does not count as new activity.
     */
    private val idleMs: Long? = DEFAULT_IDLE_MS,
    private val idleCue: String = "",
    private val idleLine: String = "",
    private val resumedTimeoutMs: Long = RESUMED_TIMEOUT_MS,
    private val micCheckTimeoutMs: Long = MIC_CHECK_TIMEOUT_MS,
    private val clockNanos: () -> Long = System::nanoTime,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<VoiceCallState>(VoiceCallState.Connecting)
    val state: StateFlow<VoiceCallState> = _state
    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel
    private val _speakerLevel = MutableStateFlow(0f)
    val speakerLevel: StateFlow<Float> = _speakerLevel
    private val _turn = MutableStateFlow(VoiceCallTurn.Listening)
    val turn: StateFlow<VoiceCallTurn> = _turn
    private val _muted = MutableStateFlow(false)

    /** Mute: capture continues as usual, but upstream sends silent frames instead (keeping the pace, so the server just sees you as not speaking) and the level drops to zero. Preserved across retries. */
    val muted: StateFlow<Boolean> = _muted

    /** The time (clockNanos) of the most recent cloud audio actually written to the playback track; after playback has been idle for a while the turn returns to listening. */
    @Volatile private var lastSpokeNanos = 0L

    /** Time (clockNanos) of the most recent new activity; the quiet timer counts from here. */
    @Volatile private var lastActivityNanos = 0L

    /** "Are you still there" has been asked and no new sign of you or a task has arrived: while this is set, Claire's own speech does not restart the timer (that is just the reminder being read). */
    @Volatile private var idleWarned = false

    private val lock = Any()
    private var runJob: Job? = null
    private var terminal = false
    private var retryScheduled = false
    private var greeted = false

    init {
        startAttempt(1)
    }

    fun hangup() = end(EndReason.Hangup)

    /** New activity from outside the call (a dispatched task progressed): the quiet timer restarts. */
    fun onActivity() = markActivity(fromMika = false)

    private fun markActivity(fromMika: Boolean) {
        if (fromMika && idleWarned) return
        if (!fromMika) idleWarned = false
        lastActivityNanos = clockNanos()
    }

    fun setMuted(muted: Boolean) {
        _muted.value = muted
        if (muted) _micLevel.value = 0f
    }

    /**
     * The reply is already displayed locally (the result of a typed request only goes into the transcript/panel; the cloud will not speak it): no more playback will bring "thinking" back to "listening",
     * so the assembly side tells the session after displaying it. While speaking it does nothing; when playback ends it naturally returns to listening.
     */
    fun onLocalReply() {
        _turn.compareAndSet(VoiceCallTurn.Thinking, VoiceCallTurn.Listening)
    }

    /** The conversation connected right now; null when not connected. */
    @Volatile private var live: CallRealtimeSession? = null

    /**
     * Text input on the call panel: typing follows the same path as speaking -- it is registered into the transcript (as a question) and as the current-turn transcript (so the model's later tool calls still pass the gate).
     * When the conversation can take text (standard mode) it is handed over as a message for it to answer; when it cannot (realtime mode) the text is recorded as the user's own words
     * and handed to [onTypedText] to dispatch directly. Accepted only in the Active state; blank input is ignored.
     */
    fun sendUserText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (_state.value !is VoiceCallState.Active) return
        markActivity(fromMika = false)
        onUserTranscript(clean, true)
        _turn.value = VoiceCallTurn.Thinking
        requestGate.onUserTranscript(clean)
        if (live?.sendUserText(clean) == true) return
        requestGate.acceptTyped(clean)
        onTypedText(clean)
    }

    /**
     * Terminal transition: calling again once terminal is a no-op (idempotent wind-down). The only exception is Failed → Ended:
     * when the user does not retry after a failure and just leaves, the call page is responsible for the wrap-up and the foreground service uses it to stop; after Ended is set, retry no longer takes effect.
     */
    fun end(reason: EndReason) {
        val job = synchronized(lock) {
            if (terminal) {
                if (_state.value is VoiceCallState.Failed) _state.value = endedFor(reason)
                return
            }
            terminal = true
            _state.value = endedFor(reason)
            runJob
        }
        job?.cancel()
    }

    /**
     * Wait for the current attempt's wind-down to finish (after hang-up there is still a finally path: close the real-time session, stop capture and playback).
     * When the page is destroyed, wait for it first so the last transcript segment is not lost; do not call it from this session's own coroutine scope (it would wait on itself).
     */
    suspend fun awaitFinished() {
        val job = synchronized(lock) { runJob }
        job?.join()
    }

    /** Retry in place: only valid in the Failed state, reruns the whole connection flow after attempt++; the safety gate and echo window are not reset. */
    fun retry() {
        val oldJob: Job?
        val attempt: Int
        synchronized(lock) {
            val failed = _state.value as? VoiceCallState.Failed ?: return
            if (!terminal || retryScheduled) return
            retryScheduled = true
            oldJob = runJob
            attempt = failed.attempt
        }
        val job = scope.launch {
            oldJob?.join() // Wait for the previous attempt's wind-down to finish before taking the audio device again
            runAttempt(attempt + 1)
        }
        synchronized(lock) { runJob = job }
    }

    private fun startAttempt(attempt: Int) {
        val job = scope.launch { runAttempt(attempt) }
        synchronized(lock) { runJob = job }
    }

    private fun fail(error: VoiceCallError, attempt: Int) {
        val job = synchronized(lock) {
            if (terminal) return
            terminal = true
            _state.value = VoiceCallState.Failed(error, attempt)
            runJob
        }
        job?.cancel()
    }

    private suspend fun runAttempt(attempt: Int) {
        var realtime: CallRealtimeSession? = null
        try {
            synchronized(lock) {
                retryScheduled = false
                // Start race: if a terminal state has already been set by end/hangup, abandon this attempt right away
                if (terminal && _state.value !is VoiceCallState.Failed) return
                terminal = false
            }
            _state.value = VoiceCallState.Connecting
            _turn.value = VoiceCallTurn.Listening
            if (!awaitResumed()) {
                end(EndReason.Background)
                return
            }
            if (audio.isTelephonyBusy()) throw VoiceCallFailure(VoiceCallError.Busy)
            if (!audio.requestCallAudio()) throw VoiceCallFailure(VoiceCallError.AudioFocus)
            audio.onFocusLost = { fail(VoiceCallError.AudioFocus, attempt) }
            realtime = connect(attempt)
            _state.value = VoiceCallState.MicCheck
            startCaptureWithFallback()
            checkMic()
            if (!audio.startPlayback()) throw VoiceCallFailure(VoiceCallError.Playback)
            if (!realtime.setMuted(false)) throw VoiceCallFailure(VoiceCallError.Provider)
            playTone()
            greetOnce(realtime)
            _state.value = VoiceCallState.Active(nowMillis())
            live = realtime
            runActive(realtime)
        } catch (cancelled: CancellationException) {
            throw cancelled // External cancellation is not a failure; the terminal state is decided by the canceller
        } catch (failure: VoiceCallFailure) {
            fail(failure.error, attempt)
        } catch (error: VolcRealtimeException) {
            fail(error.kind.toVoiceCallError(), attempt)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            fail(VoiceCallError.Provider, attempt)
        } finally {
            audio.onFocusLost = null
            live = null
            runCatching { realtime?.close() }
            audio.stopCapture()
            audio.stopPlayback()
            audio.abandonCallAudio()
            _micLevel.value = 0f
            _speakerLevel.value = 0f
        }
    }

    private suspend fun awaitResumed(): Boolean {
        if (foregroundResumed.value) return true
        return withTimeoutOrNull(resumedTimeoutMs) {
            foregroundResumed.first { it }
            true
        } ?: false
    }

    /** VOICE_COMMUNICATION enables the platform's AEC/NS; the fallback chain for init failure lives in the orchestration layer, not in the capture implementation. */
    private fun startCaptureWithFallback() {
        if (audio.startCapture(MediaRecorder.AudioSource.VOICE_COMMUNICATION)) return
        if (audio.startCapture(MediaRecorder.AudioSource.MIC)) return
        throw VoiceCallFailure(VoiceCallError.MicSilent)
    }

    /**
     * Mic check: a chunk of audio with sound must arrive within the time limit, and the system must not have muted the mic.
     * Right after switching into call mode, many audio devices first deliver a few all-zero chunks (warming up), so all-zero chunks are skipped and failure is only declared if the whole window stays silent.
     */
    private suspend fun checkMic() {
        val heard = withTimeoutOrNull(micCheckTimeoutMs) {
            var chunk: ShortArray
            do {
                chunk = audio.captureChunks.receiveCatching().getOrNull()
                    ?: throw VoiceCallFailure(VoiceCallError.MicSilent)
                if (audio.micSilenced.value) throw VoiceCallFailure(VoiceCallError.MicSilent)
            } while (chunk.all { it == 0.toShort() })
            chunk
        } ?: throw VoiceCallFailure(VoiceCallError.MicSilent)
        _micLevel.value = levelOf(heard)
    }

    /** The prompt tone is written directly to the playback track; it does not enter the playback queue and is not counted in the level. */
    private suspend fun playTone() {
        val tone = VoiceCallTone.connectTonePcm()
        try {
            audio.writePlayback(tone, tone.size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw VoiceCallFailure(VoiceCallError.Playback)
        }
    }

    private fun greetOnce(realtime: CallRealtimeSession) {
        if (greeted || greeting.isBlank()) return
        if (!realtime.sendGreeting(greeting)) throw VoiceCallFailure(VoiceCallError.Provider)
        echoWindow.append(greeting) // The greeting is also something the assistant said, so it enters the echo window
        greeted = true
    }

    private suspend fun runActive(realtime: CallRealtimeSession) {
        val queue = CallPlaybackQueue()
        try {
            coroutineScope {
                launch { writeLoop(queue) }
                launch { pumpMic(realtime) }
                launch { watchSpeaking() }
                idleMs?.let { limit -> launch { watchIdle(realtime, limit) } }
                // A system call (incoming or outgoing) takes over ours: poll the audio mode during the call and end as an "incoming call" if one comes in
                launch {
                    while (true) {
                        delay(PHONE_CALL_POLL_MS)
                        if (audio.isTelephonyBusy()) {
                            end(EndReason.IncomingCall)
                            return@launch
                        }
                    }
                }
                readEvents(realtime, queue)
            }
        } finally {
            queue.close()
        }
    }

    /** Idle hangup: ask once before [IDLE_WARNING_MS] is up, then hang up at the deadline if nothing happens; a warning window shorter than a minute is halved. */
    private suspend fun watchIdle(realtime: CallRealtimeSession, limit: Long) {
        val warnAfter = (limit - IDLE_WARNING_MS).coerceAtLeast(limit / 2)
        idleWarned = false
        lastActivityNanos = clockNanos()
        while (true) {
            delay(IDLE_POLL_MS)
            val quietMs = (clockNanos() - lastActivityNanos) / 1_000_000L
            if (quietMs >= limit) {
                end(EndReason.Idle)
                return
            }
            if (quietMs >= warnAfter && !idleWarned) {
                idleWarned = true
                if (idleCue.isNotBlank() && realtime.sendCue(idleCue)) continue
                if (idleLine.isNotBlank() && realtime.sendNotice(idleLine)) echoWindow.append(idleLine)
            }
        }
    }

    private suspend fun readEvents(realtime: CallRealtimeSession, queue: CallPlaybackQueue) {
        val assistantText = StringBuilder()
        fun flushAssistantText(final: Boolean) {
            if (assistantText.isEmpty()) return
            val text = assistantText.toString()
            assistantText.clear()
            echoWindow.append(text)
            onAssistantTranscript(text, final)
        }
        fun listen() {
            _turn.value = VoiceCallTurn.Listening
        }
        realtime.events.collect { event ->
            when (event) {
                is VolcRealtimeEvent.Audio -> {
                    markActivity(fromMika = true)
                    if (queue.offer(event.pcm) == null) throw VoiceCallFailure(VoiceCallError.Playback)
                }
                VolcRealtimeEvent.UserSpeaking, VolcRealtimeEvent.AssistantInterrupted -> {
                    if (event == VolcRealtimeEvent.UserSpeaking) markActivity(fromMika = false)
                    // The user spoke again: calls authorized by the previous utterance end here
                    if (event == VolcRealtimeEvent.UserSpeaking) requestGate.onUserTurnStart()
                    queue.invalidate()
                    audio.interruptPlayback()
                    _speakerLevel.value = 0f
                    flushAssistantText(final = false)
                    listen()
                }
                is VolcRealtimeEvent.UserTranscript -> {
                    markActivity(fromMika = false)
                    onUserTranscript(event.text, event.isFinal)
                    if (event.isFinal && event.isCurrentTurn) _turn.value = VoiceCallTurn.Thinking
                    // Register the current turn's final+complete transcript first, so a later function_call is eligible to pass the gate
                    if (event.isFinal && event.isCompleteFinal && event.isCurrentTurn && !event.hasUnresolvedPriorTurn) {
                        requestGate.onUserTranscript(event.text)
                    }
                }
                is VolcRealtimeEvent.AssistantTranscript -> {
                    markActivity(fromMika = true)
                    val room = MAX_ASSISTANT_TURN_CHARS - assistantText.length
                    if (room > 0) {
                        assistantText.append(event.delta.take(room))
                        onAssistantDraft(assistantText.toString())
                    }
                }
                VolcRealtimeEvent.AssistantTextEnded -> flushAssistantText(final = true)
                VolcRealtimeEvent.UserTranscriptionEmpty, VolcRealtimeEvent.UserTranscriptionFailed -> listen()
                is VolcRealtimeEvent.FunctionCall -> handleFunctionCall(realtime, event)
                is VolcRealtimeEvent.Failed -> throw event.error
                VolcRealtimeEvent.Closed -> throw VolcRealtimeException(VolcRealtimeException.Kind.NETWORK)
                else -> Unit
            }
        }
        throw VolcRealtimeException(VolcRealtimeException.Kind.NETWORK)
    }

    /**
     * Front desk tools carrying words first pass the safety gate; the passed words are verified to fall within the current-turn transcript and swapped for the whole utterance before being handed onward;
     * tools without words are handed onward as-is and rejected by the front desk executor (all four tools require words).
     */
    private fun handleFunctionCall(realtime: CallRealtimeSession, event: VolcRealtimeEvent.FunctionCall) {
        if (event.name !in VolcRealtimeProtocol.deskToolNames) return
        val words = event.arguments["words"] ?: return onDeskCall(event.callId, event.name, event.arguments)
        val utterance = (words as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        when (val decision = requestGate.offer(event.callId, utterance)) {
            is VoiceCallDecision.Accept ->
                onDeskCall(event.callId, event.name, JsonObject(event.arguments + ("words" to JsonPrimitive(decision.text))))
            VoiceCallDecision.Reject -> realtime.sendToolResult(event.callId, buildJsonObject {
                put("status", "rejected")
                put("reason", "words_not_verified")
                put("next", "Do not act on this. Only if the user says it again as a new utterance, call the tool once more with their words; the retry must use the new call_id.")
            })
        }
    }

    private suspend fun pumpMic(realtime: CallRealtimeSession) {
        val frameBytes = realtime.inputChunkBytes
        val pacer = CallInputPacer(frameBytes, realtime.inputSampleRate, nowNanos = clockNanos)
        val frame = ByteArray(frameBytes)
        var position = 0
        while (true) {
            val chunk = audio.captureChunks.receiveCatching().getOrNull()
                ?: throw VoiceCallFailure(VoiceCallError.MicSilent)
            val muted = _muted.value
            _micLevel.value = if (muted) 0f else levelOf(chunk)
            var index = 0
            while (index < chunk.size) {
                val count = minOf(chunk.size - index, (frameBytes - position) / 2)
                for (k in 0 until count) {
                    val sample = if (muted) 0 else chunk[index + k].toInt()
                    frame[position + 2 * k] = (sample and 0xFF).toByte()
                    frame[position + 2 * k + 1] = (sample shr 8 and 0xFF).toByte()
                }
                position += count * 2
                index += count
                if (position == frameBytes) {
                    if (!pacer.send { realtime.sendPcm16(frame) }) {
                        throw VolcRealtimeException(VolcRealtimeException.Kind.NETWORK)
                    }
                    position = 0
                }
            }
        }
    }

    /** After barge-in invalidates the generation, late blocks from the old generation are blocked by isCurrent before being written, and never reach the ear. */
    private suspend fun writeLoop(queue: CallPlaybackQueue) {
        val pcm = ByteArray(CallPlaybackQueue.WRITE_BYTES)
        while (true) {
            val block = queue.take(pcm) ?: break
            try {
                if (queue.isCurrent(block)) {
                    audio.writePlayback(pcm, block.byteCount)
                    _speakerLevel.value = levelOf(pcm, block.byteCount)
                    lastSpokeNanos = clockNanos()
                    markActivity(fromMika = true)
                    _turn.value = VoiceCallTurn.Speaking
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                throw VoiceCallFailure(VoiceCallError.Playback)
            } finally {
                queue.release(block)
            }
        }
    }

    /** If playback has stopped for [SPEAKING_IDLE_MS] with no new audio, consider the turn finished and switch back to listening. */
    private suspend fun watchSpeaking() {
        while (true) {
            delay(TURN_POLL_MS)
            if (_turn.value == VoiceCallTurn.Speaking && clockNanos() - lastSpokeNanos >= SPEAKING_IDLE_MS * 1_000_000L) {
                _turn.value = VoiceCallTurn.Listening
                _speakerLevel.value = 0f
            }
        }
    }

    private fun levelOf(chunk: ShortArray): Float {
        if (chunk.isEmpty()) return 0f
        var sum = 0.0
        for (sample in chunk) sum += sample.toDouble() * sample
        return (sqrt(sum / chunk.size) / LEVEL_FULL_SCALE).coerceIn(0.0, 1.0).toFloat()
    }

    private fun levelOf(pcm: ByteArray, byteCount: Int): Float {
        val samples = byteCount / 2
        if (samples <= 0) return 0f
        var sum = 0.0
        for (i in 0 until samples) {
            val sample = ((pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)).toShort()
            sum += sample.toDouble() * sample
        }
        return (sqrt(sum / samples) / LEVEL_FULL_SCALE).coerceIn(0.0, 1.0).toFloat()
    }

    private fun endedFor(reason: EndReason) = VoiceCallState.Ended(
        reason,
        idleMinutes = if (reason == EndReason.Idle) idleMs?.let { ((it + 30_000L) / 60_000L).toInt().coerceAtLeast(1) } ?: 0 else 0,
    )

    private class VoiceCallFailure(val error: VoiceCallError) : Exception()

    companion object {
        /** Default: hang up after 5 minutes of quiet. */
        const val DEFAULT_IDLE_MS = 5 * 60_000L
        /** How long before hanging up to first ask whether you are still there. */
        const val IDLE_WARNING_MS = 30_000L
        const val IDLE_POLL_MS = 1_000L
        const val RESUMED_TIMEOUT_MS = 3_000L
        const val MIC_CHECK_TIMEOUT_MS = 3_000L
        /** Interval for checking the system audio mode during a call: wrap up as soon as an incoming/outgoing call takes over the call audio. */
        const val PHONE_CALL_POLL_MS = 1_000L
        /** How long playback has been stopped before the turn is considered finished. */
        const val SPEAKING_IDLE_MS = 700L
        const val TURN_POLL_MS = 100L
        private const val MAX_ASSISTANT_TURN_CHARS = 1_024
        private const val LEVEL_FULL_SCALE = 8_000.0
    }
}

private fun VolcRealtimeException.Kind.toVoiceCallError(): VoiceCallError = when (this) {
    VolcRealtimeException.Kind.AUTH -> VoiceCallError.Auth
    VolcRealtimeException.Kind.TIMEOUT -> VoiceCallError.ConnectTimeout
    VolcRealtimeException.Kind.RATE_LIMITED -> VoiceCallError.RateLimited
    VolcRealtimeException.Kind.NETWORK -> VoiceCallError.Disconnected
    VolcRealtimeException.Kind.PROTOCOL,
    VolcRealtimeException.Kind.PROVIDER,
    VolcRealtimeException.Kind.BACKPRESSURE,
    -> VoiceCallError.Provider
}
