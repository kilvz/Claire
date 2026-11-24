package com.wangzan101.mika.voice.cascade

import com.wangzan101.mika.calls.CallRealtimeSession
import com.wangzan101.mika.calls.VolcRealtimeEvent
import com.wangzan101.mika.calls.VolcRealtimeException
import com.wangzan101.mika.core.frontdesk.DeskBrain
import com.wangzan101.mika.core.frontdesk.ReplyContext
import com.wangzan101.mika.voice.Pcm16
import com.wangzan101.mika.voice.SpeechEvent
import com.wangzan101.mika.voice.SpeechFailure
import com.wangzan101.mika.voice.SpeechSession
import com.wangzan101.mika.voice.model.VoiceErrorCategory
import com.wangzan101.mika.voice.model.VoiceModelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** What one utterance turned into. */
private sealed interface Heard {
    data class Text(val text: String) : Heard
    data class Failed(val error: Throwable?) : Heard
}

/**
 * A standard (listen → think → speak) call behind the same seam as a realtime voice model, so the phone bridge and
 * the in-app voice call drive it unchanged.
 *
 * - Listen: incoming 16 kHz PCM is cut into utterances by loudness ([UtteranceDetector]); each utterance is fed to
 *   its own speech-to-text session through a [FeedRecorder] and closed after [silenceMs] of quiet.
 * - Think: finished utterances, in order, go to the [brain]. Utterances that finish while the user is already talking
 *   again are held and joined with the next one, so one thought gets one reply.
 * - Speak: reply text is cut at sentence marks and commas, each piece is synthesized and sent as 24 kHz PCM, paced
 *   so at most [aheadMs] of audio is queued ahead of real time.
 *
 * When the other side talks over the assistant ([bargeIn]) the reply stops; without barge-in, input is ignored while
 * the assistant is audible.
 */
class CascadeVoiceSession internal constructor(
    private val brain: DeskBrain,
    private val listen: (FeedRecorder) -> SpeechSession,
    private val speak: suspend (String) -> PcmAudioOut,
    silenceMs: Int,
    private val bargeIn: Boolean,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val aheadMs: Long = 5_000,
    private val chunkMs: Int = 100,
    private val maxFailures: Int = 3,
) : CallRealtimeSession {
    /** Synthesized speech: 16-bit mono PCM at [sampleRate]. */
    class PcmAudioOut(val pcm: ByteArray, val sampleRate: Int)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val out = Channel<VolcRealtimeEvent>(Channel.UNLIMITED)
    override val events: Flow<VolcRealtimeEvent> = out.receiveAsFlow()
    override val inputSampleRate: Int get() = INPUT_RATE
    override val outputSampleRate: Int get() = OUTPUT_RATE
    override val inputChunkBytes: Int get() = INPUT_RATE / 50 * 2

    private val lock = Any()
    private val detector = UtteranceDetector(silenceMs, INPUT_RATE)
    @Volatile private var closed = false
    @Volatile private var muted = false

    // Listening state, guarded by [lock].
    private var recorder: FeedRecorder? = null
    private var speech: SpeechSession? = null
    private var turnSerial = 0
    private val turns = Channel<Pair<Int, CompletableDeferred<Heard>>>(Channel.UNLIMITED)
    private var held = ""
    /** User utterances started but not yet thought through: while any exist, the user is speaking or just finished and has not been answered. */
    private var unheard = 0

    // Speaking state.
    @Volatile private var reply: Job? = null
    // Keep cancelled replies until they finish, even if a replacement is itself interrupted while waiting.
    private val replyJobs = mutableSetOf<Job>() // guarded by lock
    // Background messages waiting to be spoken, each with its completion callback: they get a turn once the current reply is done and the user is not speaking; queued user interjections are not lost either. guarded by lock
    private val notices = ArrayDeque<Pair<String, (Boolean) -> Unit>>()
    @Volatile private var audibleUntil = 0L
    private val failures = AtomicInteger(0)
    private val callIds = AtomicInteger(0)
    /** Call ids stay unique across reconnects (the in-app call never accepts a replayed id). */
    private val sessionTag = java.util.UUID.randomUUID().toString().take(8)
    private val pendingTools = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val orphanedTools = ConcurrentHashMap<String, String>()

    init {
        scope.launch { thinkLoop() }
    }

    override fun sendPcm16(bytes: ByteArray, byteCount: Int): Boolean {
        if (closed) return false
        val samples = Pcm16.toSamples(bytes, byteCount)
        synchronized(lock) {
            if (muted) return true
            val audible = isAudible()
            if (audible && !bargeIn) {
                if (detector.inSpeech) endUtterance(cancel = true)
                return true
            }
            var at = 0
            while (at < samples.size) {
                val end = minOf(samples.size, at + FRAME)
                val frame = samples.copyOfRange(at, end)
                at = end
                when (detector.push(frame, strict = audible)) {
                    UtteranceDetector.Signal.Start -> startUtterance(detector.takePreroll())
                    UtteranceDetector.Signal.Speech -> recorder?.feed(frame)
                    UtteranceDetector.Signal.End -> endUtterance(cancel = false)
                    UtteranceDetector.Signal.None -> Unit
                }
            }
        }
        return true
    }

    override fun sendGreeting(text: String): Boolean {
        if (closed || text.isBlank()) return false
        startReply {
            brain.remember(text)
            it.say(text)
        }
        return true
    }

    override fun setMuted(muted: Boolean): Boolean {
        if (closed) return false
        synchronized(lock) {
            this.muted = muted
            if (muted && detector.inSpeech) endUtterance(cancel = true)
            if (muted) detector.reset()
        }
        return true
    }

    override fun sendToolResult(callId: String, result: JsonObject): Boolean {
        if (closed) return false
        pendingTools.remove(callId)?.let { return it.complete(result) }
        val name = orphanedTools.remove(callId) ?: return false
        // The reply that asked has been talked over; answer the result as a turn of its own.
        startReply { brain.lateResult(name, result, it) }
        return true
    }

    override fun sendNotice(text: String, ended: (spoken: Boolean) -> Unit): Boolean {
        if (closed || text.isBlank()) return false
        synchronized(lock) {
            notices.addLast(text to ended)
            nextNotice()
        }
        return true
    }

    /** In a standard call a report is itself a turn recorded in the model history and answered by the model (see [DeskBrain.notice]), not read out verbatim: cues take the same path. */
    override fun sendCue(text: String, ended: (spoken: Boolean) -> Unit): Boolean = sendNotice(text, ended)

    override fun sendUserText(text: String): Boolean {
        if (closed || text.isBlank()) return false
        startReply { brain.reply(text, it) }
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        val unspoken = synchronized(lock) {
            speech?.cancel()
            recorder?.cancel()
            speech = null
            recorder = null
            notices.toList().also { notices.clear() }
        }
        scope.cancel()
        // Messages that never got their turn still get an answer: count them as not spoken and let the caller take another path
        unspoken.forEach { (_, ended) -> ended(false) }
        pendingTools.values.forEach { it.cancel() }
        out.trySend(VolcRealtimeEvent.Closed)
        out.close()
    }

    // ---- listen ----

    private fun startUtterance(preroll: ShortArray) {
        interrupt()
        emit(VolcRealtimeEvent.UserSpeaking)
        val feed = FeedRecorder()
        val heard = CompletableDeferred<Heard>()
        val session = try {
            listen(feed)
        } catch (e: Exception) {
            heard.complete(Heard.Failed(e))
            null
        }
        recorder = feed
        speech = session
        feed.feed(preroll)
        unheard++
        turns.trySend(++turnSerial to heard)
        if (session != null) scope.launch {
            try {
                session.events.collect { event ->
                    when (event) {
                        is SpeechEvent.Final -> heard.complete(Heard.Text(event.text.trim()))
                        is SpeechEvent.Failed -> heard.complete(Heard.Failed(failureOf(event.why)))
                        else -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                heard.complete(Heard.Failed(e))
            }
            heard.complete(Heard.Text(""))
        }
    }

    private fun failureOf(why: SpeechFailure): Throwable? = when (why) {
        is SpeechFailure.Provider -> why.error
        is SpeechFailure.Unavailable -> VoiceModelError.IncompleteConfig("Speech recognition cannot listen to a call")
        else -> null
    }

    private fun endUtterance(cancel: Boolean) {
        val session = speech
        speech = null
        recorder = null
        if (cancel) {
            detector.reset()
            session?.cancel()
        } else {
            session?.release()
            emit(VolcRealtimeEvent.UserSpeechEnded)
        }
    }

    // ---- think ----

    private suspend fun thinkLoop() {
        for ((serial, heard) in turns) {
            val result = heard.await()
            val current = synchronized(lock) { serial == turnSerial && !detector.inSpeech }
            val stop = try {
                think(result, current)
            } finally {
                // This utterance has been thought through (the reply has begun, or it was not heard clearly): if the user is not speaking now, queued messages get a turn
                synchronized(lock) {
                    unheard--
                    nextNotice()
                }
            }
            if (stop) return
        }
    }

    /** Answers one finished utterance; true when a failure ends the call. */
    private fun think(result: Heard, current: Boolean): Boolean {
        when (result) {
            is Heard.Failed -> {
                emit(VolcRealtimeEvent.UserTranscriptionFailed)
                return fatal(result.error)
            }
            is Heard.Text -> {
                failures.set(0)
                val text = listOf(held, result.text).filter { it.isNotBlank() }.joinToString(" ")
                when {
                    text.isBlank() -> emit(VolcRealtimeEvent.UserTranscriptionEmpty)
                    !current -> held = text
                    else -> {
                        held = ""
                        emit(VolcRealtimeEvent.UserTranscript(text, isFinal = true, isCompleteFinal = true, isCurrentTurn = true))
                        startReply { brain.reply(text, it) }
                    }
                }
                return false
            }
        }
    }

    /** Records one failure; true (and the call is failed) when it cannot recover. */
    private fun fatal(error: Throwable?): Boolean {
        val category = error?.let { VoiceModelError.from(it).category }
        val kind = when {
            category == VoiceErrorCategory.AUTH_FAILED || category == VoiceErrorCategory.MISSING_CREDENTIALS ||
                category == VoiceErrorCategory.INCOMPLETE_CONFIG || category == VoiceErrorCategory.UNSUPPORTED ->
                VolcRealtimeException.Kind.AUTH
            failures.incrementAndGet() >= maxFailures -> when (category) {
                VoiceErrorCategory.RATE_LIMITED -> VolcRealtimeException.Kind.RATE_LIMITED
                VoiceErrorCategory.TIMEOUT -> VolcRealtimeException.Kind.TIMEOUT
                else -> VolcRealtimeException.Kind.NETWORK
            }
            else -> return false
        }
        emit(VolcRealtimeEvent.Failed(VolcRealtimeException(kind)))
        return true
    }

    // ---- speak ----

    private fun isAudible(): Boolean = audibleUntil > clock()

    /** Stops the current reply; the bridge is told so it drops audio already queued. */
    private fun interrupt() {
        val job = reply ?: return
        reply = null
        job.cancel()
        if (isAudible()) emit(VolcRealtimeEvent.AssistantInterrupted)
        audibleUntil = 0
    }

    /**
     * Speak the next background message when not replying and the user is not speaking (no mid-utterance or just-finished-but-unanswered speech);
     * otherwise wait (the calls come again when the reply ends or this utterance is thought through). Called under [lock].
     */
    private fun nextNotice() {
        if (closed || reply?.isActive == true || detector.inSpeech || unheard > 0) return
        val (text, ended) = notices.removeFirstOrNull() ?: return
        startReply(interruptCurrent = false, ended = ended) { brain.notice(text, it) }
    }

    /** [ended] is called once when this reply winds up: true if it was spoken in full, false when interrupted, failed, or the call dropped. */
    private fun startReply(
        interruptCurrent: Boolean = true,
        ended: ((Boolean) -> Unit)? = null,
        block: suspend (ReplyContext) -> Unit,
    ) {
        synchronized(lock) {
            // The new reply waits for the old one to unwind (it closes its tool calls in the brain's history).
            if (interruptCurrent) interrupt()
            val previous = replyJobs.toList()
            var spoken = false
            val next = scope.launch {
                previous.forEach { it.join() }
                try {
                    speakReply(block)
                    // When something must be reported as spoken or not, wait for the audio already queued to finish playing; a barge-in during this stretch still counts as not spoken
                    if (ended != null) while (isAudible()) delay(chunkMs.toLong())
                    spoken = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fatal(e)
                }
            }
            replyJobs += next
            next.invokeOnCompletion { cause ->
                synchronized(lock) {
                    replyJobs.remove(next)
                    nextNotice()
                }
                ended?.invoke(spoken && cause == null)
            }
            reply = next
        }
    }

    private suspend fun speakReply(block: suspend (ReplyContext) -> Unit) = coroutineScope {
        val pieces = Channel<String>(Channel.UNLIMITED)
        val splitter = SentenceSplitter()
        val player = launch { play(pieces) }
        val ctx = object : ReplyContext {
            override suspend fun say(delta: String) {
                splitter.push(delta).forEach { pieces.send(it) }
            }

            override suspend fun callTool(name: String, arguments: JsonObject): JsonObject? {
                val id = "cascade_${sessionTag}_${callIds.incrementAndGet()}"
                val result = CompletableDeferred<JsonObject>()
                pendingTools[id] = result
                emit(VolcRealtimeEvent.FunctionCall(id, name, arguments))
                return try {
                    result.await()
                } catch (e: CancellationException) {
                    if (pendingTools.remove(id) != null) orphanedTools[id] = name
                    throw e
                }
            }
        }
        try {
            block(ctx)
            splitter.flush()?.let { pieces.send(it) }
        } finally {
            pieces.close()
        }
        player.join()
    }

    private suspend fun play(pieces: Channel<String>) {
        var started = false
        for (piece in pieces) {
            val audio = try {
                speak(piece)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (fatal(e)) return
                continue
            }
            failures.set(0)
            val pcm = toOutput(audio)
            if (pcm.isEmpty()) continue
            if (!started) {
                started = true
                emit(VolcRealtimeEvent.AssistantAudioStarted)
            }
            emit(VolcRealtimeEvent.AssistantTranscript(piece))
            val chunk = OUTPUT_RATE * 2 * chunkMs / 1000
            var at = 0
            while (at < pcm.size) {
                while (audibleUntil - clock() > aheadMs) delay(chunkMs.toLong())
                val end = minOf(pcm.size, at + chunk)
                val bytes = pcm.copyOfRange(at, end)
                at = end
                val now = clock()
                audibleUntil = maxOf(audibleUntil, now) + bytes.size * 1000L / (OUTPUT_RATE * 2)
                emit(VolcRealtimeEvent.Audio(bytes))
            }
        }
        if (started) {
            emit(VolcRealtimeEvent.AssistantTextEnded)
            emit(VolcRealtimeEvent.AssistantAudioEnded)
        }
    }

    private fun toOutput(audio: PcmAudioOut): ByteArray {
        if (audio.sampleRate == OUTPUT_RATE) return audio.pcm
        val samples = Pcm16.toSamples(audio.pcm)
        return Pcm16.toBytes(Pcm16.resample(samples, audio.sampleRate, OUTPUT_RATE))
    }

    private fun emit(event: VolcRealtimeEvent) {
        if (!closed) out.trySend(event)
    }

    companion object {
        const val INPUT_RATE = 16_000
        const val OUTPUT_RATE = 24_000
        private const val FRAME = INPUT_RATE / 50
    }
}
