package com.wangzan101.mika.voice.ondevice

import com.wangzan101.mika.voice.SessionEvents
import com.wangzan101.mika.voice.SpeechEvent
import com.wangzan101.mika.voice.SpeechFailure
import com.wangzan101.mika.voice.SpeechSession
import com.wangzan101.mika.voice.StreamingRecorder
import com.wangzan101.mika.voice.model.VoiceModelError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hold-to-talk on an on-device streaming pack: recorder chunks feed the transducer (opened through [openStream],
 * which may wait for the shared model to finish loading), partials go out as the text grows, and an endpoint — a pause
 * long enough to split sentences — locks the finished segment into [SpeechEvent.Partial]'s stable part.
 *
 * The recorder starts immediately and queues audio while the model loads (see `PcmRecorder`'s buffer), so speech from
 * the first moment is not lost. Streaming locally has no length cap ([unlimited] is true), costs nothing and never uses
 * the network. The pump owns its [OnDeviceSttStream] and closes it when it exits, so a native stream is never released
 * while a decode is still running.
 */
internal class OnDeviceSpeechSession(
    private val recorder: StreamingRecorder,
    private val openStream: suspend () -> OnDeviceSttStream,
    private val mapError: (Throwable) -> VoiceModelError,
) : SpeechSession {
    private val out = SessionEvents()
    override val events: Flow<SpeechEvent> = out.flow
    override val live = true
    override val unlimited = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var stable = ""
    @Volatile private var tail = ""
    @Volatile private var closed = false
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
        val stream = try {
            openStream()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            runCatching { recorder.stop() }
            out.finish(SpeechEvent.Failed(SpeechFailure.Provider(mapError(e))))
            releaseAll()
            return
        }
        try {
            for (chunk in recorder.chunks) {
                if (out.finished || out.cancelled) break
                stream.accept(chunk)
                val text = stream.text()
                if (stream.isEndpoint()) {
                    if (text.isNotBlank()) stable = join(stable, text)
                    tail = ""
                    stream.reset()
                    out.send(SpeechEvent.Partial(stable, tail))
                } else if (text != tail) {
                    tail = text
                    out.send(SpeechEvent.Partial(stable, tail))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            runCatching { recorder.stop() }
            out.finish(SpeechEvent.Failed(SpeechFailure.Provider(mapError(e))))
            releaseAll()
        } finally {
            runCatching { stream.close() }
        }
    }

    override fun release() {
        if (!markClosed()) return
        scope.launch(Dispatchers.IO) {
            runCatching { recorder.stop() }
            // The pump drains the last chunks and closes its stream; if native decoding is stuck, do not keep the UI waiting
            withTimeoutOrNull(2_000) { pump?.join() }
            out.finish(SpeechEvent.Final(join(stable, tail)))
            releaseAll()
        }
    }

    override fun cancel() {
        if (!markClosed()) return
        out.cancel()
        scope.launch(Dispatchers.IO) {
            runCatching { recorder.stop() }
            withTimeoutOrNull(2_000) { pump?.join() }
            releaseAll()
        }
    }

    private fun markClosed(): Boolean = synchronized(this) {
        if (closed) false else {
            closed = true
            true
        }
    }

    private fun releaseAll() {
        levels?.cancel()
        scope.cancel()
    }

    private fun join(a: String, b: String): String = when {
        a.isBlank() -> b
        b.isBlank() -> a
        else -> "$a $b"
    }
}
