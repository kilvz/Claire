package com.wangzan101.mika.core.model

import kotlinx.serialization.json.JsonObject
import java.util.Base64

/**
 * Image generation model. Kept separate from chat models: a request to an image model is not a conversation and has no tool calls, context or chain of thought.
 * Returned images are handed to the upper layer as [GeneratedImage]; HTTP/provider errors still use [ModelError],
 * only limits of the image model itself (unsupported aspect ratio, editing, undecodable image) use [ImageError].
 */
interface ImageModel {
    /** Model name, used for logging only. */
    val name: String

    /** What this model supports; the upper layer rejects unsupported requests based on it before sending, avoiding paying first and failing later. */
    val capabilities: ImageCapabilities

    /** Throws [ModelError] or [ImageError] on failure. */
    suspend fun generate(request: ImageRequest): ImageResponse
}

/** Aspect ratio. Providers name them differently; [wire] is Gemini's notation, OpenAI-style APIs convert it to a size. */
enum class ImageAspect(val wire: String) {
    Square("1:1"),
    Portrait("2:3"),
    Landscape("3:2"),
    Tall("9:16"),
    Wide("16:9"),
}

/** A reference image. Image models want the original image, without [ModelImage]'s screen-read compression. */
class ImageReference(val bytes: ByteArray, val mime: String)

/** A generation request. [count] is how many are wanted; how many the provider actually returns is determined by the result. */
data class ImageRequest(
    val prompt: String,
    val aspect: ImageAspect = ImageAspect.Square,
    val count: Int = 1,
    val references: List<ImageReference> = emptyList(),
)

/** One image returned by the provider. [width]/[height] are 0 when they cannot be read; do not guess. */
class GeneratedImage(
    val bytes: ByteArray,
    val mime: String,
    val width: Int,
    val height: Int,
    val revisedPrompt: String? = null,
)

class ImageResponse(val images: List<GeneratedImage>, val usage: Usage? = null)

/**
 * What an image model can do. Capabilities are written from actual testing, not guessed from the model name; the upper layer only sends requests listed here.
 * [aspects] are the aspect ratios it can generate; [maxReferences] is the maximum number of reference images when editing; [maxCount] is the maximum number requested at once.
 */
data class ImageCapabilities(
    val supportsEdit: Boolean,
    val maxReferences: Int = 0,
    val maxCount: Int = 1,
    val aspects: Set<ImageAspect> = setOf(ImageAspect.Square),
)

/** Limits of the image model itself: not an HTTP error from the provider; retrying or switching models may help. */
sealed class ImageError(message: String) : Exception(message) {
    /** This model/API does not support the request (aspect ratio, editing, image count, reference images). */
    class Unsupported(message: String) : ImageError(message)

    /** The response is not a recognizable image (not PNG/JPEG/WebP/GIF, or the content is corrupt). */
    class BadImage(message: String) : ImageError(message)

    /** Image too large; do not write it to the workspace. */
    class TooLarge(val bytes: Long) : ImageError("image is too large")
}

/** Recognize the image format and size from the file header; null if unrecognized. Only the few formats image providers return are recognized. */
internal class ImageBytes(val mime: String, val width: Int, val height: Int)

internal fun sniffImage(bytes: ByteArray): ImageBytes? = when {
    bytes.size >= 24 && u8(bytes, 0) == 0x89 && u8(bytes, 1) == 'P'.code && u8(bytes, 2) == 'N'.code && u8(bytes, 3) == 'G'.code ->
        ImageBytes("image/png", be32(bytes, 16), be32(bytes, 20))
    bytes.size >= 3 && u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xD8 && u8(bytes, 2) == 0xFF ->
        jpeg(bytes)
    bytes.size >= 12 && ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WEBP" ->
        webp(bytes)
    bytes.size >= 10 && ascii(bytes, 0, 3) == "GIF" ->
        ImageBytes("image/gif", le16(bytes, 6), le16(bytes, 8))
    else -> null
}

/** JPEG dimensions are in the SOF0/SOF1/SOF2 segments; skip through the segments one by one to find them. */
private fun jpeg(b: ByteArray): ImageBytes? {
    var i = 2
    while (i + 9 < b.size) {
        if (u8(b, i) != 0xFF) { i++; continue }
        val marker = u8(b, i + 1)
        if (marker == 0xD8 || marker == 0xD9) { i += 2; continue }
        if (marker in 0xD0..0xD7 || marker == 0x01) { i += 2; continue }
        if (i + 4 > b.size) return null
        val length = be16(b, i + 2)
        if (length < 2) return null
        if (marker in 0xC0..0xC2 || marker in 0xC5..0xC7 || marker in 0xC9..0xCB || marker in 0xCD..0xCF) {
            if (i + 9 > b.size) return null
            return ImageBytes("image/jpeg", be16(b, i + 7), be16(b, i + 5))
        }
        i += 2 + length
    }
    return ImageBytes("image/jpeg", 0, 0)
}

/** WebP: VP8X has extended dimensions, VP8 (lossy) and VP8L (lossless) each read from their own header. */
private fun webp(b: ByteArray): ImageBytes? {
    var i = 12
    while (i + 8 <= b.size) {
        val fourcc = ascii(b, i, 4)
        val size = le32(b, i + 4).coerceAtLeast(0)
        val data = i + 8
        when (fourcc) {
            "VP8X" -> if (data + 10 <= b.size) {
                val w = 1 + (u8(b, data + 4) or (u8(b, data + 5) shl 8) or (u8(b, data + 6) shl 16))
                val h = 1 + (u8(b, data + 7) or (u8(b, data + 8) shl 8) or (u8(b, data + 9) shl 16))
                return ImageBytes("image/webp", w, h)
            }
            "VP8 " -> if (data + 10 <= b.size) {
                val w = le16(b, data + 6) and 0x3FFF
                val h = le16(b, data + 8) and 0x3FFF
                return ImageBytes("image/webp", w, h)
            }
            "VP8L" -> if (data + 5 <= b.size) {
                val bits = le32(b, data + 1)
                val w = 1 + (bits and 0x3FFF)
                val h = 1 + ((bits ushr 14) and 0x3FFF)
                return ImageBytes("image/webp", w, h)
            }
        }
        i = data + size + (size and 1)
    }
    return ImageBytes("image/webp", 0, 0)
}

private fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and 0xFF
private fun be16(b: ByteArray, at: Int): Int = (u8(b, at) shl 8) or u8(b, at + 1)
private fun be32(b: ByteArray, at: Int): Int = (u8(b, at) shl 24) or (u8(b, at + 1) shl 16) or (u8(b, at + 2) shl 8) or u8(b, at + 3)
private fun le16(b: ByteArray, at: Int): Int = u8(b, at) or (u8(b, at + 1) shl 8)
private fun le32(b: ByteArray, at: Int): Int = u8(b, at) or (u8(b, at + 1) shl 8) or (u8(b, at + 2) shl 16) or (u8(b, at + 3) shl 24)
private fun ascii(b: ByteArray, at: Int, n: Int): String = String(b, at, n, Charsets.ISO_8859_1)

/** Detect the format of an image returned by the provider from its content; shared by the b64 and URL paths. */
internal fun decodeImage(bytes: ByteArray, revisedPrompt: String? = null): GeneratedImage {
    if (bytes.size > MAX_IMAGE_BYTES) throw ImageError.TooLarge(bytes.size.toLong())
    val info = sniffImage(bytes) ?: throw ImageError.BadImage("response is not a PNG, JPEG, WebP or GIF image")
    return GeneratedImage(bytes, info.mime, info.width, info.height, revisedPrompt)
}

internal fun decodeBase64Image(text: String, revisedPrompt: String? = null): GeneratedImage {
    val bytes = try { Base64.getDecoder().decode(text) } catch (e: IllegalArgumentException) {
        throw ImageError.BadImage("response is not valid base64")
    }
    return decodeImage(bytes, revisedPrompt)
}

/** Maximum byte size of one generated image; larger ones are not written to the workspace. */
internal const val MAX_IMAGE_BYTES = 20 * 1024 * 1024

/** Pick a file extension for an image in a multipart body; falls back to png when unrecognized. */
internal fun extensionOf(mime: String): String = when (mime.substringAfter('/', "").lowercase()) {
    "jpeg", "jpg" -> "jpg"
    "webp" -> "webp"
    "gif" -> "gif"
    else -> "png"
}

/** Summarize the image request parameters into JSON for tracing/usage (so multipart requests get a record too). */
internal fun imageTracePayload(model: String, request: ImageRequest): JsonObject = kotlinx.serialization.json.buildJsonObject {
    put("model", kotlinx.serialization.json.JsonPrimitive(model))
    put("prompt", kotlinx.serialization.json.JsonPrimitive(request.prompt.take(200)))
    put("aspect", kotlinx.serialization.json.JsonPrimitive(request.aspect.wire))
    put("count", kotlinx.serialization.json.JsonPrimitive(request.count))
    if (request.references.isNotEmpty()) put("references", kotlinx.serialization.json.JsonPrimitive(request.references.size))
}
