package com.wangzan101.mika.voice.ondevice

import com.wangzan101.mika.voice.SpeechEvent
import com.wangzan101.mika.voice.SpeechFailure
import com.wangzan101.mika.voice.SpeechSession
import com.wangzan101.mika.voice.StreamingRecorder
import com.wangzan101.mika.voice.model.VoiceModelError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceSpeechSessionTest {
    private class Capture(private val starts: Boolean = true) : StreamingRecorder {
        override val level = MutableStateFlow(0f)
        override val chunks = Channel<ShortArray>(8)
        var stopped = false
        override fun start() = starts
        override fun stop(): ShortArray {
            stopped = true
            chunks.close()
            return ShortArray(0)
        }
        override fun cancel() { chunks.cancel() }
    }

    /** Two segments: the second accept ends the first; reset starts afresh. */
    private class Stream : OnDeviceSttStream {
        private var total = 0
        private var endpoint = false
        @Volatile var closed = false

        @Synchronized override fun accept(pcm: ShortArray) {
            total++
            endpoint = total == 2
        }

        @Synchronized override fun text(): String = if (total <= 2) "hello" else "world"

        @Synchronized override fun isEndpoint(): Boolean = endpoint

        @Synchronized override fun reset() {
            endpoint = false
        }

        override fun close() { closed = true }
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(5_000) { block() } }

    private fun CoroutineScope.collect(session: SpeechSession): Channel<SpeechEvent> = Channel<SpeechEvent>(32).also { output ->
        launch { session.events.filter { it !is SpeechEvent.Level }.collect { output.send(it) }; output.close() }
    }

    @Test fun partialsGrowAndAnEndpointStartsANewSegment() = test {
        val capture = Capture()
        val stream = Stream()
        val session = OnDeviceSpeechSession(capture, { stream }) { VoiceModelError.OnDeviceFailed("failed", it) }
        val events = collect(session)
        assertTrue(session.live)
        assertTrue(session.unlimited)
        capture.chunks.send(ShortArray(4))
        assertEquals(SpeechEvent.Partial("", "hello"), events.receive())
        capture.chunks.send(ShortArray(4))
        assertEquals(SpeechEvent.Partial("hello", ""), events.receive())
        capture.chunks.send(ShortArray(4))
        assertEquals(SpeechEvent.Partial("hello", "world"), events.receive())
        session.release()
        assertEquals(SpeechEvent.Final("hello world"), events.receive())
        assertTrue(events.receiveCatching().isClosed)
        assertTrue(capture.stopped)
        assertTrue(stream.closed)
    }

    @Test fun aMicrophoneThatCannotStartFailsWithoutOpeningTheModel() = test {
        var opened = false
        val session = OnDeviceSpeechSession(Capture(starts = false), { opened = true; Stream() }) {
            VoiceModelError.OnDeviceFailed("failed", it)
        }
        assertEquals(SpeechEvent.Failed(SpeechFailure.MicUnavailable), collect(session).receive())
        assertFalse(opened)
    }

    @Test fun aModelThatFailsToOpenReportsAProviderFailure() = test {
        val error = VoiceModelError.OnDeviceFailed("cannot load")
        val session = OnDeviceSpeechSession(Capture(), { throw error }) { it as VoiceModelError }
        assertEquals(SpeechEvent.Failed(SpeechFailure.Provider(error)), collect(session).receive())
    }

    @Test fun cancelClosesTheStreamWithoutAFinalText() = test {
        val capture = Capture()
        val stream = Stream()
        val session = OnDeviceSpeechSession(capture, { stream }) { VoiceModelError.OnDeviceFailed("failed", it) }
        val events = collect(session)
        capture.chunks.send(ShortArray(4))
        assertEquals(SpeechEvent.Partial("", "hello"), events.receive())
        session.cancel()
        assertTrue(events.receiveCatching().isClosed)
        // Stopping the recorder and closing the stream happen off the main thread; wait for the cleanup rather than racing it
        withTimeout(2_000) { while (!capture.stopped || !stream.closed) delay(10) }
        assertTrue(capture.stopped)
        assertTrue(stream.closed)
    }
}
