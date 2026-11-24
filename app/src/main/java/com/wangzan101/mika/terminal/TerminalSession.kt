package com.wangzan101.mika.terminal

import com.wangzan101.mika.core.cap.sandbox.SandboxShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Ring buffer: keeps the most recent [cap] bytes; the UI rebuilds the screen from it when re-attaching. */
class TerminalRingBuffer(private val cap: Int) {
    private val buf = ByteArray(cap)
    private var start = 0
    private var size = 0
    private var dropped = 0L

    @Synchronized
    fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (bytes.size >= cap) {
            bytes.copyInto(buf, 0, bytes.size - cap)
            start = 0
            size = cap
            dropped += bytes.size.toLong() - cap
            return
        }
        val overflow = size + bytes.size - cap
        if (overflow > 0) {
            start = (start + overflow) % cap
            size -= overflow
            dropped += overflow
        }
        val end = (start + size) % cap
        val first = minOf(bytes.size, cap - end)
        bytes.copyInto(buf, end, 0, first)
        if (first < bytes.size) bytes.copyInto(buf, 0, first, bytes.size)
        size += bytes.size
    }

    @Synchronized
    fun snapshot(): ByteArray {
        val out = ByteArray(size)
        val first = minOf(size, cap - start)
        buf.copyInto(out, 0, start, start + first)
        if (first < size) buf.copyInto(out, first, 0, size - first)
        return out
    }

    /** Number of oldest bytes dropped; 0 means everything since the session started is still there. */
    @Synchronized
    fun droppedBytes(): Long = dropped
}

/**
 * A user terminal session: shell process, output buffer, input and history.
 * Output goes into the ring buffer (for a re-attaching UI) and to [output] (for the UI currently watching) at the same time.
 */
class TerminalSession internal constructor(
    val conversationId: String,
    val title: String?,
    /** Network state frozen when the session starts; changing the setting midway only affects new sessions. */
    val network: Boolean,
    private val shell: SandboxShell,
    scope: CoroutineScope,
) {
    enum class Phase { Running, Exited, Revoked, Closed }

    private val _phase = MutableStateFlow(Phase.Running)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _exitCode = MutableStateFlow<Int?>(null)
    val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

    private val ring = TerminalRingBuffer(1 shl 20)
    // When the UI falls behind for a moment, the reading side waits instead of dropping chunks: a dropped chunk leaves a gap on screen and may also cut an escape sequence or half a UTF-8 character.
    // While it waits, the output pipe fills up and the sandbox side stalls, so input and resizes are only handled once the UI catches up. With no UI watching, emit doesn't wait and only goes into the ring buffer
    private val _output = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val output: kotlinx.coroutines.flow.SharedFlow<ByteArray> = _output

    /** Lines the user has entered in this session; memory only, never written to disk. */
    val history = ArrayDeque<String>()

    /** The PTY echoes by itself, so the UI must not draw the input again. */
    val echoInput = false

    private val shellClosed = AtomicBoolean()
    /** See [writeBytes]; created before [lifecycle], which has to shut this thread down when it wraps up. */
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "mika-terminal-write").apply { isDaemon = true } }
    private val lifecycle = scope.launch {
        try {
            val revocations = launch {
                shell.revoked.collect { revoked ->
                    if (revoked) _phase.compareAndSet(Phase.Running, Phase.Revoked)
                }
            }
            val reader = launch(Dispatchers.IO) { drainOutput() }
            val code = shell.exited.await()
            _exitCode.value = code
            _phase.update {
                when {
                    it != Phase.Running -> it
                    shell.revoked.value -> Phase.Revoked
                    else -> Phase.Exited
                }
            }
            revocations.cancel()
            reader.join()
        } finally {
            releaseShell()
        }
    }

    private suspend fun drainOutput() {
        val buf = ByteArray(16 * 1024)
        try {
            shell.output.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    val chunk = buf.copyOf(n)
                    ring.append(chunk)
                    _output.emit(chunk)
                }
            }
        } catch (_: IOException) {
            // A closed pipe can race with exit, revocation or an explicit close.
        }
    }

    fun write(line: String) {
        if (line.isNotEmpty()) {
            history.addLast(line)
            while (history.size > 200) history.removeFirst()
        }
        writeBytes((line + "\n").toByteArray())
    }

    /**
     * Text written to the shell is handed to this session's own thread and written in order; the caller doesn't wait: when the
     * UI falls behind, the sandbox side doesn't read input and the input pipe fills up, so writing directly on the main thread
     * would block it, and since the main thread also collects output, the two sides would wait on each other. Writes that fail
     * (session already closed) are dropped.
     */
    fun writeBytes(bytes: ByteArray) {
        runCatching { writer.execute { runCatching { shell.input.write(bytes); shell.input.flush() } } }
    }

    /** The UI size changed (rotation, font change, split screen); mikarun writes the new column and row counts into the pty. */
    fun resize(cols: Int, rows: Int) {
        if (cols !in 1..1000 || rows !in 1..1000) return
        runCatching { shell.resize(cols, rows) }
    }

    /** Output from the start of the session until now (the oldest part beyond the buffer has already been dropped). */
    fun snapshot(): ByteArray = ring.snapshot()

    fun droppedBytes(): Long = ring.droppedBytes()

    fun close() {
        _phase.value = Phase.Closed
        lifecycle.cancel()
        releaseShell()
    }

    private fun releaseShell() {
        if (shellClosed.compareAndSet(false, true)) {
            writer.shutdownNow()
            // Pipe cleanup can block. It must outlive cancellation of the session's coroutine.
            thread(isDaemon = true, name = "mika-terminal-close") { runCatching { shell.close() } }
        }
    }
}
