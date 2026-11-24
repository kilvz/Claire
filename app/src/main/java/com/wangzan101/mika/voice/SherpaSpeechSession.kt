package com.wangzan101.mika.voice

import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hold-to-talk on the local sherpa-onnx engine: recorder chunks feed the
 * streaming transducer, partials go out as the text grows, and an endpoint (a
 * pause long enough to split sentences) locks the finished segment into
 * [SpeechEvent.Partial]'s stable part.
 *
 * The recognizer is loaded on a background thread, not on the caller's (usually
 * the main) thread: the bundled model is tens of megabytes and the downloaded
 * one hundreds, so loading synchronously made the voice button freeze before
 * the listening card appeared. The recorder starts immediately and queues
 * audio while the recognizer loads (see [PcmRecorder]'s buffer), so speech from
 * the first moment is not lost.
 *
 * Streaming locally means there is no length cap ([unlimited] is true, like the
 * vendor live-streaming sessions). The recognizer belongs to this one session
 * and is released with it.
 */
internal class SherpaSpeechSession(
    private val recorder: StreamingRecorder,
    private val loadRecognizer: () -> OnlineRecognizer,
) : SpeechSession {
    private val out = SessionEvents()
    override val events: Flow<SpeechEvent> = out.flow
    override val live = true
    override val unlimited = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var stable = ""
    @Volatile private var tail = ""
    @Volatile private var closed = false
    private val completing = Any()
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var pump: Job? = null
    private var levels: Job? = null

    init {
        val started = runCatching { recorder.start() }.getOrDefault(false)
        if (!started) {
            out.finish(SpeechEvent.Failed(SpeechFailure.MicUnavailable))
            releaseAll()
        } else {
            pump = scope.launch { pumpAudio() }
            levels = scope.launch {
                recorder.level.collect { if (it > 0f) out.send(SpeechEvent.Level(it)) }
            }
        }
    }

    private suspend fun pumpAudio() {
        val rec = runCatching { loadRecognizer() }.getOrNull()
        val s = rec?.let { runCatching { it.createStream() }.getOrNull() }
        if (rec == null || s == null) {
            if (rec != null) runCatching { rec.release() }
            out.finish(SpeechEvent.Failed(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SherpaFailed)))
            releaseAll()
            return
        }
        // Publish both together: release()/cancel() may run while the load is still
        // in flight and must see either both or neither.
        synchronized(completing) {
            if (closed) {
                runCatching { s.release() }
                runCatching { rec.release() }
                return
            }
            recognizer = rec
            stream = s
        }
        try {
            for (chunk in recorder.chunks) {
                if (out.finished || out.cancelled) break
                val samples = FloatArray(chunk.size) { chunk[it] / 32768.0f }
                s.acceptWaveform(samples, SherpaOnnx.SAMPLE_RATE)
                while (rec.isReady(s)) rec.decode(s)
                val text = rec.getResult(s).text.trim()
                if (rec.isEndpoint(s)) {
                    if (text.isNotBlank()) stable = join(stable, text)
                    tail = ""
                    rec.reset(s)
                    out.send(SpeechEvent.Partial(stable, tail))
                } else if (text != tail) {
                    tail = text
                    out.send(SpeechEvent.Partial(stable, tail))
                }
            }
        } catch (_: Throwable) {
            out.finish(SpeechEvent.Failed(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SherpaFailed)))
            releaseAll()
        }
    }

    override fun release() {
        synchronized(completing) {
            if (closed) return
            closed = true
        }
        scope.launch(Dispatchers.IO) {
            runCatching { recorder.stop() }
            withTimeoutOrNull(2_000) { pump?.join() }
            val text = join(stable, tail)
            out.finish(SpeechEvent.Final(text))
            releaseAll()
        }
    }

    override fun cancel() {
        synchronized(completing) {
            if (closed) return
            closed = true
        }
        out.cancel()
        scope.launch(Dispatchers.IO) {
            runCatching { recorder.stop() }
            withTimeoutOrNull(2_000) { pump?.join() }
            releaseAll()
        }
    }

    private fun releaseAll() {
        levels?.cancel()
        val (rec, s) = synchronized(completing) {
            val r = recognizer
            val st = stream
            recognizer = null
            stream = null
            r to st
        }
        runCatching { s?.release() }
        runCatching { rec?.release() }
        scope.cancel()
    }

    private fun join(a: String, b: String): String = when {
        a.isBlank() -> b
        b.isBlank() -> a
        else -> "$a $b"
    }
}
