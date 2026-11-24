package com.wangzan101.mika.core.learn.skills

import com.wangzan101.mika.core.learn.knowledge.FrontMatter

/**
 * A SKILL.md shared by someone else becomes a draft awaiting acceptance (#165). It does not go through the model and does not go straight into trial: the user must review it under "pending confirmation" and accept it before it is used.
 * The source is written by code as imported ([PersonalSkill.SOURCE_IMPORTED]) and the lines used only for self-learning are removed; format, size and sensitive content go through the same validation as self-learning drafts
 * ([SkillDrafter.validate]). Names of built-in skills cannot be used; when a personal skill with the same name already exists, the result becomes an update draft for it, so the changed lines can be seen before accepting.
 */
object SkillImport {
    sealed interface Result {
        /** The id to store it under while it awaits acceptance, and the draft. */
        data class Ready(val id: String, val skill: PersonalSkill) : Result
        /** Rejected: [problem] is a [SkillDrafter.validate] code, or `builtin_name` or `draft_pending`. */
        data class Rejected(val problem: String) : Result
    }

    /** Lines that only make sense on this device (source, which run it was learned from, what it updates); they are removed when sharing and rewritten by code on import. */
    private val LOCAL_FIELDS = listOf("updates", "learned_from", "source")

    /** The full text as shared: [LOCAL_FIELDS] are removed, everything else is left as-is. */
    fun shareable(markdown: String): String =
        LOCAL_FIELDS.fold(markdown.replace("\r\n", "\n")) { m, k -> PersonalSkills.withoutField(m, k) }

    fun prepare(
        raw: String, store: PersonalSkills, builtinNames: Set<String>,
        toolNames: Set<String>, scriptTool: String?, runnableScripts: Set<String> = emptySet(),
        id: String, now: Long,
    ): Result {
        if (raw.length > SkillDrafter.MAX_CHARS * 2) return Result.Rejected("too_long")
        val md = PersonalSkills.withField(shareable(SkillDrafter.stripFence(raw)), "source", PersonalSkill.SOURCE_IMPORTED)
        SkillDrafter.validate(md, toolNames, scriptTool, runnableScripts)?.let { return Result.Rejected(it) }
        val name = FrontMatter.parse(md).first["name"] as String
        if (name in builtinNames) return Result.Rejected("builtin_name")
        if (store.pending().any { it.name == name || it.replacesDraftOf == name }) return Result.Rejected("draft_pending")
        val existing = store.get(name)
        return Result.Ready(id, PersonalSkill(
            name = name, markdown = md, status = SkillStatus.pending,
            learnedFrom = null, replacesDraftOf = existing?.name,
            createdAt = now, outcomes = emptyList(),
            baseHash = existing?.let { PersonalSkills.fingerprint(it.markdown) },
            source = PersonalSkill.SOURCE_IMPORTED,
        ))
    }
}
