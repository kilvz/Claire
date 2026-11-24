package com.wangzan101.mika.terminal

import com.wangzan101.mika.core.cap.sandbox.SandboxShell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.concurrent.thread

class TerminalSessionTest {
    private class FakeShell : SandboxShell {
        private val toApp = PipedInputStream(64 * 1024)
        private val fromApp = PipedInputStream(64 * 1024)
        val writer = PipedOutputStream(toApp)
        private val appWriter = PipedOutputStream(fromApp)
        val appWritten: InputStream = fromApp
        override val input: OutputStream = appWriter
        override val output: InputStream = toApp
        override val exited = CompletableDeferred<Int>()
        override val revoked = MutableStateFlow(false)
        @Volatile var closed = false
        override fun close() {
            closed = true
            runCatching { writer.close() }
            runCatching { appWriter.close() }
            runCatching { toApp.close() }
            runCatching { fromApp.close() }
        }
    }

    @Test fun outputGoesToRingBufferAndStateTracksExit() = runBlocking {
        val shell = FakeShell()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = TerminalSession("c1", "one", network = false, shell = shell, scope = scope)
        try {
            shell.writer.write("hello\n".toByteArray()); shell.writer.flush()
            withTimeout(2_000) {
                while (!String(session.snapshot()).contains("hello")) Thread.sleep(10)
            }
            assertEquals("hello\n", String(session.snapshot()))

            val input = shell.appWritten
            session.write("ls -la")
            val got = ByteArray(16)
            val n = withTimeout(2_000) { input.read(got) }
            assertEquals("ls -la\n", String(got, 0, n))
            assertEquals("ls -la", session.history.last())

            shell.writer.close()
            shell.exited.complete(7)
            withTimeout(2_000) {
                while (session.phase.value != TerminalSession.Phase.Exited) Thread.sleep(10)
            }
            assertEquals(7, session.exitCode.value)
            withTimeout(2_000) {
                while (!shell.closed || shell.revoked.subscriptionCount.value != 0) delay(10)
            }
        } finally {
            session.close()
            scope.cancel()
        }
        assertTrue(shell.closed)
    }

    /** A viewer that momentarily falls behind (the main thread stalls for a bit) loses no chunks: a dropped chunk would leave a gap on screen and could even cut an escape sequence or half a character. */
    @Test fun aViewerThatFallsBehindStillGetsEveryChunk() = runBlocking {
        val shell = FakeShell()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = TerminalSession("c3", null, network = false, shell = shell, scope = scope)
        val seen = ByteArrayOutputStream()
        val subscribed = CompletableDeferred<Unit>()
        val holding = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        try {
            val viewer = scope.launch {
                session.output.onSubscription { subscribed.complete(Unit) }.collect { chunk ->
                    holding.complete(Unit)
                    resume.await()
                    synchronized(seen) { seen.write(chunk) }
                }
            }
            withTimeout(2_000) { subscribed.await() }
            // Write the first 66 lines one line per chunk: the viewer holds 1 chunk and the buffer holds 64, so chunk 66 either evicts the oldest one or makes the side reading the output wait.
            // Every chunk goes into the ring buffer before it reaches the viewer, so all 66 lines arrive under either strategy; the rest is written in one go
            for (i in 1..66) {
                val line = "line-$i\n"
                shell.writer.write(line.toByteArray()); shell.writer.flush()
                withTimeout(2_000) { while (!String(session.snapshot()).endsWith(line)) delay(2) }
                // Let the viewer get chunk 1 before the rest is written, so the buffer fills starting from chunk 2
                if (i == 1) withTimeout(2_000) { holding.await() }
            }
            shell.writer.write((67..100).joinToString("") { "line-$it\n" }.toByteArray()); shell.writer.flush()
            resume.complete(Unit)
            val all = (1..100).joinToString("") { "line-$it\n" }
            withTimeout(5_000) { while (String(session.snapshot()) != all) delay(10) }
            withTimeoutOrNull(2_000) { while (synchronized(seen) { seen.size() } < all.length) delay(10) }
            assertEquals(all, synchronized(seen) { seen.toString() })
            viewer.cancel()
        } finally {
            session.close()
            scope.cancel()
        }
    }

    /** When nobody reads the input (the sandbox side is waiting on the viewer), writing input must not block the caller either: keystroke lines are written on the main thread. */
    @Test fun writingDoesNotBlockTheCallerWhenInputIsNotRead() = runBlocking {
        val shell = FakeShell()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = TerminalSession("c4", null, network = false, shell = shell, scope = scope)
        try {
            val caller = Thread { session.writeBytes(ByteArray(200_000)); session.writeBytes("\u001b[A".toByteArray()) }
            caller.start()
            caller.join(2_000)
            assertFalse("writeBytes waited for the shell to read its input", caller.isAlive)
        } finally {
            session.close()
            scope.cancel()
        }
    }

    @Test fun closingDoesNotWaitForShellCleanupOnTheCaller() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val backing = FakeShell()
        val shell = object : SandboxShell by backing {
            override fun close() {
                entered.countDown()
                release.await(5, SECONDS)
                backing.close()
                cleaned.countDown()
            }
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
        val session = TerminalSession("c5", null, network = false, shell = shell, scope = scope)
        val caller = thread(isDaemon = true) {
            session.close()
            returned.countDown()
        }
        try {
            assertTrue(entered.await(2, SECONDS))
            assertTrue("close waited for shell cleanup", returned.await(1, SECONDS))
            assertEquals(TerminalSession.Phase.Closed, session.phase.value)
        } finally {
            release.countDown()
            caller.join(2_000)
            scope.cancel()
            assertTrue(cleaned.await(2, SECONDS))
        }
    }

    @Test fun revocationWinsOverExit() = runBlocking {
        val shell = FakeShell()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = TerminalSession("c2", null, network = false, shell = shell, scope = scope)
        try {
            shell.revoked.value = true
            withTimeout(2_000) {
                while (session.phase.value != TerminalSession.Phase.Revoked) Thread.sleep(10)
            }
            assertEquals(TerminalSession.Phase.Revoked, session.phase.value)
        } finally {
            session.close()
            scope.cancel()
        }
    }

    @Test fun ringBufferKeepsTheNewestBytes() {
        val ring = TerminalRingBuffer(8)
        ring.append("abc".toByteArray())
        ring.append("defgh".toByteArray())
        assertEquals("abcdefgh", String(ring.snapshot()))
        assertEquals(0, ring.droppedBytes())
        ring.append("ijkl".toByteArray())
        assertEquals("efghijkl", String(ring.snapshot()))
        assertEquals(4, ring.droppedBytes())
        ring.append("0123456789".toByteArray())
        assertEquals("23456789", String(ring.snapshot()))
        assertEquals(6, ring.droppedBytes())
    }
}
