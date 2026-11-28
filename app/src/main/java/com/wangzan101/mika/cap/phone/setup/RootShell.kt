package com.wangzan101.mika.cap.phone.setup

import com.wangzan101.mika.cap.drainBounded
import com.wangzan101.mika.cap.onDaemonThread
import com.wangzan101.mika.core.device.shell.SuAnswer
import com.wangzan101.mika.core.device.shell.SuProbe
import java.io.File
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * su on the phone: find it, query it, run processes through it. Judged purely by capability (the su file can be found, the uid that comes out), not by which kind of root it is.
 * Every method blocks (su may be showing a permission dialog waiting for the user), so call them only on IO threads.
 */
object RootShell {
    /** Full path of su; null when it cannot be found. */
    fun su(): String? = SuProbe.find(System.getenv("PATH")) { runCatching { File(it).exists() }.getOrDefault(false) }

    /**
     * Query su once: run `id` as [uid] (0 is root, 2000 is shell), waiting at most [timeoutMs] (the first time it shows a permission dialog, so the user needs time).
     * Null when su cannot be found.
     */
    fun ask(uid: Int, timeoutMs: Long): SuAnswer? {
        val su = su() ?: return null
        val args = if (uid == 0) SuProbe.rootArgs(su, "id") else SuProbe.shellArgs(su, "id")
        val p = runCatching { start(args) }.getOrElse { return SuAnswer.Denied }
        val out = collect(p.inputStream)
        val exited = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!exited) p.destroyForcibly()
        val text = runCatching { out.get(1, TimeUnit.SECONDS) }.getOrDefault("")
        return SuProbe.answer(text, timedOut = !exited, expectUid = uid)
    }

    /** Start an su process; stderr is merged into stdout (that is where su's explanation goes when it denies). */
    fun start(args: List<String>): Process = ProcessBuilder(args).redirectErrorStream(true).start()

    /**
     * Read the process output until a line yields a uid (it is returned), the stream ends (null: su exited without running the command), or [timeoutMs] elapses (throws java.util.concurrent.TimeoutException).
     * On timeout the caller terminates the process.
     */
    fun readUid(p: Process, timeoutMs: Long): Int? {
        val f = onDaemonThread(THREAD) {
            val r = p.inputStream.bufferedReader()
            generateSequence { runCatching { r.readLine() }.getOrNull() }.firstNotNullOfOrNull(SuProbe::uid)
        }
        return f.get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** Reads [stream] to the end on another thread (at most [max] bytes, dropping the rest). */
    fun collect(stream: InputStream, max: Int = 16 * 1024): CompletableFuture<String> = onDaemonThread(THREAD) {
        String(drainBounded(stream, max).first, Charsets.UTF_8)
    }

    private const val THREAD = "mika-su"
}
