package com.wangzan101.mika.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.util.UUID

/** A recommended task on the home screen. Tapping it only fills [prompt] into the input box; it is not sent automatically. */
data class RecommendedTask(val id: String, val title: String, val prompt: String, val fillIn: Boolean = false)

/** The reason a save was rejected. The UI only updates the list after a successful write. */
enum class RecommendedTaskError { BlankTitle, BlankPrompt, TooLong, TooMany, TooManyFields, SaveFailed, Unreadable }

/**
 * Home-screen recommended tasks. This is the same list as the built-in examples: when the file does not exist yet it is initialized once from what [ensureLoaded] passes in,
 * and from then on the user's additions, edits and deletions are the list itself. An empty list is written to the file too, so deleted default tasks are not added back on the next launch.
 * When the file cannot be read it is never overwritten; clearing has to be requested explicitly by the user.
 */
class RecommendedTaskStore(private val file: File, private val ids: () -> String = { UUID.randomUUID().toString() }) {
    private val _tasks = MutableStateFlow<List<RecommendedTask>>(emptyList())
    private val _unreadable = MutableStateFlow(false)
    private var loaded = false
    private var attempted = false

    val tasks: StateFlow<List<RecommendedTask>> = _tasks.asStateFlow()

    /** An existing file cannot be read. Adding, editing and deleting are blocked then, so an empty list cannot overwrite content that has not been read yet. */
    val unreadable: StateFlow<Boolean> = _unreadable.asStateFlow()

    /**
     * Load the list the first time it is opened: when the file does not exist yet, initialize it once from [seed]. When a list already exists, drop the examples the user never touched
     * and that have been retired from the built-ins ([retired]); entries whose title or prompt was edited, and ones the user added, are left alone.
     */
    @Synchronized
    fun ensureLoaded(retired: () -> List<Pair<String, String>> = { emptyList() }, seed: () -> List<Pair<String, String>>) {
        if (loaded || attempted) return
        attempted = true
        when (val read = read()) {
            Read.Missing -> {
                val seeded = seed().mapNotNull { (title, prompt) ->
                    (normalize(title, prompt) as? Normalized.Ok)?.let { RecommendedTask(ids(), it.title, it.prompt) }
                }
                if (write(seeded)) publish(seeded)
            }
            is Read.Ok -> {
                val gone = retired().toSet()
                publish(if (gone.isEmpty()) read.tasks else read.tasks.filterNot { (it.title to it.prompt) in gone })
            }
            Read.Corrupt, Read.Failed -> {
                _tasks.value = emptyList()
                _unreadable.value = true
                loaded = true
            }
        }
    }

    @Synchronized
    fun add(title: String, prompt: String, fillIn: Boolean = false): RecommendedTaskError? {
        guard()?.let { return it }
        val text = normalize(title, prompt)
        if (text is Normalized.Invalid) return text.error
        if (_tasks.value.size >= MAX_TASKS) return RecommendedTaskError.TooMany
        val ok = text as Normalized.Ok
        if (fillIn && TaskTemplate.fields(ok.prompt).size > TaskTemplate.MAX_FIELDS) return RecommendedTaskError.TooManyFields
        return commit(_tasks.value + RecommendedTask(ids(), ok.title, ok.prompt, fillIn))
    }

    @Synchronized
    fun update(id: String, title: String, prompt: String, fillIn: Boolean? = null): RecommendedTaskError? {
        guard()?.let { return it }
        val text = normalize(title, prompt)
        if (text is Normalized.Invalid) return text.error
        val index = _tasks.value.indexOfFirst { it.id == id }
        if (index < 0) return null
        val ok = text as Normalized.Ok
        val enabled = fillIn ?: _tasks.value[index].fillIn
        if (enabled && TaskTemplate.fields(ok.prompt).size > TaskTemplate.MAX_FIELDS) return RecommendedTaskError.TooManyFields
        return commit(_tasks.value.mapIndexed { i, task -> if (i == index) task.copy(title = ok.title, prompt = ok.prompt, fillIn = enabled) else task })
    }

    @Synchronized
    fun delete(id: String): RecommendedTaskError? {
        guard()?.let { return it }
        if (_tasks.value.none { it.id == id }) return null
        return commit(_tasks.value.filterNot { it.id == id })
    }

    /** Reorder using the complete ID list captured when the drag started, so tasks are not lost during concurrent additions or deletions. */
    @Synchronized
    fun reorder(ids: List<String>): RecommendedTaskError? {
        guard()?.let { return it }
        val current = _tasks.value
        if (ids.size != current.size || ids.toSet() != current.map { it.id }.toSet()) return RecommendedTaskError.SaveFailed
        val byId = current.associateBy { it.id }
        val reordered = ids.map { byId.getValue(it) }
        return if (reordered == current) null else commit(reordered)
    }

    /** When the user explicitly discards an unreadable old file, replace it with an empty list. */
    @Synchronized
    fun discardUnreadable(): RecommendedTaskError? {
        if (!_unreadable.value) return null
        return commit(emptyList())
    }

    private fun guard(): RecommendedTaskError? = when {
        _unreadable.value -> RecommendedTaskError.Unreadable
        !loaded && file.exists() -> RecommendedTaskError.Unreadable
        else -> null
    }

    private fun publish(tasks: List<RecommendedTask>) {
        _tasks.value = tasks
        _unreadable.value = false
        loaded = true
    }

    private fun commit(tasks: List<RecommendedTask>): RecommendedTaskError? {
        if (!write(tasks)) return RecommendedTaskError.SaveFailed
        publish(tasks)
        return null
    }

    private fun read(): Read {
        if (!file.exists()) return Read.Missing
        if (!file.isFile) return Read.Failed
        return try {
            val array = Json.parseToJsonElement(file.readText()) as? JsonArray ?: return Read.Corrupt
            val tasks = array.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val id = text(o, "id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = text(o, "title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val prompt = text(o, "prompt")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                RecommendedTask(id, title, prompt, (o["fillIn"] as? JsonPrimitive)?.booleanOrNull == true)
            }.distinctBy { it.id }
            Read.Ok(tasks)
        } catch (_: SerializationException) {
            Read.Corrupt
        } catch (_: IOException) {
            Read.Failed
        }
    }

    private fun write(tasks: List<RecommendedTask>): Boolean {
        val parent = file.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        return try {
            val json = JsonArray(tasks.map { task ->
                buildJsonObject {
                    put("id", task.id)
                    put("title", task.title)
                    put("prompt", task.prompt)
                    if (task.fillIn) put("fillIn", true)
                }
            }).toString()
            // Write a temporary file and then rename it, so being killed halfway leaves no partial file. The rename fails when the target already exists, in which case the file is overwritten directly.
            val tmp = File(parent, "${file.name}.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.writeText(json)
                tmp.delete()
            }
            file.isFile
        } catch (_: IOException) {
            false
        }
    }

    private sealed interface Read {
        data object Missing : Read
        data class Ok(val tasks: List<RecommendedTask>) : Read
        data object Corrupt : Read
        data object Failed : Read
    }

    private sealed interface Normalized {
        data class Ok(val title: String, val prompt: String) : Normalized
        data class Invalid(val error: RecommendedTaskError) : Normalized
    }

    companion object {
        const val FILE = "recommended-tasks-v2.json"
        const val TITLE_MAX = 500
        const val PROMPT_MAX = 8_000
        const val MAX_TASKS = 50

        private fun text(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** Only leading and trailing whitespace is trimmed for the emptiness check; spaces and line breaks in the middle are preserved. */
        private fun normalize(title: String, prompt: String): Normalized {
            val t = title.trim()
            val p = prompt.trim()
            return when {
                t.isEmpty() -> Normalized.Invalid(RecommendedTaskError.BlankTitle)
                p.isEmpty() -> Normalized.Invalid(RecommendedTaskError.BlankPrompt)
                t.codePointCount(0, t.length) > TITLE_MAX || p.codePointCount(0, p.length) > PROMPT_MAX ->
                    Normalized.Invalid(RecommendedTaskError.TooLong)
                else -> Normalized.Ok(t, p)
            }
        }
    }
}
