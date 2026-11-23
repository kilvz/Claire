package com.wangzan101.mika.voice

import com.k2fsa.sherpa.onnx.OnlineRecognizer
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
 * Streaming locally means there is no length cap ([unlimited] is true, like the
 * vendor live-streaming sessions). The recognizer belongs to this one session
 * and is released with it.
 */
internal class SherpaSpeechSession(
    private val recorder: StreamingRecorder,
    private val recognizer: OnlineRecognizer,
) : SpeechSession {
    private val out = SessionEvents()
    override val events: Flow<SpeechEvent> = out.flow
    override val live = true
    override val unlimited = true

    private val stream = recognizer.createStream()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var stable = ""
    private var tail = ""
    private var closed = false
    private val completing = Any()
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
        try {
            for (chunk in recorder.chunks) {
                if (out.finished || out.cancelled) break
                val samples = FloatArray(chunk.size) { chunk[it] / 32768.0f }
                stream.acceptWaveform(samples, SherpaOnnx.SAMPLE_RATE)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val text = recognizer.getResult(stream).text.trim()
                if (recognizer.isEndpoint(stream)) {
                    if (text.isNotBlank()) stable = join(stable, text)
                    tail = ""
                    recognizer.reset(stream)
                    out.send(SpeechEvent.Partial(stable, tail))
                } else if (text != tail) {
                    tail = text
                    out.send(SpeechEvent.Partial(stable, tail))
                }
            }
        } catch (_: Throwable) {
            out.finish(SpeechEvent.Failed(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SherpaFailed)))
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
        runCatching { stream.release() }
        runCatching { recognizer.release() }
        scope.cancel()
    }

    private fun join(a: String, b: String): String = when {
        a.isBlank() -> b
        b.isBlank() -> a
        else -> "$a $b"
    }
}
