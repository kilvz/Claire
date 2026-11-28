package com.wangzan101.mika.voice.ondevice

import android.content.res.AssetManager
import java.io.File

/** A local inference backend, such as sherpa-onnx. Found by a pack's [VoicePackEntry.backend]. */
interface OnDeviceSttBackend {
    /** Matches the manifest's `backend` field, for example `sherpa-onnx`. */
    val id: String

    /** Whether this backend recognises this kind of model ([VoicePackEntry.family]). */
    fun supports(family: String): Boolean

    /** Whether this device can run it (CPU architecture). */
    fun abiSupported(): Boolean

    /** Loads an installed, verified pack; throws [OnDeviceSttException] on failure. May be slow, so call it off the main thread. */
    fun load(pack: InstalledVoicePack, options: EngineOptions): OnDeviceSttEngine
}

/** A loaded model that stays in memory until [close]. */
interface OnDeviceSttEngine : AutoCloseable {
    /** Takes 16 kHz mono PCM and returns text. [language] is an ISO 639 language part; null means auto-detect. */
    suspend fun transcribe(pcm: ShortArray, language: String?): String

    /**
     * An independent incremental stream for one live session, or null when this engine only transcribes whole clips.
     * The caller closes the returned stream; the shared model stays loaded, like [transcribe].
     */
    fun newStream(language: String?): OnDeviceSttStream? = null
}

/** One live recognition stream: feed audio, read the growing text, and cut at endpoints. Not shared between sessions. */
interface OnDeviceSttStream : AutoCloseable {
    /** Feeds 16 kHz mono PCM. */
    fun accept(pcm: ShortArray)

    /** Everything recognised in the current segment (trimmed by the caller). */
    fun text(): String

    /** The user paused long enough to end a segment; [text] is final and [reset] starts the next one. */
    fun isEndpoint(): Boolean

    fun reset()
}

data class EngineOptions(val threads: Int) {
    companion object {
        /** Threads: the smaller of the CPU core count and 4. */
        fun default() = EngineOptions(minOf(Runtime.getRuntime().availableProcessors(), 4))
    }
}

/** A pack from the manifest plus its install location; files are addressed by role, not by name. */
data class InstalledVoicePack(
    val entry: VoicePackEntry,
    val dir: File,
    /** Present for [VoicePackEntry.bundled] packs so the engine can read the APK assets instead of files. */
    val assets: AssetManager? = null,
    val bundled: Boolean = false,
) {
    /** The file for [role]; throws [OnDeviceSttException.Kind.Missing] when the pack has no such role. */
    fun file(role: String): File {
        val f = entry.files.firstOrNull { it.role == role }
            ?: throw OnDeviceSttException(OnDeviceSttException.Kind.Missing, "voice pack ${entry.id} has no '$role' file")
        return File(dir, f.name)
    }

    /** The path inside the APK assets for [role], used by bundled packs. */
    fun assetPath(role: String): String {
        val f = entry.files.firstOrNull { it.role == role }
            ?: throw OnDeviceSttException(OnDeviceSttException.Kind.Missing, "voice pack ${entry.id} has no '$role' file")
        return "voice-models/${entry.id}/${f.name}"
    }
}

/** An on-device recognition failure, classified by [kind] and mapped to `VoiceModelError` when it reaches the voice catalog. */
class OnDeviceSttException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * [Corrupt] is only for load failures (unreadable files, the model cannot be built), and the UI asks the user to download again;
     * [Failed] means the model loaded but this decode failed unexpectedly, so downloading again will not help and the UI only offers a retry.
     */
    enum class Kind { Missing, Corrupt, OutOfMemory, Timeout, Unsupported, Failed }
}

/** Backend registry: pass the list at construction; tests substitute fakes. */
class OnDeviceBackends(private val backends: List<OnDeviceSttBackend> = listOf(SherpaOnnxBackend(), StreamingBackend())) {
    fun find(id: String): OnDeviceSttBackend? = backends.firstOrNull { it.id == id }

    /** The backend exists and recognises this model; manifest parsing uses it to hide packs this build cannot run. */
    fun supports(backend: String, family: String): Boolean = find(backend)?.supports(family) == true
}
