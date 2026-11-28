package com.wangzan101.mika.cap.root

import android.os.SystemClock
import android.util.Base64
import com.wangzan101.mika.cap.drainBounded
import com.wangzan101.mika.cap.onDaemonThread
import com.wangzan101.mika.core.cap.root.RootCommandClass
import com.wangzan101.mika.core.cap.root.RootPort
import com.wangzan101.mika.core.cap.root.RootRun
import com.wangzan101.mika.core.device.shell.RootCommandSession
import com.wangzan101.mika.core.device.shell.SuAnswer
import com.wangzan101.mika.core.device.shell.SuProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The Agent's root commands: run as root through `su -c`, with stdout and stderr collected separately (at most 1 MB
 * each) and ended at the time limit. The command is first encoded as base64 and decoded in root's sh only to be handed
 * to `sh -c`: the outer line is always valid sh, quotes and newlines in the command arrive unchanged, and a mistyped
 * command is reported by the inner sh, which is distinguishable from "su refused" (the outer layer prints a marker
 * first; no marker means su did not run). The Root side supervises the command's process group independently. When the
 * app hits the time limit or is cancelled it closes stdin, telling the Root side to end the process group. [allowed] is
 * rechecked before every execution: if the user turns the setting off while the task holds the port, later calls must
 * still not start su. [su] gives su's full path (null when not found); when su did not run this command it is reported
 * through [refused] (null means su is gone), which the settings side records and uses to turn this item off.
 */
class RootCommands(
    private val su: () -> String?,
    private val allowed: () -> Boolean,
    private val refused: (SuAnswer?) -> Unit,
) : RootPort {
    override suspend fun run(command: String, timeoutMs: Long): RootRun = runInterruptible(Dispatchers.IO) {
        // Settings can change while the task runs; the port is only an assembly result, not a long-lived authorization credential
        if (!allowed()) return@runInterruptible RootRun.Denied
        val su = su() ?: return@runInterruptible RootRun.Unavailable.also { refused(null) }
        val started = SystemClock.elapsedRealtime()
        val p = runCatching { ProcessBuilder(SuProbe.rootArgs(su, script(command))).start() }
            .getOrElse { return@runInterruptible RootRun.Denied.also { refused(SuAnswer.Denied) } }
        val out = onDaemonThread(THREAD) { drain(p.inputStream) }
        val err = onDaemonThread(THREAD) { drain(p.errorStream) }
        val exited = try {
            p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            stop(p)
        }
        val duration = SystemClock.elapsedRealtime() - started
        val (stdout, outDropped) = awaitOutput(out)
        val (stderr, errDropped) = awaitOutput(err)
        if (!stdout.startsWith(MARK)) {
            // Not even the marker appeared: su did not run this command
            val a = if (!exited) SuAnswer.NoAnswer else SuAnswer.Denied
            refused(a)
            return@runInterruptible if (a == SuAnswer.NoAnswer) RootRun.NoAnswer else RootRun.Denied
        }
        val exit = if (exited) p.exitValue() else -1
        RootRun.Done(exit, !exited, stdout.removePrefix(MARK).removePrefix("\n"), stderr, outDropped + errDropped, duration)
    }

    private fun stop(p: Process) {
        // Even when su is a client of a daemon, closing the pipe still notifies the Root side that actually runs the command.
        runCatching { p.outputStream.close() }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_GRACE_MS)
        var interrupted = false
        try {
            while (p.isAlive) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    p.destroyForcibly()
                    return
                }
                try {
                    if (p.waitFor(remaining, TimeUnit.NANOSECONDS)) return
                } catch (_: InterruptedException) {
                    // Give the supervisor script time to handle EOF, so su is not killed before it has even started listening.
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun script(command: String): String {
        val b64 = Base64.encodeToString(command.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val path = if (RootCommandClass.classify(command) == RootCommandClass.Kind.ReadOnly) {
            "export PATH=${RootCommandClass.SYSTEM_COMMAND_PATH}; "
        } else ""
        return path + "echo $MARK; c=\"\$(echo $b64 | base64 -d)\"; " +
            RootCommandSession.androidScript
    }

    private fun awaitOutput(output: CompletableFuture<Pair<String, Long>>): Pair<String, Long> = try {
        output.get(2, TimeUnit.SECONDS)
    } catch (e: InterruptedException) {
        throw e
    } catch (_: Exception) {
        "" to 0L
    }

    /** Reads a stream to the end, keeping at most [MAX_BYTES] and counting the rest as dropped. */
    private fun drain(stream: InputStream): Pair<String, Long> =
        drainBounded(stream, MAX_BYTES).let { (kept, dropped) -> String(kept, Charsets.UTF_8) to dropped }

    private companion object {
        const val MARK = "__mika_root_ok__"
        const val MAX_BYTES = 1 shl 20
        const val STOP_GRACE_MS = 2_000L
        const val THREAD = "mika-root"
    }
}
