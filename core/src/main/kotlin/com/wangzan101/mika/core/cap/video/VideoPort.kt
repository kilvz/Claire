package com.wangzan101.mika.core.cap.video

/** Metadata of a workspace video. 0/null when it cannot be read. */
class VideoInfo(
    val path: String,
    val durationMs: Long?,
    val width: Int,
    val height: Int,
    val mime: String,
    val bytes: Long,
    val hasAudio: Boolean,
)

/** One frame extracted from a video; the JPEG has already been scaled to a size the model can view. */
class VideoFrame(val atMs: Long, val jpeg: ByteArray)

/** A video saved to the movie library. */
class SavedVideo(val savedAs: String, val uri: String, val bytes: Long)

/**
 * App-side implementation of the video capability: reads workspace video metadata, extracts frames for the model to view, and saves videos to the movie library.
 * Path normalization, symlink checks and media decoding are all done by the app; the core only recognizes `/workspace/...`.
 */
interface VideoPort {
    suspend fun info(path: String): VideoInfo

    /** Take [count] frames evenly (1-12); frames that cannot be extracted are skipped. */
    suspend fun frames(path: String, count: Int): List<VideoFrame>

    suspend fun saveToMovies(path: String, name: String?): SavedVideo
}

/** Video-specific errors; unreadable/undecodable/too large. */
sealed class VideoError(message: String) : Exception(message) {
    class Unsupported(message: String) : VideoError(message)
    class TooLarge(val bytes: Long) : VideoError("video is too large")
    class BadVideo(message: String) : VideoError(message)
}
