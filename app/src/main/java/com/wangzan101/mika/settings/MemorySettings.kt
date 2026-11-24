package com.wangzan101.mika.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Switches for "memory and skills": [useMemory] is the master switch (off = no retrieval, no injection, no extraction, no memory tools offered);
 * [autoRemember] is "automatically remember things I said"; [learnSkills] is "learn skills from completed tasks" (on by default; learned skills are only used once the user accepts them).
 */
data class MemorySwitches(
    val useMemory: Boolean = true,
    val autoRemember: Boolean = true,
    val learnSkills: Boolean = true,
    /** Automatically record one cross-session summary when a task ends, for retrieval by similar tasks later; when off, nothing is recorded and no retrieval tool is offered either. */
    val rememberTasks: Boolean = true,
    /**
     * "Let Claire summarize and record on her own": when the user asks her to remember something, specific values in the content that the user did not say personally (that she summarized or inferred)
     * are stored too, with the source marked separately as summarized by Claire. Off by default: only what the user said personally is recorded, nothing else is accepted.
     */
    val inferMemories: Boolean = false,
) {
    val taskMemoryEnabled: Boolean get() = useMemory && rememberTasks
}

/**
 * Switches are stored in one small JSON file; if it cannot be read, the defaults are used.
 * Files stored by older versions lack [LEARN_DEFAULT_APPLIED]: back then "learn skills" defaulted to off, so it is turned on once while reading; afterwards every save includes the key, and it stays off if the user turns it off manually.
 */
class MemorySettings(private val file: File) {
    private val _switches = MutableStateFlow(read())
    val switches: StateFlow<MemorySwitches> = _switches.asStateFlow()

    @Synchronized fun update(transform: (MemorySwitches) -> MemorySwitches) {
        val next = transform(_switches.value)
        file.parentFile?.mkdirs()
        file.writeText(buildJsonObject {
            put("useMemory", next.useMemory); put("autoRemember", next.autoRemember); put("learnSkills", next.learnSkills)
            put("rememberTasks", next.rememberTasks); put("inferMemories", next.inferMemories)
            put(LEARN_DEFAULT_APPLIED, true)
        }.toString())
        _switches.value = next
    }

    private fun read(): MemorySwitches {
        val o = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull() ?: return MemorySwitches()
        fun b(k: String, d: Boolean) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: d
        val def = MemorySwitches()
        val upgraded = b(LEARN_DEFAULT_APPLIED, false)
        return MemorySwitches(b("useMemory", def.useMemory), b("autoRemember", def.autoRemember),
            if (upgraded) b("learnSkills", def.learnSkills) else def.learnSkills,
            b("rememberTasks", def.rememberTasks), b("inferMemories", def.inferMemories))
    }

    private companion object {
        const val LEARN_DEFAULT_APPLIED = "learnDefaultApplied"
    }
}
