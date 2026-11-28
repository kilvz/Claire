package com.wangzan101.mika.voice.ondevice

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One file in a voice pack. [url] is this file's absolute upstream address (such as the license text); when absent, the upstream address is the pack's [VoicePackEntry.upstream] plus the file name. */
@Serializable
data class VoicePackFile(
    val name: String,
    val role: String,
    val size: Long,
    val sha256: String,
    val url: String? = null,
)

@Serializable
data class VoicePackLicense(val name: String, val file: String, val attribution: String)

/** A downloadable offline-recognition voice pack. [backend] + [family] select the engine that loads it. */
@Serializable
data class VoicePackEntry(
    val id: String,
    val version: Int,
    val backend: String,
    val family: String,
    val displayName: String,
    val languages: List<String>,
    val recommendedFor: List<String>,
    val files: List<VoicePackFile>,
    val license: VoicePackLicense,
    val upstream: String,
) {
    /** Total bytes across all files (download size and disk usage). */
    val totalBytes: Long get() = files.sumOf { it.size }
}

/**
 * The voice-pack manifest bundled in the APK (`assets/voice-models/catalog.json`). Only engines and model families
 * this build knows are kept, so packs and fields added to a newer manifest never break parsing.
 */
class VoicePackCatalog(val release: String, val packs: List<VoicePackEntry>) {

    fun entry(id: String): VoicePackEntry? = packs.firstOrNull { it.id == id }

    /** The address on the release page: release assets are stored flat as `packId--fileName`. Downloads try this first. */
    fun releaseUrl(entry: VoicePackEntry, file: VoicePackFile): String = release + entry.id + "--" + file.name

    /** The original upstream address: the file's own absolute address when present, otherwise the pack's upstream directory plus the file name. Used when the release is unreachable. */
    fun upstreamUrl(entry: VoicePackEntry, file: VoicePackFile): String = file.url ?: (entry.upstream + file.name)

    @Serializable
    private data class Manifest(val schema: Int, val release: String, val packs: List<VoicePackEntry>)

    companion object {
        const val SCHEMA = 1
        const val ASSET = "voice-models/catalog.json"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parses the manifest; [supported] decides whether an engine/model family can run here, and packs that cannot are omitted.
         * A pack is also omitted when its id or a file name is not a safe single path segment ([isSafeName]): both are
         * concatenated directly into install directories and download/import paths. Only that pack is hidden; the rest still work.
         * The manifest ships with the APK, so a schema mismatch is a packaging error and throws [IllegalStateException].
         */
        fun parse(json: String, supported: (backend: String, family: String) -> Boolean): VoicePackCatalog {
            val m = this.json.decodeFromString<Manifest>(json)
            check(m.schema == SCHEMA) { "voice-pack catalog schema ${m.schema}, expected $SCHEMA" }
            return VoicePackCatalog(m.release, m.packs.filter { p ->
                supported(p.backend, p.family) && isSafeName(p.id) && p.files.all { isSafeName(it.name) }
            })
        }

        /** A name that can be used as one path segment: not empty, not `.` or `..`, and containing no `/` or `\`. */
        fun isSafeName(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name

        fun load(context: Context, supported: (backend: String, family: String) -> Boolean): VoicePackCatalog =
            parse(context.assets.open(ASSET).bufferedReader().use { it.readText() }, supported)
    }
}
