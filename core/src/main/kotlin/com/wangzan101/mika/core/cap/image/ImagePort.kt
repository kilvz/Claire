package com.wangzan101.mika.core.cap.image

import com.wangzan101.mika.core.model.GeneratedImage
import com.wangzan101.mika.core.model.ImageCapabilities
import com.wangzan101.mika.core.model.ImageReference
import com.wangzan101.mika.core.model.ImageRequest
import com.wangzan101.mika.core.model.ImageResponse

/**
 * The app-side implementation of the image capability: call the image model, write the output into the workspace, and save workspace images to the album.
 * Normalization of workspace paths, symlink checks, and file-handle verification are all done by the app side; the core only recognizes paths like `/workspace/...`.
 */
interface ImagePort {
    /** The name of the currently selected image model, used only for recording. */
    val modelName: String

    /** What the current model can do; before sending a request the tool uses it to reject unsupported calls. */
    val capabilities: ImageCapabilities

    /** Sends one generation/edit request; throws [com.wangzan101.mika.core.model.ModelError] or [com.wangzan101.mika.core.model.ImageError] on failure. */
    suspend fun generate(request: ImageRequest): ImageResponse

    /** Reads a reference image from the workspace; throws [com.wangzan101.mika.core.model.ImageError] when the path escapes the workspace, the file is not an image, or it is too large. */
    suspend fun readReference(path: String): ImageReference

    /** Writes an image into the workspace at `/workspace/out/images/` and returns the final path and byte count; writes go through the workspace gate, never overwrite, and do not follow links. */
    suspend fun store(image: GeneratedImage, nameHint: String?, prompt: String): StoredImage

    /** Saves an image from the workspace into the album `Pictures/Claire`, returning the album path and content URI. */
    suspend fun saveToGallery(path: String, name: String?): SavedImage
}

/** An image already written into the workspace. */
class StoredImage(val path: String, val bytes: Int, val width: Int, val height: Int)

/** An image already saved to the album. */
class SavedImage(val savedAs: String, val uri: String, val bytes: Long)
