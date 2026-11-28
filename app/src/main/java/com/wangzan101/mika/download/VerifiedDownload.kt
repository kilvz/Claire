package com.wangzan101.mika.download

import com.wangzan101.mika.about.DownloadPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * One file in a task: downloaded to [target] in [sources] order, and every finished source must first pass [verify] (size, digest and so on);
 * otherwise the source counts as failed. [size] is the byte count known in advance (from the manifest) for overall task progress; -1 when unknown.
 */
data class DownloadFile(
    val target: File,
    val sources: List<String>,
    val verify: (File) -> Unit,
    val size: Long = -1,
)

/** Progress of one task. [totalBytes] is -1 when unknown; [error] is the last failure reason when [DownloadPhase.Failed]. */
data class DownloadProgress(
    val phase: DownloadPhase,
    val doneBytes: Long,
    val totalBytes: Long,
    val error: Throwable? = null,
)

/**
 * A generic verified downloader shared by app updates (`about/`) and offline voice packs (`voice/`), so it lives in its own top-level package.
 *
 * - A task downloads a set of files in order; each file tries its sources in order, and the set need not come from one source.
 * - Each file is written to `<target>.part`, renamed to the target only when complete, and **then** verified; files already present and verified at the start are skipped.
 * - A target file existing under its final name does **not** mean it was verified: that can happen mid-verification, after a failed verification before deletion, or after an interrupted run.
 *   Callers (such as the voice-pack installer) may use these files only inside [start]'s `onComplete`, or after verifying them again themselves.
 * - When every source fails for one file (or `onComplete` fails), the whole task fails and none of its target files are kept,
 *   including those skipped at the start because they had already verified. Cancellation clears only the file being downloaded; verified files stay and are skipped on retry.
 * - Tasks are identified by key: one task per key at a time, and different keys never cancel each other.
 * - An unfinished download after the process is killed counts as failed and the user can retry. Downloads start only when the caller (a user tap) asks.
 */
class VerifiedDownload(
    private val http: OkHttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private class Run(val files: List<DownloadFile>) {
        lateinit var job: Job
        @Volatile var call: Call? = null
        @Volatile var current: File? = null
        /** Why the task failed; null with an abnormal end means it was cancelled. */
        @Volatile var failure: Throwable? = null
        /** Downloaded and total bytes per file; totals start from [DownloadFile.size] and update from the response length during the download and the real length when it finishes. */
        val done = LongArray(files.size)
        val sizes = LongArray(files.size) { files[it].size }
    }

    private val runs = mutableMapOf<String, Run>()
    /** The latest task per key; a new task with the same key waits until it ends completely (connection closed, partial file removed). */
    private val last = mutableMapOf<String, Job>()
    private val flows = mutableMapOf<String, MutableStateFlow<DownloadProgress>>()

    /**
     * Starts downloading [files]; if a task is already running for [key] it does nothing and returns false.
     * When [client] is null the constructor's [http] is used (through the app proxy); after everything passes, [onComplete] runs first (for example moving files into place),
     * and the task reaches [DownloadPhase.Ready] only if that succeeds; if it throws, the task fails.
     *
     * [prepare] runs inside the task before the first file (for example creating an empty target directory on a background thread); throwing fails the task.
     * [onEnd] is called once after the task ends completely, whether it succeeded, failed or was cancelled, and even when the scope was cancelled before the task started;
     * it runs before progress reaches its final state, and callers clean up there (abandon staging, release locks). It should not throw; any error is swallowed.
     */
    @Synchronized
    fun start(
        key: String,
        files: List<DownloadFile>,
        client: OkHttpClient? = null,
        prepare: suspend () -> Unit = {},
        onEnd: () -> Unit = {},
        onComplete: suspend () -> Unit = {},
    ): Boolean {
        require(files.isNotEmpty()) { "nothing to download" }
        require(files.all { it.sources.isNotEmpty() }) { "no download source" }
        if (key in runs) return false
        val run = Run(files)
        val previous = last[key]
        runs[key] = run
        flow(key).value = progress(run, DownloadPhase.Downloading)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            previous?.join()
            try {
                prepare()
                download(key, run, client ?: http)
                onComplete()
            } catch (e: Throwable) {
                // A real cancellation (the task itself is no longer running) counts as cancelled; everything else counts as failure, including Error
                // and a CancellationException surfacing from verify/onComplete while the task still runs (for example a timeout).
                if (e is CancellationException && !isActive) throw e
                run.failure = e
                files.forEach { it.target.delete() }
                // VM errors such as out-of-memory are rethrown after cleanup instead of being swallowed as ordinary failures; other failures are only recorded in progress.
                if (e is VirtualMachineError) throw e
            }
        }
        run.job = job
        last[key] = job
        // Cleanup lives in the completion callback: when the scope is cancelled before the task starts, no line of the task body runs, so the key must still be released.
        job.invokeOnCompletion { cause ->
            try {
                runCatching(onEnd)
            } finally {
                finish(key, run, cause)
            }
        }
        job.start()
        return true
    }

    @Synchronized
    private fun finish(key: String, run: Run, cause: Throwable?) {
        if (last[key] === run.job) last.remove(key)
        // When [cancel] stopped it, it was already removed and reported Idle.
        if (runs[key] !== run) return
        runs.remove(key)
        val failure = run.failure
        flow(key).value = when {
            failure != null -> progress(run, DownloadPhase.Failed, failure)
            cause == null -> progress(run, DownloadPhase.Ready)
            else -> DownloadProgress(DownloadPhase.Idle, 0, -1)
        }
    }

    @Synchronized
    fun progress(key: String): StateFlow<DownloadProgress> = flow(key).asStateFlow()

    /**
     * Waits until the latest task for [key] ends completely (including the connection closing after [cancel], the partial file being removed and `onComplete` finishing); returns at once when there is none.
     * Use this when the caller must clean up after the task ends (such as voice-pack staging); progress alone is not enough because cancellation returns it to Idle immediately while the task may still be stopping.
     */
    suspend fun join(key: String) {
        val job = synchronized(this) { last[key] }
        job?.join()
    }

    /** Stops the task for [key]: cuts the connection without waiting for the current block to finish, deletes the partial file being downloaded, and returns progress to [DownloadPhase.Idle]. */
    @Synchronized
    fun cancel(key: String) {
        val run = runs.remove(key) ?: return
        run.job.cancel()
        run.call?.cancel()
        run.call = null
        run.current?.let { File(it.path + PART).delete() }
        flow(key).value = DownloadProgress(DownloadPhase.Idle, 0, -1)
    }

    private suspend fun download(key: String, run: Run, client: OkHttpClient) {
        for ((index, file) in run.files.withIndex()) {
            coroutineContext.ensureActive()
            if (alreadyVerified(file)) {
                update(key, run) { run.sizes[index] = file.target.length(); run.done[index] = run.sizes[index] }
                continue
            }
            run.current = file.target
            var failure: Exception? = null
            for (url in file.sources) {
                coroutineContext.ensureActive()
                // When switching sources this file starts over.
                update(key, run) { run.done[index] = 0; run.sizes[index] = file.size }
                try {
                    fetch(client, url, file.target, onCall = { next ->
                        synchronized(this) {
                            if (runs[key] === run && run.job.isActive) run.call = next else next.cancel()
                        }
                    }) { done, size ->
                        update(key, run) {
                            run.done[index] = done
                            if (file.size < 0) run.sizes[index] = size
                        }
                    }
                    coroutineContext.ensureActive()
                    file.verify(file.target)
                    coroutineContext.ensureActive()
                    failure = null
                    break
                } catch (e: CancellationException) {
                    file.target.delete()
                    throw e
                } catch (e: Exception) {
                    // A connection error caused by cancellation must not trigger the next source.
                    file.target.delete()
                    coroutineContext.ensureActive()
                    failure = e
                }
            }
            failure?.let { throw it }
            update(key, run) { run.sizes[index] = file.target.length(); run.done[index] = run.sizes[index] }
        }
        run.current = null
    }

    private fun alreadyVerified(file: DownloadFile): Boolean {
        if (!file.target.isFile) return false
        return try {
            file.verify(file.target)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            file.target.delete()
            false
        }
    }

    /** Updates progress only for this still-running task; a late callback after cancellation or replacement never overwrites the new state. */
    private inline fun update(key: String, run: Run, change: () -> Unit) {
        synchronized(this) {
            if (runs[key] !== run) return
            change()
            flow(key).value = progress(run, DownloadPhase.Downloading)
        }
    }

    private fun progress(run: Run, phase: DownloadPhase, error: Throwable? = null) = DownloadProgress(
        phase,
        run.done.sum(),
        if (run.sizes.any { it < 0 }) -1 else run.sizes.sum(),
        error,
    )

    private fun flow(key: String) = flows.getOrPut(key) { MutableStateFlow(DownloadProgress(DownloadPhase.Idle, 0, -1)) }

    companion object {
        private const val PART = ".part"

        /** Downloads [url] to [file], reporting progress as it goes (done, total, -1 when total is unknown); throws on a non-2xx response or an incomplete download and leaves no target file. */
        suspend fun fetch(http: OkHttpClient, url: String, file: File, onCall: (Call) -> Unit = {}, progress: (Long, Long) -> Unit) {
            val part = File(file.path + PART)
            try {
                val call = http.newCall(Request.Builder().url(url).build())
                onCall(call)
                coroutineContext.ensureActive()
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("empty body")
                    val size = body.contentLength()
                    var done = 0L
                    progress(0, size)
                    body.byteStream().use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                progress(done, size)
                            }
                        }
                    }
                    if (size >= 0 && done != size) throw IOException("incomplete download")
                }
                coroutineContext.ensureActive()
                if (!part.renameTo(file)) throw IOException("cannot move download")
            } finally {
                part.delete()
            }
        }
    }
}
