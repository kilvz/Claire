package com.wangzan101.mika.settings

import com.wangzan101.mika.core.prompt.PersonaPrompt
import com.wangzan101.mika.core.prompt.PersonaStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * One persona: name, description, a set of options and a prompt body. The options and body are assembled into the system prompt by [com.wangzan101.mika.core.prompt.PersonaPrompt],
 * and the fixed header and footer are hardcoded. A persona carries no tools, permissions, memory or model, and takes no part in choosing them.
 */
@Serializable
data class Persona(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val prompt: String,
    /** The options selected in the UI; when an old file lacks this field, everything is at its default. */
    val style: PersonaStyle = PersonaStyle.DEFAULT,
    /** Nickname: what the assistant calls itself; empty means the default Claire. Sanitized by [PersonaPrompt.nickname] before entering the prompt. */
    val nickname: String = "",
    /** Incremented on every save; v1 keeps only the latest body and does not retain old revisions. */
    val revision: Int = 1,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** v1 ships no built-in examples; the field is reserved for bundled releases later. */
    val builtin: Boolean = false,
)

/** This persona's self-name: the sanitized nickname, or Claire when nothing was entered. */
val Persona.assistant: String get() = PersonaPrompt.nickname(nickname)

/** This persona actually changes nothing: all options at default, no instructions, and the default nickname. */
val Persona.changesNothing: Boolean
    get() = style.isDefault && prompt.isBlank() && assistant == PersonaPrompt.DEFAULT_NICKNAME

@Serializable
private data class PersonaFile(
    val version: Int = 1,
    val personas: List<Persona> = emptyList(),
    /** Id of the persona selected in settings and currently in use; null when there is none (old files lack this field, which amounts to none in use). */
    val active: String? = null,
)

/**
 * Persona settings: `files/settings/personas.json`. Atomic writes, with a version number; when it cannot be read, the original file is saved as `.bad-<time>` and treated as an empty list.
 * Besides the persona list, it also remembers the "currently in use" one chosen in settings ([activeId]); it is written to disk in the same pass as the list,
 * so the active one can never point at a persona that no longer exists.
 * It contains no keys and is not encrypted; one instance per process, provided by [com.wangzan101.mika.MikaApp].
 */
class PersonaStore(private val dir: File) {
    private val file = File(dir, "personas.json")
    // coerceInputValues: unrecognized values in the options (from later versions or hand-edited files) are read as defaults, so the whole file is not treated as corrupt
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    private val _personas: MutableStateFlow<List<Persona>>
    private val _activeId: MutableStateFlow<String?>

    init {
        val loaded = load()
        _personas = MutableStateFlow(loaded.personas)
        _activeId = MutableStateFlow(loaded.active)
    }

    val personas: StateFlow<List<Persona>> = _personas.asStateFlow()

    /** Id of the active persona chosen in settings; null when none is chosen or the chosen one was deleted. */
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    /** The active persona; null when there is none (Claire speaks in her usual way). */
    fun active(): Persona? = get(_activeId.value)

    /** Selects a persona ([id] must be in the list), or pass null to go back to having none active. Returns true once written. */
    @Synchronized fun setActive(id: String?): Boolean {
        if (id != null && get(id) == null) return false
        if (id == _activeId.value) return true
        return runCatching { save(_personas.value, id); true }.getOrDefault(false)
    }

    fun get(id: String?): Persona? = id?.takeIf(ID::matches)?.let { key -> _personas.value.firstOrNull { it.id == key } }

    @Synchronized fun add(
        name: String, description: String, prompt: String, style: PersonaStyle = PersonaStyle.DEFAULT,
    ): Result<Persona> = runCatching {
        val now = System.currentTimeMillis()
        val persona = Persona(
            name = name.trim(), description = description.trim(), prompt = prompt.trim(), style = style,
            createdAt = now, updatedAt = now,
        )
        validate(persona)
        checkRoom()
        save(_personas.value + persona)
        persona
    }

    /**
     * Edits the active persona and writes it immediately. When there is none active yet, a new persona is created with [defaultName] and set active (in the same write),
     * so the user need not "create a persona" before adjusting it; but if the result still changes nothing (all options at default, empty instructions, default nickname) it is not created, to avoid leaving an empty persona behind.
     */
    @Synchronized fun editActive(defaultName: String, change: (Persona) -> Persona): Result<Persona> = runCatching {
        val current = active()
        if (current != null) return@runCatching update(change(current)).getOrThrow()
        val now = System.currentTimeMillis()
        val created = change(Persona(name = defaultName.trim().take(PersonaPrompt.MAX_NAME), prompt = "", createdAt = now, updatedAt = now))
        if (created.changesNothing) return@runCatching created
        validate(created)
        checkRoom()
        save(_personas.value + created, created.id)
        created
    }

    /** Creates a fully default persona and sets it active: the user creates one in order to adjust it right away, and a fully default persona does not change Claire. */
    @Synchronized fun create(name: String): Result<Persona> = runCatching {
        val now = System.currentTimeMillis()
        val persona = Persona(name = name.trim(), prompt = "", createdAt = now, updatedAt = now)
        validate(persona)
        checkRoom()
        save(_personas.value + persona, persona.id)
        persona
    }

    @Synchronized fun rename(id: String, name: String): Result<Persona> = runCatching {
        update((get(id) ?: error("persona not found")).copy(name = name.trim())).getOrThrow()
    }

    @Synchronized fun update(persona: Persona): Result<Persona> = runCatching {
        validate(persona)
        val existing = _personas.value.firstOrNull { it.id == persona.id } ?: error("persona not found")
        // If the content did not change, do not write and do not bump the revision (the UI tapped the option that is already selected)
        if (persona.copy(revision = existing.revision, createdAt = existing.createdAt, updatedAt = existing.updatedAt, builtin = existing.builtin) == existing) {
            return@runCatching existing
        }
        val next = persona.copy(
            revision = existing.revision + 1,
            createdAt = existing.createdAt,
            updatedAt = System.currentTimeMillis(),
            builtin = existing.builtin,
        )
        save(_personas.value.map { if (it.id == persona.id) next else it })
        next
    }

    /** Duplicates one, with the name supplied by the UI (the "copy" wording differs per language), truncated when too long; the copy is not the active one. */
    @Synchronized fun duplicate(id: String, name: (String) -> String = { "$it (copy)" }): Result<Persona> = runCatching {
        val source = get(id) ?: error("persona not found")
        val now = System.currentTimeMillis()
        val copy = source.copy(
            id = UUID.randomUUID().toString(), name = name(source.name).trim().take(PersonaPrompt.MAX_NAME),
            revision = 1, createdAt = now, updatedAt = now, builtin = false,
        )
        validate(copy)
        checkRoom()
        save(_personas.value + copy)
        copy
    }

    @Synchronized fun delete(id: String): Boolean {
        val existing = get(id) ?: return false
        if (existing.builtin) return false
        val next = _personas.value.filterNot { it.id == id }
        // Deleting exactly the persona that is active: clear the active one in the same write
        return runCatching { save(next, _activeId.value.takeIf { it != id }); true }.getOrDefault(false)
    }

    private fun checkRoom() = check(_personas.value.size < MAX_PERSONAS) { "too many personas" }

    private fun validate(persona: Persona) {
        require(ID.matches(persona.id)) { "invalid persona id" }
        require(persona.name.isNotBlank()) { "name is required" }
        require(persona.name.length <= PersonaPrompt.MAX_NAME) { "name is too long" }
        require(persona.description.length <= MAX_DESCRIPTION) { "description is too long" }
        require(persona.prompt.length <= PersonaPrompt.MAX_PROMPT_CHARS) { "prompt is too long" }
        require(PersonaPrompt.nicknameAccepted(persona.nickname)) { "nickname has characters that cannot be used" }
    }

    @Synchronized private fun save(list: List<Persona>, active: String? = _activeId.value) {
        dir.mkdirs()
        val text = json.encodeToString(PersonaFile(personas = list, active = active))
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
        _personas.value = list
        _activeId.value = active
    }

    private class Loaded(val personas: List<Persona>, val active: String?)

    private fun load(): Loaded {
        if (!file.isFile) return Loaded(emptyList(), null)
        return runCatching {
            val stored = json.decodeFromString<PersonaFile>(file.readText())
            val personas = stored.personas.filter { ID.matches(it.id) }
            // The active one must point at a persona still in the list (hand-edited files and dangling ids left by older versions both count as none)
            Loaded(personas, stored.active?.takeIf { id -> personas.any { it.id == id } })
        }.getOrElse {
            // Keep an unreadable old file around so the next save does not simply overwrite it
            runCatching { file.renameTo(File(dir, "${file.name}.bad-${System.currentTimeMillis()}")) }
            Loaded(emptyList(), null)
        }
    }

    companion object {
        const val MAX_DESCRIPTION = 160
        /** Maximum number of personas stored; enough to be useful without making the list unreadable. */
        const val MAX_PERSONAS = 20
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")

        fun isValidId(id: String?): Boolean = id != null && ID.matches(id)
    }
}
