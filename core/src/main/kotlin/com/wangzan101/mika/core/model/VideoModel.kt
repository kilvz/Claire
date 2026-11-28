package com.wangzan101.mika.core.model

/** Video generation model: submit an asynchronous task, poll for the result, and return a downloadable video. */
interface VideoModel {
    val name: String
    val capabilities: VideoCapabilities

    /**
     * Submit and wait until downloadable; [onProgress] is called at every step (the app turns it into a short phrase in the user's language on the task card).
     * Failures throw [ModelError] (provider HTTP) or [VideoModelError]. When cancelled while waiting, make a best effort to cancel the provider task that has not started.
     */
    suspend fun generate(request: VideoRequest, onProgress: suspend (VideoJobStage) -> Unit = {}): VideoOutput
}

/** Which step an asynchronous video task has reached. */
enum class VideoJobStage { Submitted, Queued, Running }

/**
 * What a video model can do. Declared per model by each provider's [VideoBackend.capabilities] according to the provider documentation; tools and the settings page only look at it and do not know providers.
 * [firstFrameKeepsAspect]: with a first frame, the output keeps the first frame's aspect ratio and the requested aspect ratio has no effect (the adapter does not send it).
 * [supportsAudio]: can generate sound synchronized with the picture (the adapter requests sound accordingly); false for models that are uncertain, leaving it to the provider default.
 */
data class VideoCapabilities(
    val durations: IntRange = 4..15,
    val aspects: Set<String> = setOf("16:9", "9:16", "1:1"),
    val resolutions: Set<String> = setOf("480p", "720p"),
    val supportsFirstFrame: Boolean = true,
    val firstFrameKeepsAspect: Boolean = false,
    val supportsAudio: Boolean = false,
)

/** One generation request; the reference image is read into bytes from the workspace by the app, and the core only sends it. */
data class VideoRequest(
    val prompt: String,
    val durationSec: Int,
    val aspect: String,
    val resolution: String,
    val firstFrame: ByteArray? = null,
    val firstFrameMime: String? = null,
)

/** Generation result: either inline bytes or a download URL that expires (the caller must download immediately). */
class VideoOutput(
    val bytes: ByteArray? = null,
    val url: String? = null,
    val mime: String = "video/mp4",
    val jobId: String? = null,
    val durationMs: Long? = null,
)

/**
 * Video-model-specific errors; HTTP/provider errors still use [ModelError].
 * [Failed] means the provider explicitly reported the task failed or was cancelled (no output); [Timeout] means the local wait was too long or the provider reported the task expired;
 * [Unconfirmed] means the connection dropped after the submission request was sent, so it is unknown whether the provider created a task (it may still be billed): the caller must not retry as if it had not been submitted.
 */
sealed class VideoModelError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unsupported(message: String) : VideoModelError(message)
    class BadResponse(message: String) : VideoModelError(message)
    class Timeout(message: String) : VideoModelError(message)
    class Failed(message: String) : VideoModelError(message)
    class Unconfirmed(cause: Throwable) : VideoModelError("could not confirm whether the video job was created", cause)
}
