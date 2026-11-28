package com.wangzan101.mika.core.cap.video

import com.wangzan101.mika.core.model.VideoCapabilities
import com.wangzan101.mika.core.model.VideoJobStage

/** A generated video: workspace path, dimensions/duration, and poster (JPEG, may be null). */
class VideoTake(
    val path: String,
    val durationMs: Long?,
    val width: Int,
    val height: Int,
    val poster: ByteArray? = null,
)

/** One generation request; firstFramePath is a workspace path that the app reads into bytes before handing it to the model. */
class VideoGenerationRequest(
    val prompt: String,
    val durationSec: Int,
    val aspect: String,
    val resolution: String,
    val firstFramePath: String? = null,
)

/**
 * App-side implementation of the generation capability: calls VideoModel, downloads into the workspace, and extracts one frame as the poster; the port reports progress to the task card itself.
 * The port does not know about providers: which provider and protocol is decided by [com.wangzan101.mika.core.model.VideoBackend].
 */
interface VideoGenerationPort {
    val modelName: String
    val capabilities: VideoCapabilities

    /**
     * Submit, wait until downloadable, and save into the workspace. Failures throw ModelError/VideoModelError/VideoError.
     * Every step of the task is also reported to [onStage]: the tool uses [VideoJobStage.Submitted] to know the provider accepted it (billing has started), and a later failure does not refund the allowance.
     */
    suspend fun generate(request: VideoGenerationRequest, onStage: suspend (VideoJobStage) -> Unit = {}): VideoTake
}
