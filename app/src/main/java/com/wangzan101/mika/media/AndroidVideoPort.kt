package com.wangzan101.mika.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.webkit.MimeTypeMap
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.core.cap.video.SavedVideo
import com.wangzan101.mika.core.cap.video.VideoError
import com.wangzan101.mika.core.cap.video.VideoFrame
import com.wangzan101.mika.core.cap.video.VideoInfo
import com.wangzan101.mika.core.cap.video.VideoPort
import com.wangzan101.mika.core.workspace.SandboxPaths
import com.wangzan101.mika.core.workspace.GatedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Phone implementation of the video capability: reads workspace video metadata/extracts frames with MediaMetadataRetriever ([VideoProbe]), and saves to the movie library via [VideoExporter].
 * To avoid the path being swapped inside the sandbox between the check and the use, decoding reads directly from the handle verified by the workspace gate; it is not copied to the cache or read entirely into memory.
 */
class AndroidVideoPort(
    private val context: Context,
    private val conversationId: String,
) : VideoPort {
    private val dirs by lazy { SandboxDirs(context) }
    private val workspace: File by lazy { dirs.workspace(com.wangzan101.mika.chat.WorkspaceResolver.resolveFor(context, conversationId)) }

    override suspend fun info(path: String): VideoInfo = probe(path) { g, r ->
        val m = VideoProbe.meta(r)
        VideoInfo(path, m.durationMs, m.width, m.height, mimeOf(path.substringAfterLast('.', "")), g.size, m.hasAudio)
    }

    override suspend fun frames(path: String, count: Int): List<VideoFrame> {
        val job = currentCoroutineContext()[Job]
        return probe(path) { _, r ->
            val duration = VideoProbe.meta(r).durationMs ?: 0L
            VideoProbe.sampleTimes(duration, count).mapNotNull { atMs ->
                job?.ensureActive()
                VideoProbe.frameJpeg(r, atMs, FRAME_SIDE)?.let { VideoFrame(atMs, it) }
            }
        }
    }

    /** Cover for chat cards: a frame near the start, long side within [maxSide]; null when unreadable. */
    suspend fun poster(path: String, maxSide: Int): Bitmap? =
        runCatching { probe(path) { _, r -> VideoProbe.frameBitmap(r, 0, maxSide) } }
            .getOrElse { if (it is CancellationException) throw it else null }

    override suspend fun saveToMovies(path: String, name: String?): SavedVideo {
        val rel = relative(path) ?: throw VideoError.Unsupported("the path must be under ${SandboxPaths.WORKSPACE}")
        return dirs.tree.shared {
            withContext(Dispatchers.IO) {
                dirs.gate.openRead(workspace, rel).use { g ->
                    if (g.size > MAX_BYTES) throw VideoError.TooLarge(g.size)
                    val mime = mimeOf(path.substringAfterLast('.', ""))
                    VideoExporter(context).save(g.input(), mime, name ?: path.substringAfterLast('/'))
                }
            }
        }
    }

    /** Opens [path] through the workspace gate and decodes on the same handle. */
    private suspend fun <T> probe(path: String, block: (GatedFile, MediaMetadataRetriever) -> T): T {
        val rel = relative(path) ?: throw VideoError.Unsupported("the path must be under ${SandboxPaths.WORKSPACE}")
        return dirs.tree.shared {
            withContext(Dispatchers.IO) {
                dirs.gate.openRead(workspace, rel).use { g ->
                    try {
                        VideoProbe.open(g) { r -> block(g, r) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: VideoError) {
                        throw e
                    } catch (e: Exception) {
                        throw VideoError.BadVideo(e.message ?: "cannot decode this video")
                    }
                }
            }
        }
    }

    private fun relative(path: String): String? {
        val prefix = SandboxPaths.WORKSPACE + "/"
        val p = path.trim()
        if (!p.startsWith(prefix)) return null
        val rel = p.removePrefix(prefix)
        if (rel.isEmpty() || rel.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return rel
    }

    private fun mimeOf(ext: String): String = when (ext.lowercase()) {
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        "m4v" -> "video/x-m4v"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())?.takeIf { it.startsWith("video/") } ?: "video/mp4"
    }

    companion object {
        /** Upper limit for saving and sharing one workspace video. */
        const val MAX_BYTES = 1024L * 1024 * 1024
        /** Long side of frames shown to the model. */
        private const val FRAME_SIDE = 1280
    }
}
