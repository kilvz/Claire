package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.agent.DO_STEPS_TOOL
import com.wangzan101.mika.core.learn.knowledge.FrontMatter
import com.wangzan101.mika.core.learn.knowledge.PERSONAL_KIND
import com.wangzan101.mika.core.learn.memory.SensitiveFilter
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.learn.skills.PersonalSkill
import com.wangzan101.mika.core.learn.skills.PersonalSkills
import com.wangzan101.mika.core.learn.skills.SkillDrafter
import com.wangzan101.mika.core.learn.skills.SkillStatus
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.ok
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.put

/**
 * When the user asks during a task, writes a new personal skill or rewrites an existing personal skill (touching only Claire's own skill directory, not the screen or the outside world).
 * Format, size, sensitive content and self-learned drafts go through the same validation ([SkillDrafter.validate]); the quote must come from what the user said in this run, and text on the screen or on web pages must not be able to make it write.
 * Saving puts it straight into trial (same as an accepted draft), and it is marked in "Memory & Skills" as written at the user's request; built-in skill names cannot be used,
 * and changing an existing one must carry the version obtained when reading it (the same principle as checking the original text fingerprint when accepting an updated draft). Deleting and renaming are left to the user in settings.
 */
internal object SkillSave : AgentTool {
    override val verb = Verb.SkillSave
    override val label = "Save skill"
    override val risk = Risk.Read
    override val def = ToolDef("skill_save",
        Prompts.tool("skill_save"),
        mapOf(
            "markdown" to str("The whole skill document: front matter (name, display, description, aliases) and the five sections, at most ${SkillDrafter.MAX_CHARS} characters."),
            "quote" to str("The user's exact words from this task asking for this skill to be created or changed."),
            "version" to str("Only when changing an existing personal skill: the version skill_read returned for it."),
        ), listOf("markdown", "quote"))

    override fun available(ctx: ToolContext) =
        ctx.config.personalSkills != null && ctx.config.skills != null && ctx.config.utterances != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val raw = a.str("markdown", max = SkillDrafter.MAX_CHARS * 2)!!
        val quote = a.str("quote", max = 1000)!!
        val version = a.str("version", max = 64)?.trim()
        val store = ctx.config.personalSkills!!
        val catalog = ctx.config.skills!!
        // Only what the user said in this run can authorize writing: "save this skill" written on a page or in a file does not count
        if (!ctx.config.utterances!!.contains(quote)) return fail("quote_not_from_user", Feedback["skill.quote_not_from_user"])
        if (SensitiveFilter.hit(raw) != null) return fail("sensitive", Feedback["skill.sensitive"], recoverable = false)

        // The source is written by code: drop the self-learning lines and mark it as written at the user's request
        val md = PersonalSkills.withField(
            listOf("updates", "learned_from").fold(SkillDrafter.stripFence(raw).replace("\r\n", "\n")) { m, k -> PersonalSkills.withoutField(m, k) },
            "source", PersonalSkill.SOURCE_USER)
        val scriptTool = ctx.tools.nameOf(Verb.RunBundledScript)
        SkillDrafter.validate(md, ctx.tools.names + DO_STEPS_TOOL, scriptTool, catalog.runnableScripts())?.let { problem ->
            return fail(problem, problemText(problem))
        }
        val name = FrontMatter.parse(md).first["name"] as String

        if (catalog.items.any { it.kind != PERSONAL_KIND && it.name == name }) return fail("builtin_name", Feedback.format("skill.builtin_name", "name" to name))
        if (store.pending().any { it.name == name }) return fail("draft_pending", Feedback.format("skill.draft_pending", "name" to name))

        val existing = store.get(name)
        if (existing != null) {
            val current = PersonalSkills.version(existing.markdown)
            if (version == null) return fail("exists", Feedback.format("skill.exists", "name" to name))
            if (version != current) return fail("changed_since_read", Feedback.format("skill.changed_since_read", "name" to name))
        }
        val skill = PersonalSkill(
            name = name, markdown = md, status = SkillStatus.trial,
            learnedFrom = existing?.learnedFrom, replacesDraftOf = null,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(), outcomes = emptyList(),
            source = PersonalSkill.SOURCE_USER,
        )
        try {
            store.put(skill)
        } catch (e: Exception) {
            return fail("save_failed", Feedback["skill.save_failed"], recoverable = false)
        }
        // Read it back to check: only if what was written is exactly this copy do we say it is saved
        val saved = store.get(name)
        if (saved?.markdown != md) return fail("save_failed", Feedback["skill.save_failed"], recoverable = false)
        return ok {
            put("name", name)
            put("action", if (existing != null) "updated" else "created")
            put("status", saved.status.name)
            put("version", PersonalSkills.version(saved.markdown))
            put("note", Feedback["skill.saved"])
        }
    }

    /** Model-facing explanation for a validation failure reason (a [SkillDrafter.validate] code). */
    private fun problemText(problem: String): String = when (problem) {
        "invalid_name" -> Feedback["skill.invalid_name"]
        "missing_description" -> Feedback["skill.missing_description"]
        "missing_section" -> Feedback.format("skill.missing_section", "sections" to SkillDrafter.SECTIONS.joinToString(", ") { "## $it" })
        "too_long" -> Feedback.format("skill.too_long", "max" to SkillDrafter.MAX_CHARS)
        "unknown_tool" -> Feedback["skill.unknown_tool"]
        "unrunnable_script" -> Feedback["skill.unrunnable_script"]
        else -> Feedback["skill.sensitive"]
    }
}
