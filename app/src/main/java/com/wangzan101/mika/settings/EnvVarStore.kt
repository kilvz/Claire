package com.wangzan101.mika.settings

import android.content.Context
import com.wangzan101.mika.core.workspace.AtomicFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * Settings › Environment variables: name=value key-value pairs that plugin scripts and prompt hooks read when they run (e.g. `MIKA_TRANSLATE_BASE/KEY/MODEL`).
 * Values may contain secrets, but following "functionality first, security later" they are currently stored in plaintext; encryption is left as a later item.
 */
class EnvVarStore(private val dir: File) {
    private val file = File(dir, FILE)
    @Volatile private var cached: Map<String, String>? = null
    private val _changes = MutableStateFlow(emptyMap<String, String>())

    /** Current values observed by the settings page; updated after every change. */
    val changes: StateFlow<Map<String, String>> get() = _changes.asStateFlow()

    @Synchronized fun all(): Map<String, String> {
        cached?.let { return it }
        val m = runCatching { read() }.getOrDefault(emptyMap())
        cached = m
        _changes.value = m
        return m
    }

    /** Stores [k]=[v]; an empty [v] is equivalent to deleting this entry. */
    fun set(k: String, v: String) {
        synchronized(this) {
            val next = all().toMutableMap()
            if (v.isEmpty()) next.remove(k) else next[k] = v
            write(next)
        }
    }

    fun remove(k: String) {
        synchronized(this) {
            val next = all().toMutableMap()
            next.remove(k)
            write(next)
        }
    }

    fun clear() {
        synchronized(this) { write(emptyMap()) }
    }

    private fun read(): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val obj = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return emptyMap()
        return obj.entries.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
    }

    private fun write(m: Map<String, String>) {
        dir.mkdirs()
        val obj = buildJsonObject { m.forEach { (k, v) -> put(k, v) } }
        AtomicFiles.write(file, obj.toString(), durable = false)
        cached = m
        _changes.value = m
    }

    companion object {
        private const val FILE = "env-vars.json"
        private val Json = Json { ignoreUnknownKeys = true }

        @Volatile private var instance: EnvVarStore? = null

        fun get(context: Context): EnvVarStore = instance ?: synchronized(this) {
            instance ?: EnvVarStore(File(context.applicationContext.filesDir, "settings")).also { instance = it }
        }
    }
}
