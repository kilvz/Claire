package com.wangzan101.mika.voice.model

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * Downloads the optional (too large to bundle) on-device models once and keeps
 * them under filesDir/sherpa/<modelId>. The archive is a .tar.bz2 release from
 * the sherpa-onnx model pages; only the four transducer files are extracted and
 * renamed to stable names, so [com.wangzan101.mika.voice.SherpaOnnx] can load
 * them by path.
 */
object SherpaModelStore {
    sealed interface State {
        data object Absent : State
        data class Downloading(val fraction: Float) : State
        /** The bytes are in and the archive is being decompressed: on a phone this is the longest silent stretch. */
        data object Unpacking : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states

    /**
     * Downloads have their own scope: unpacking a 250 MB archive can take a while, and walking
     * away from the page must not cancel it halfway through.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()

    /** Starts [modelId] unless it is already downloading or unpacking; the work outlives the screen that asked. */
    fun start(context: Context, modelId: String) {
        if (modelId !in SherpaOnnxModels.DOWNLOADS) return
        val app = context.applicationContext
        synchronized(jobs) {
            if (jobs[modelId]?.isActive == true) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    download(app, modelId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // download() already recorded the reason in the state map.
                } finally {
                    synchronized(jobs) { jobs.remove(modelId) }
                }
            }
            jobs[modelId] = job
            job.start()
        }
    }

    /** Stable names [SherpaOnnx] loads after extraction; a missing one means the archive did not match. */
    private val REQUIRED_FILES = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")

    fun dir(context: Context, modelId: String): File = File(context.filesDir, "sherpa/$modelId")

    fun ready(context: Context, modelId: String): Boolean {
        val d = dir(context, modelId)
        return File(d, "encoder.onnx").length() > 0 &&
            File(d, "decoder.onnx").length() > 0 &&
            File(d, "joiner.onnx").length() > 0 &&
            File(d, "tokens.txt").length() > 0
    }

    /**
     * Fetches [modelId] and unpacks it atomically: into a staging directory first,
     * renamed into place only when every file is there, so a cancelled or failed
     * download never leaves a half model behind.
     */
    suspend fun download(context: Context, modelId: String): Unit = withContext(Dispatchers.IO) {
        val spec = SherpaOnnxModels.DOWNLOADS[modelId]
            ?: throw IOException("Model $modelId has no download")
        update(modelId, State.Downloading(0f))
        val archive = File(context.cacheDir, "sherpa-$modelId.tar.bz2")
        val staging = File(context.filesDir, "sherpa/$modelId.tmp")
        try {
            fetch(spec.url, archive) { fraction -> update(modelId, State.Downloading(fraction)) }
            // The bytes are all there; what remains is decompressing the archive, which can take
            // minutes on a phone. Say so instead of leaving a "100%" that looks stuck.
            update(modelId, State.Unpacking)
            staging.deleteRecursively()
            staging.mkdirs()
            extract(archive, staging, spec)
            val missing = REQUIRED_FILES.filter { File(staging, it).length() == 0L }
            if (missing.isNotEmpty()) throw IOException("Archive is missing ${missing.joinToString()}")
            val dest = dir(context, modelId)
            dest.deleteRecursively()
            dest.parentFile?.mkdirs()
            if (!staging.renameTo(dest)) throw IOException("Could not move the model into place")
            archive.delete()
            update(modelId, State.Ready)
        } catch (e: CancellationException) {
            // Leaving the page no longer cancels the download, so this only happens with the process.
            runCatching { archive.delete() }
            runCatching { staging.deleteRecursively() }
            throw e
        } catch (e: Throwable) {
            update(modelId, State.Failed(e.message ?: "download failed"))
            runCatching { archive.delete() }
            runCatching { staging.deleteRecursively() }
            throw e
        }
    }

    private fun update(modelId: String, state: State) {
        _states.value = _states.value + (modelId to state)
    }

    private fun fetch(url: String, target: File, progress: (Float) -> Unit) {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty download")
            val total = body.contentLength()
            var read = 0L
            target.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        if (total > 0) progress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
    }

    private fun extract(archive: File, dest: File, spec: SherpaOnnxModels.Download) {
        BZip2CompressorInputStream(archive.inputStream().buffered()).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val rename = when (entry.name.substringAfterLast('/')) {
                            spec.encoder -> "encoder.onnx"
                            spec.decoder -> "decoder.onnx"
                            spec.joiner -> "joiner.onnx"
                            "tokens.txt" -> "tokens.txt"
                            else -> null
                        }
                        if (rename != null) {
                            File(dest, rename).outputStream().use { out -> tar.copyTo(out) }
                        }
                    }
                    entry = tar.nextEntry
                }
            }
        }
    }
}
