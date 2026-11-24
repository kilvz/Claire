package com.wangzan101.mika.plugin

import com.wangzan101.mika.core.tool.ScriptResult
import com.wangzan101.mika.core.tool.ScriptRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Runs plugin scripts inside the App process: `sh <script> <argsFile>`. stdout/stderr are fully collected, and on
 * timeout the process is killed with [destroyForcibly].
 * [shell] defaults to `/system/bin/sh`, tests inject `sh`; `MIKA_ARGS_FILE` and the configuration from [envProvider]
 * (the environment variables page) both go into the process environment.
 */
class PluginScriptRunner(
    private val shell: String = "/system/bin/sh",
    private val envProvider: () -> Map<String, String> = { emptyMap() },
) : ScriptRunner {
    override suspend fun run(script: File, argsFile: File, env: Map<String, String>, timeoutMs: Long): ScriptResult =
        withContext(Dispatchers.IO) {
            val pb = ProcessBuilder(shell, script.absolutePath, argsFile.absolutePath)
            pb.environment().apply {
                put("MIKA_ARGS_FILE", argsFile.absolutePath)
                putAll(envProvider())
                putAll(env)
            }
            val process = pb.start()
            val outF = CompletableFuture.supplyAsync { readCapped(process.inputStream, MAX_OUTPUT_BYTES) }
            val errF = CompletableFuture.supplyAsync { readCapped(process.errorStream, MAX_OUTPUT_BYTES) }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
            val exitCode = if (finished) process.exitValue() else -1
            fun read(f: CompletableFuture<String>): String = runCatching { f.get(2, TimeUnit.SECONDS) }.getOrDefault("")
            ScriptResult(exitCode, read(outF), read(errF), !finished)
        }

    override suspend fun runStdin(script: File, stdin: String, env: Map<String, String>, timeoutMs: Long): ScriptResult =
        withContext(Dispatchers.IO) {
            val pb = ProcessBuilder(shell, script.absolutePath)
            pb.environment().apply {
                putAll(envProvider())
                putAll(env)
            }
            val process = pb.start()
            val outF = CompletableFuture.supplyAsync { readCapped(process.inputStream, MAX_OUTPUT_BYTES) }
            val errF = CompletableFuture.supplyAsync { readCapped(process.errorStream, MAX_OUTPUT_BYTES) }
            CompletableFuture.runAsync { runCatching { process.outputStream.use { it.write(stdin.toByteArray(Charsets.UTF_8)) } } }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
            val exitCode = if (finished) process.exitValue() else -1
            fun read(f: CompletableFuture<String>): String = runCatching { f.get(2, TimeUnit.SECONDS) }.getOrDefault("")
            ScriptResult(exitCode, read(outF), read(errF), !finished)
        }
}

/** Maximum plugin script output kept in memory; anything beyond is discarded (so a single script cannot blow up memory). */
private const val MAX_OUTPUT_BYTES = 1_000_000

/** Reads at most [maxBytes] bytes from [input], drains the rest, then returns. */
internal fun readCapped(input: java.io.InputStream, maxBytes: Int): String {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var total = 0
    while (total < maxBytes) {
        val n = input.read(chunk, 0, minOf(chunk.size, maxBytes - total))
        if (n < 0) break
        out.write(chunk, 0, n)
        total += n
    }
    while (input.read(chunk) >= 0) { /* drain the rest so the process isn't blocked on a full pipe */ }
    return out.toString(Charsets.UTF_8)
}
