package com.wangzan101.mika.voice

import com.wangzan101.mika.core.model.ModelError
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A session that transcribes while you speak: it uploads and collects results while held, winds down after release, and yields Final only when the server says it is finished. Runs on the self-hosted Fun-ASR protocol. */
class LiveSpeechTest {
    private class Capture(private val starts: Boolean = true) : StreamingRecorder {
        override val level = MutableStateFlow(0f)
        override val chunks = Channel<ShortArray>(100)
        var cancelled = false
        override fun start() = starts
        override fun stop(): ShortArray { chunks.close(); return shortArrayOf() }
        override fun cancel() { cancelled = true; chunks.cancel() }
    }

    private sealed interface Out {
        data class Text(val text: String) : Out
        class Audio(val pcm: ByteArray) : Out
    }

    private class Socket : LiveAsrSocket {
        override val incoming = Channel<String>(32)
        val sent = Channel<Out>(64)
        var closed = false
        override fun sendText(text: String) { check(sent.trySend(Out.Text(text)).isSuccess) }
        override fun sendAudio(pcm: ByteArray) { check(sent.trySend(Out.Audio(pcm)).isSuccess) }
        override fun close() { closed = true; incoming.cancel() }
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(5_000) { block() } }

    private fun CoroutineScope.collect(session: SpeechSession): Channel<SpeechEvent> = Channel<SpeechEvent>(32).also { output ->
        launch { session.events.filter { it !is SpeechEvent.Level }.collect { output.send(it) }; output.close() }
    }

    private suspend fun Socket.text() = (sent.receive() as Out.Text).text
    private suspend fun Socket.audio() = (sent.receive() as Out.Audio).pcm

    @Test fun uploadsWhileHeldAndGivesFinalOnlyAfterStopped() = test {
        val capture = Capture(); val socket = Socket()
        val session = LiveSpeech(capture, FunAsrProtocol(), { socket }, this)
        val events = collect(session)
        assertTrue(session.live)
        // Upload while speaking: holding for any length of time is fine
        assertTrue(session.unlimited)
        assertEquals("START", socket.text())
        // Audio recorded before the server confirms is held back for now
        repeat(2) { capture.chunks.send(ShortArray(800) { 7 }) }
        yield()
        assertTrue(socket.sent.tryReceive().isFailure)
        socket.incoming.send("""{"event": "started"}""")
        val first = socket.audio()
        assertEquals(LiveSpeech.PACKET_SAMPLES * 2, first.size)
        assertEquals(7.toByte(), first[0]); assertEquals(0.toByte(), first[1]) // Little endian
        socket.incoming.send("""{"sentences": [], "partial": "你好", "is_final": false}""")
        assertEquals(SpeechEvent.Partial("", "你好"), events.receive())
        // An identical snapshot is not sent again
        socket.incoming.send("""{"sentences": [], "partial": "你好", "is_final": false}""")
        socket.incoming.send("""{"sentences": [{"text": "你好。"}], "partial": "打开", "is_final": false}""")
        assertEquals(SpeechEvent.Partial("你好。", "打开"), events.receive())
        capture.chunks.send(shortArrayOf(1, 2, 3))
        session.release(); session.release()
        assertEquals(SpeechEvent.Converting, events.receive())
        // A tail shorter than one packet is still sent, followed by the closing message
        assertArrayEquals(byteArrayOf(1, 0, 2, 0, 3, 0), socket.audio())
        assertEquals("STOP", socket.text())
        socket.incoming.send("""{"sentences": [{"text": "你好。"}, {"text": "打开微信。"}], "partial": "", "is_final": true}""")
        assertEquals(SpeechEvent.Partial("你好。打开微信。", ""), events.receive())
        assertTrue(events.tryReceive().isFailure) // Even once the final text arrives, stopped is still awaited
        socket.incoming.send("""{"event": "stopped"}""")
        assertEquals(SpeechEvent.Final("你好。打开微信。"), events.receive())
        assertTrue(events.receiveCatching().isClosed)
        yield()
        assertTrue(socket.closed); assertTrue(capture.cancelled)
    }

    // Disconnected before the end arrived: fail, and do not treat unfinished intermediate text as the final text (whatever is handed off gets sent to the model)
    @Test fun disconnectBeforeEndFails() = test {
        val capture = Capture(); val socket = Socket()
        val session = LiveSpeech(capture, FunAsrProtocol(), { socket }, this)
        val events = collect(session)
        socket.text(); socket.incoming.send("""{"event": "started"}""")
        socket.incoming.send("""{"sentences": [], "partial": "一半", "is_final": false}""")
        assertEquals(SpeechEvent.Partial("", "一半"), events.receive())
        socket.incoming.close()
        assertEquals(SpeechEvent.Failed(SpeechFailure.Network), events.receive())
    }

    @Test fun finalWaitTimesOut() = test {
        val capture = Capture(); val socket = Socket()
        val session = LiveSpeech(capture, FunAsrProtocol(), { socket }, this, finalTimeoutMs = 30)
        val events = collect(session)
        socket.text(); socket.incoming.send("""{"event": "started"}""")
        session.release()
        assertEquals(SpeechEvent.Converting, events.receive())
        assertEquals("STOP", socket.text())
        assertEquals(SpeechEvent.Failed(SpeechFailure.Network), events.receive())
        yield()
        assertTrue(socket.closed)
    }

    @Test fun serviceErrorsAndAuthAreReported() = test {
        val socket = Socket()
        val session = LiveSpeech(Capture(), BailianProtocol("fun-asr-realtime"), { socket }, this)
        val events = collect(session)
        socket.text()
        socket.incoming.send("""{"header":{"event":"task-failed","error_code":"InvalidApiKey","error_message":"x"},"payload":{}}""")
        val failed = events.receive() as SpeechEvent.Failed
        assertTrue((failed.why as SpeechFailure.Provider).error is ModelError.Auth)

        val handshake = LiveSpeech(Capture(), FunAsrProtocol(), { throw ModelError.Auth() }, this)
        val second = collect(handshake).receive() as SpeechEvent.Failed
        assertTrue((second.why as SpeechFailure.Provider).error is ModelError.Auth)
    }

    @Test fun micThatDoesNotStartFailsAtOnce() = test {
        val events = collect(LiveSpeech(Capture(starts = false), FunAsrProtocol(), { error("not called") }, this))
        assertEquals(SpeechEvent.Failed(SpeechFailure.MicUnavailable), events.receive())
    }

    @Test fun cancelDropsLateResults() = test {
        val capture = Capture(); val socket = Socket(); val ready = CompletableDeferred<Unit>()
        val session = LiveSpeech(capture, FunAsrProtocol(), { ready.await(); socket }, this)
        val events = collect(session)
        session.cancel(); session.cancel()
        ready.complete(Unit)
        assertTrue(events.receiveCatching().isClosed)
        assertTrue(capture.cancelled)
    }

    @Test fun bailianPacketsArePacedAndFunAsrIsNot() {
        assertEquals(50L, BailianProtocol("fun-asr-realtime").packetGapMs)
        assertEquals(0L, FunAsrProtocol().packetGapMs)
    }

    @Test fun probePassesOnStartAndSendsClosing() = test {
        val socket = Socket()
        val done = launch { probeLiveAsr(FunAsrProtocol(), { socket }) }
        assertEquals("START", socket.text())
        socket.incoming.send("""{"event": "started"}""")
        assertEquals("STOP", socket.text())
        done.join()
        assertTrue(socket.closed)
    }

    @Test fun probeReportsRefusalAndEarlyClose() {
        runBlocking {
            val refused = Socket()
            refused.incoming.send("""{"header":{"event":"task-failed","error_code":"Arrearage"},"payload":{}}""")
            val e = runCatching { probeLiveAsr(BailianProtocol("fun-asr-realtime"), { refused }) }.exceptionOrNull()
            assertTrue((e as LiveAsrError).auth)
            val closed = Socket().also { it.incoming.close() }
            assertTrue(runCatching { probeLiveAsr(FunAsrProtocol(), { closed }) }.exceptionOrNull() is IOException)
        }
        assertThrows(Exception::class.java) { runBlocking { probeLiveAsr(FunAsrProtocol(), { Socket() }, timeoutMs = 20) } }
    }

    @Test fun joinAddsSpacesOnlyBetweenLatinWords() {
        assertEquals("你好世界", joinAsr(listOf("你好", "世界")))
        assertEquals("hello world", joinAsr(listOf("hello", "", "world")))
        assertEquals("hello.World", joinAsr(listOf("hello.", "World")))
        assertEquals("", joinAsr(emptyList()))
    }
}
