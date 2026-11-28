package com.wangzan101.mika.cap.phone.shell

import android.os.Bundle
import android.util.Log
import com.wangzan101.mika.cap.OutputCollector
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.TAG
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.CMD_TIMEOUT_S
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.COMMAND_OUTPUT_MAX
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.READER_JOIN_MS

// Assistant service: running commands and unlocking
internal fun ShellService.commandResult(code: Int, output: String) = Bundle().apply { putInt("exitCode", code); putString("output", output) }

/** Runs `sh -c` as shell with merged output, bounded time and length; a non-null [displayId] is put into MIKA_DISPLAY_ID. */
internal fun ShellService.exec(command: String, displayId: Int?): Bundle {
    fun result(code: Int, output: String) = commandResult(code, output)
    if (command.isBlank() || command.length > 4_000) return result(-1, "Invalid command length.")
    val process = try {
        ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true)
            .apply { if (displayId != null) environment()["MIKA_DISPLAY_ID"] = displayId.toString() }.start()
    } catch (t: Throwable) {
        return result(-1, t.message ?: "Command could not start.")
    }
    val collector = OutputCollector(COMMAND_OUTPUT_MAX)
    val reader = Thread { runCatching { process.inputStream.use(collector::drain) } }.apply { isDaemon = true; start() }
    val finished = process.waitFor(CMD_TIMEOUT_S, TimeUnit.SECONDS)
    if (!finished) process.destroyForcibly()
    reader.join(READER_JOIN_MS)
    // If the reader thread has not finished by the deadline (a descendant still holds the pipe), keep only what was read so far
    val kept = collector.snapshot().first
    val output = String(kept, Charsets.UTF_8) + if (kept.size >= COMMAND_OUTPUT_MAX) "\n[output truncated]" else ""
    return result(if (finished) process.exitValue() else -1, output)
}

internal fun ShellService.exec(vararg cmd: String): Int = sh(*cmd).first

/**
 * Runs one shell command and returns (exit code, output); returns -1 on timeout.
 * Output is read on a separate thread, so a stuck command never blocks the binder thread forever.
 */
internal fun ShellService.sh(vararg cmd: String): Pair<Int, String> {
    val p = try {
        ProcessBuilder(*cmd).redirectErrorStream(true).start()
    } catch (t: Throwable) {
        Log.w(TAG, "exec ${cmd.joinToString(" ")} failed", t)
        return -1 to ""
    }
    val output = AtomicReference("")
    val reader = Thread { output.set(runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("")) }
        .apply { isDaemon = true; start() }
    if (!p.waitFor(CMD_TIMEOUT_S, TimeUnit.SECONDS)) {
        p.destroyForcibly()
        Log.w(TAG, "exec ${cmd.joinToString(" ")} timed out")
        return -1 to ""
    }
    reader.join(READER_JOIN_MS)
    return p.exitValue() to output.get()
}
