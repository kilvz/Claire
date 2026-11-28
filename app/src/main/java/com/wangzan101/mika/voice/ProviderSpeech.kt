package com.wangzan101.mika.voice

import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.voice.model.VoiceModelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Minimal recorder interface: [PcmRecorder] implements it and tests substitute a fake. */
interface Recorder {
    val level: StateFlow<Float>
    fun start(): Boolean
    fun stop(): ShortArray
    fun cancel()
}

/**
 * The provider path: records while held and only emits [SpeechEvent.Level]; after release it emits [SpeechEvent.Converting],
 * hands the whole recording to [transcribe] (WAV in, text out, throws [ModelError] on failure), and yields Final or Failed(Provider).
 * Recording starts at construction; a recording shorter than [MIN_MS] counts as not understood and the provider is not called.
 * Transcription waits at most [timeoutMs] (default [TRANSCRIBE_TIMEOUT_MS]); a timeout counts as the network being unreachable and gives Failed(Network), so the UI never spins forever.
 */
class ProviderSpeech(
    private val recorder: Recorder,
    private val transcribe: suspend (wav: ByteArray) -> String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val timeoutMs: Long = TRANSCRIBE_TIMEOUT_MS,
) : SpeechSession {
    private val out = SessionEvents()
    override val events: Flow<SpeechEvent> = out.flow
    override val live = false

    private var levels: Job? = null
    private var work: Job? = null
    private var released = false

    init {
        if (!recorder.start()) {
            out.finish(SpeechEvent.Failed(SpeechFailure.MicUnavailable))
        } else {
            levels = scope.launch { recorder.level.collect { out.send(SpeechEvent.Level(it)) } }
        }
    }

    @Synchronized
    override fun release() {
        if (released || out.finished || out.cancelled) return
        released = true
        levels?.cancel()
        // stop waits for the read loop to exit (briefly blocking), so put it on a background thread instead of stalling the main thread
        work = scope.launch {
            val pcm = recorder.stop()
            if (pcm.size < Wav.RATE * MIN_MS / 1000) {
                out.finish(SpeechEvent.Final(""))
                return@launch
            }
            out.send(SpeechEvent.Converting)
            val wav = Wav.encode(pcm)
            // The connection may never respond (the client only has a very long read timeout, no overall timeout): stop waiting at the deadline.
            // Catch errors inside the timeout block and hand out the error unchanged (no coroutine boundary is crossed, so it is not swapped for a copy)
            val result = withTimeoutOrNull(timeoutMs) {
                try {
                    SpeechEvent.Final(transcribe(wav))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ModelError) {
                    SpeechEvent.Failed(SpeechFailure.Provider(e))
                } catch (e: VoiceModelError) {
                    // The voice layer's own categories (pack not installed, on-device recognition failed, ...) reach the UI unchanged instead of being wrapped as "cannot parse the reply"
                    SpeechEvent.Failed(SpeechFailure.Provider(e))
                } catch (e: Exception) {
                    SpeechEvent.Failed(SpeechFailure.Provider(ModelError.BadResponse(e.message.orEmpty(), e)))
                }
            } ?: SpeechEvent.Failed(SpeechFailure.Network)
            out.finish(result)
        }
    }

    @Synchronized
    override fun cancel() {
        if (!out.cancel()) return
        levels?.cancel()
        work?.cancel()
        recorder.cancel()
    }

    companion object {
        /** Recordings shorter than this are most likely accidental touches and count as not understood. */
        const val MIN_MS = 500

        /** Upper bound for waiting on the provider's transcription after release; a recording of at most 60 seconds normally transcribes within a few seconds. */
        const val TRANSCRIBE_TIMEOUT_MS = 30_000L
    }
}
