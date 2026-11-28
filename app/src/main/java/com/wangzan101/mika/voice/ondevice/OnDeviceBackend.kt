package com.wangzan101.mika.voice.ondevice

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
}

data class EngineOptions(val threads: Int) {
    companion object {
        /** Threads: the smaller of the CPU core count and 4. */
        fun default() = EngineOptions(minOf(Runtime.getRuntime().availableProcessors(), 4))
    }
}

/** A pack from the manifest plus its install location; files are addressed by role, not by name. */
data class InstalledVoicePack(val entry: VoicePackEntry, val dir: File) {
    /** The file for [role]; throws [OnDeviceSttException.Kind.Missing] when the pack has no such role. */
    fun file(role: String): File {
        val f = entry.files.firstOrNull { it.role == role }
            ?: throw OnDeviceSttException(OnDeviceSttException.Kind.Missing, "voice pack ${entry.id} has no '$role' file")
        return File(dir, f.name)
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
class OnDeviceBackends(private val backends: List<OnDeviceSttBackend> = listOf(SherpaOnnxBackend())) {
    fun find(id: String): OnDeviceSttBackend? = backends.firstOrNull { it.id == id }

    /** The backend exists and recognises this model; manifest parsing uses it to hide packs this build cannot run. */
    fun supports(backend: String, family: String): Boolean = find(backend)?.supports(family) == true
}
