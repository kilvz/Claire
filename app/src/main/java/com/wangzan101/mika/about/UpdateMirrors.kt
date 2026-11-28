package com.wangzan101.mika.about

import com.wangzan101.mika.core.workspace.AtomicFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * A single GitHub download mirror. [prefix] looks like `https://ghfast.top/`; the complete URL is prefix + the official asset URL.
 * The user can rename it, change the address, reorder, add and remove; official GitHub is always tried last as a fallback and is not part of this list.
 */
@Serializable
data class UpdateMirror(val id: String, val name: String, val prefix: String)

/** The mirror settings used for updates: the master switch and the list tried in order. */
@Serializable
data class MirrorSettings(val enabled: Boolean = false, val mirrors: List<UpdateMirror> = emptyList()) {
    companion object {
        val OFF = MirrorSettings()
    }
}

/** The reason a mirror-list save was rejected. The UI only changes the list after a successful write. */
enum class UpdateMirrorError { InvalidName, InvalidPrefix, Duplicate, TooMany, SaveFailed, Unreadable }

/**
 * User-managed list of update mirrors. When the file does not exist yet, [UpdateMirrors.PRESETS] is written once;
 * after that the user's additions, edits and ordering are this list itself, so upgrading the app will not overwrite an order the user has changed.
 * When the file cannot be read, the original file is left untouched and nothing happens until the user explicitly restores the defaults.
 */
class UpdateMirrorStore(
    private val file: File,
    private val ids: () -> String = { UUID.randomUUID().toString() },
) {
    private val _mirrors = MutableStateFlow<List<UpdateMirror>>(emptyList())
    private val _enabled = MutableStateFlow(false)
    private val _unreadable = MutableStateFlow(false)
    private var loaded = false
    private var attempted = false

    val mirrors: StateFlow<List<UpdateMirror>> = _mirrors.asStateFlow()
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** An existing file could not be read. Additions, edits and deletions are blocked then, so that an empty list cannot overwrite content that was never read. */
    val unreadable: StateFlow<Boolean> = _unreadable.asStateFlow()

    @Synchronized
    fun ensureLoaded() {
        if (loaded || attempted) return
        attempted = true
        when (val read = read()) {
            Read.Missing -> {
                val seeded = presets()
                // Even if the write fails, put the presets in memory first: downloads and update checks still work, and the next change writes again.
                write(false, seeded)
                publish(false, seeded)
            }
            is Read.Ok -> publish(read.enabled, read.mirrors)
            Read.Failed -> {
                _mirrors.value = emptyList()
                _enabled.value = false
                _unreadable.value = true
                loaded = true
            }
        }
    }

    /** Downloads and update checks take this snapshot: the switch and the list are read together, guaranteeing they come from the same settings. */
    @Synchronized
    fun snapshot(): MirrorSettings {
        ensureLoaded()
        return MirrorSettings(_enabled.value, _mirrors.value)
    }

    @Synchronized
    fun setEnabled(on: Boolean): UpdateMirrorError? {
        guard()?.let { return it }
        if (_enabled.value == on) return null
        return commit(on, _mirrors.value)
    }

    @Synchronized
    fun add(name: String, prefix: String): UpdateMirrorError? {
        guard()?.let { return it }
        val prefixValue = UpdateMirrors.normalizePrefix(prefix) ?: return UpdateMirrorError.InvalidPrefix
        val nameValue = displayName(name, prefixValue) ?: return UpdateMirrorError.InvalidName
        if (_mirrors.value.size >= UpdateMirrors.MAX) return UpdateMirrorError.TooMany
        if (_mirrors.value.any { it.prefix.equals(prefixValue, ignoreCase = true) }) return UpdateMirrorError.Duplicate
        return commit(_enabled.value, _mirrors.value + UpdateMirror(ids(), nameValue, prefixValue))
    }

    @Synchronized
    fun update(id: String, name: String, prefix: String): UpdateMirrorError? {
        guard()?.let { return it }
        val prefixValue = UpdateMirrors.normalizePrefix(prefix) ?: return UpdateMirrorError.InvalidPrefix
        val nameValue = displayName(name, prefixValue) ?: return UpdateMirrorError.InvalidName
        val index = _mirrors.value.indexOfFirst { it.id == id }
        if (index < 0) return null
        if (_mirrors.value.anyIndexed { i, m -> i != index && m.prefix.equals(prefixValue, ignoreCase = true) }) {
            return UpdateMirrorError.Duplicate
        }
        return commit(
            _enabled.value,
            _mirrors.value.mapIndexed { i, mirror -> if (i == index) mirror.copy(name = nameValue, prefix = prefixValue) else mirror },
        )
    }

    @Synchronized
    fun delete(id: String): UpdateMirrorError? {
        guard()?.let { return it }
        if (_mirrors.value.none { it.id == id }) return null
        return commit(_enabled.value, _mirrors.value.filterNot { it.id == id })
    }

    /** Reorders according to the full list of IDs seen when the drag started, so that concurrent additions or deletions cannot lose mirrors. */
    @Synchronized
    fun reorder(ids: List<String>): UpdateMirrorError? {
        guard()?.let { return it }
        val current = _mirrors.value
        if (ids.size != current.size || ids.toSet() != current.map { it.id }.toSet()) return UpdateMirrorError.SaveFailed
        val byId = current.associateBy { it.id }
        val reordered = ids.map { byId.getValue(it) }
        return if (reordered == current) null else commit(_enabled.value, reordered)
    }

    /** Called when the user explicitly wants to return to the presets; an unreadable old file is also replaced here. */
    @Synchronized
    fun resetDefaults(): UpdateMirrorError? = commit(_enabled.value, presets())

    private fun presets() = UpdateMirrors.PRESETS.map { UpdateMirror(ids(), it.name, it.prefix) }

    private fun guard(): UpdateMirrorError? {
        ensureLoaded()
        return if (_unreadable.value) UpdateMirrorError.Unreadable else null
    }

    private fun publish(enabled: Boolean, mirrors: List<UpdateMirror>) {
        _enabled.value = enabled
        _mirrors.value = mirrors
        _unreadable.value = false
        loaded = true
    }

    private fun commit(enabled: Boolean, mirrors: List<UpdateMirror>): UpdateMirrorError? {
        if (!write(enabled, mirrors)) return UpdateMirrorError.SaveFailed
        publish(enabled, mirrors)
        return null
    }

    private fun read(): Read {
        if (!file.exists()) return Read.Missing
        if (!file.isFile) return Read.Failed
        return try {
            val o = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return Read.Failed
            val enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: false
            val array = o["mirrors"] as? JsonArray ?: JsonArray(emptyList())
            val mirrors = array.mapNotNull { element ->
                val m = element as? JsonObject ?: return@mapNotNull null
                val id = text(m, "id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = text(m, "name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val prefix = text(m, "prefix")?.let(UpdateMirrors::normalizePrefix) ?: return@mapNotNull null
                UpdateMirror(id, name, prefix)
            }.distinctBy { it.id }
            Read.Ok(enabled, mirrors)
        } catch (_: SerializationException) {
            Read.Failed
        } catch (_: IOException) {
            Read.Failed
        }
    }

    private fun write(enabled: Boolean, mirrors: List<UpdateMirror>): Boolean {
        val parent = file.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        return try {
            val json = storageJson.encodeToString(MirrorSettings(enabled, mirrors))
            // Write a temp file first, then rename it, so a kill halfway through does not leave half a file. Rename fails when the target already exists, so fall back to overwriting.
            AtomicFiles.write(file, json, durable = false, move = AtomicFiles.Move.Rename, fallback = AtomicFiles.Fallback.WriteDirect)
            file.isFile
        } catch (_: IOException) {
            false
        }
    }

    private sealed interface Read {
        data object Missing : Read
        data class Ok(val enabled: Boolean, val mirrors: List<UpdateMirror>) : Read
        data object Failed : Read
    }

    /** An empty name falls back to the host name; too long returns null (the caller reports [UpdateMirrorError.InvalidName]). */
    private fun displayName(name: String, prefix: String): String? {
        val trimmed = name.trim()
        if (trimmed.codePointCount(0, trimmed.length) > UpdateMirrors.NAME_MAX) return null
        return trimmed.ifEmpty { UpdateMirrors.host(prefix) }
    }

    companion object {
        const val FILE = "update-mirrors.json"
        private val storageJson = Json { encodeDefaults = true }

        private fun text(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private inline fun <T> List<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean =
            indices.any { predicate(it, this[it]) }
    }
}

/** Rules and presets for mirror addresses, plus the complete list of URLs to try for one update. */
object UpdateMirrors {
    /** Any more would fire too many requests at once during a single check. */
    const val MAX = 8
    const val NAME_MAX = 40

    /**
     * Presets: services that were verified on 2026-11-15 to download Claire release assets. Addresses keep only the scheme, host and path, with a trailing slash;
     * the complete download URL is `prefix + https://github.com/...`. The first two can also be used for update checks (one via the API, one via the releases page).
     */
    val PRESETS = listOf(
        UpdateMirror("preset-gh-proxy", "GH-Proxy", "https://gh-proxy.com/"),
        UpdateMirror("preset-ghfast", "GhFast", "https://ghfast.top/"),
        UpdateMirror("preset-ghproxy-net", "GhProxy.net", "https://ghproxy.net/"),
    )

    /**
     * Normalizes a user-entered mirror address into `https://host[:port][/path]/`:
     * only HTTPS is accepted, without credentials, query string or fragment; returns null when it is invalid.
     */
    fun normalizePrefix(raw: String): String? {
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        if (!url.isHttps) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (url.query != null || url.fragment != null) return null
        if (url.host.isBlank()) return null
        val base = url.newBuilder().query(null).fragment(null).build().toString()
        return base.trimEnd('/') + "/"
    }

    fun host(prefix: String): String = prefix.toHttpUrlOrNull()?.host.orEmpty()

    /**
     * The complete URLs tried in order for one update: user mirrors (when enabled, in list order) first, official GitHub always last.
     * When mirrors are disabled or the list is empty, only the official URL is present.
     */
    fun candidates(officialUrl: String, settings: MirrorSettings): List<String> = buildList {
        if (settings.enabled) settings.mirrors.forEach { add(it.prefix + officialUrl) }
        add(officialUrl)
    }.distinct()
}
