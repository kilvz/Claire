package com.wangzan101.mika.cap

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CompletableFuture

// Small helper for blocking reads of process output: reads cannot be cancelled, so they run on their own daemon thread and are length-bounded.

/** Does blocking work on a dedicated daemon thread [name] (not using the shared pool); the result or thrown exception goes to the returned future. */
internal fun <T> onDaemonThread(name: String, block: () -> T): CompletableFuture<T> = CompletableFuture<T>().also { f ->
    Thread({ runCatching(block).fold({ f.complete(it) }, { f.completeExceptionally(it) }) }, name).apply { isDaemon = true }.start()
}

/** Read [stream] to the end, keeping at most [limit] bytes; the rest is read and discarded with a count. On a read error it stops there and still returns what was read. */
internal fun drainBounded(stream: InputStream, limit: Int): Pair<ByteArray, Long> =
    OutputCollector(limit).also { runCatching { it.drain(stream) } }.snapshot()

/** Read to end of stream; keep at most [limit] bytes, discarding and counting the rest (not draining it would leave the command stuck writing output). A snapshot can be taken mid-read too. */
internal class OutputCollector(private val limit: Int) {
    private val kept = ByteArrayOutputStream(minOf(limit, READ_CHUNK))
    private var dropped = 0L

    fun drain(input: InputStream) {
        val buf = ByteArray(READ_CHUNK)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            synchronized(this) {
                val room = limit - kept.size()
                if (room > 0) kept.write(buf, 0, minOf(n, room))
                if (n > room) dropped += n - maxOf(room, 0)
            }
        }
    }

    @Synchronized fun snapshot(): Pair<ByteArray, Long> = kept.toByteArray() to dropped

    private companion object {
        const val READ_CHUNK = 64 * 1024
    }
}
