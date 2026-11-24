package com.wangzan101.mika.core.learn.skills

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.learn.knowledge.FrontMatter
import com.wangzan101.mika.core.learn.knowledge.PERSONAL_KIND
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.learn.memory.SensitiveFilter
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.PlaceKind
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.safety.verify.AnswerSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.net.UrlHost

/**
 * All the input for drafting a skill: [events] are this run's events, [toolNames] are Claire's current tool names,
 * and [tools] looks up what a tool does by name.
 */
/** [answer] is the final answer handed to the user; [sources] are the answer's sources found by code (see [AnswerSource]). */
data class DraftInput(
    val runId: String, val goal: String, val answer: String, val sources: List<AnswerSource>, val events: List<RunEvent>,
    val toolNames: Set<String>, val tools: ToolIndex,
)

/** Actions that get things done (phone, web, sandbox); reading the screen, looking up skills, wrapping up and the like do not count as steps. */
internal val ACTION_VERBS = setOf(Verb.Tap, Verb.Scroll, Verb.Swipe, Verb.TypeText, Verb.ClearText, Verb.Back, Verb.PressKey, Verb.LaunchApp, Verb.ShareText,
    Verb.Navigate, Verb.Upload, Verb.Tabs, Verb.RunCommand, Verb.RunBundledScript, Verb.WriteFile, Verb.ShareFile)

/** When drafting is worthwhile; the switch and the evaluation mode are decided by the caller. */
object Eligibility {
    const val MIN_ACTIONS = 5

    /** null = drafting is allowed; otherwise the reason not to draft. [done]: this run counts as getting the task done ([com.wangzan101.mika.core.agent.LearnGate]). */
    fun check(events: List<RunEvent>, followedPersonalSkill: String?, tools: ToolIndex, done: Boolean): String? {
        val ended = events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()
        return when {
            ended?.outcome != "succeeded" -> "not_succeeded"
            !done -> "not_done"
            // The user already had Claire write the skill down the way they wanted in this run, so do not start a separate draft
            events.any { it is RunEvent.ToolResult && tools.verb(it.tool) == Verb.SkillSave && it.resultJson.contains("\"ok\":true") } -> "skill_saved"
            // Failed actions do not count as steps (results may be truncated, so judge by the raw text)
            events.count { it is RunEvent.ToolResult && tools.verb(it.tool) in ACTION_VERBS && !it.resultJson.contains("\"ok\":false") } < MIN_ACTIONS -> "too_few_actions"
            else -> interrupted(events) ?: "followed_personal_skill".takeIf { followedPersonalSkill != null }
        }
    }

    /** Part of this run was not walked by Claire itself (takeover skipped, the user finished it themselves, a confirmation declined), so nothing can be learned from it: returns the reason, otherwise null. */
    fun interrupted(events: List<RunEvent>): String? = when {
        events.any { it is RunEvent.Takeover && it.outcome == "skipped" } -> "takeover_skipped"
        // The user finished it themselves: Claire never walked that part of the route
        events.any { it is RunEvent.Takeover && it.outcome == "user_finished" } -> "takeover_user_finished"
        events.any { it is RunEvent.Confirmation && it.decision in DECLINED } -> "confirmation_declined"
        else -> null
    }

    private val DECLINED = setOf("declined", "declined_before")
}

/**
 * One line of the action list: only the tool, the target element's name, the app or site it was in ([where], a site is written as "website hostname"), whether it took effect, and the error code.
 * [typed]: this step typed text.
 */
data class ActionLine(
    val step: Int, val tool: String, val target: String?, val where: String?, val changed: Boolean?, val error: String?,
    val typed: Boolean = false,
)

/** Build a compact action list from the run events. The full screen text is not taken; the text of `type_text` is not taken either (rendered as `<text>`). */
object ActionDigest {
    private val ELEMENT = Regex("\"ref\":\"(@e\\d+)\",\"role\":\"[^\"]*\",\"name\":(\"(?:[^\"\\\\]|\\\\.)*\")")
    private val CURRENT_APP = Regex("\"currentApp\":\"([^\"]+)\"")
    private val CURRENT_SITE = Regex("\"currentSite\":\"([^\"]+)\"")
    private val READS = setOf(Verb.Observe, Verb.ListElements)

    fun of(events: List<RunEvent>, tools: ToolIndex, maxLines: Int = 60): List<ActionLine> {
        val args = HashMap<Int, JsonObject>()
        var names = emptyMap<String, String>()
        // Which app on the phone and which site on the web; onWeb says which side we are on right now
        var app: String? = null
        var site: String? = null
        var onWeb = false
        val out = mutableListOf<ActionLine>()
        for (e in events) {
            when (e) {
                is RunEvent.ModelReply -> e.argsJson?.let(::parse)?.let { args[e.step] = it }
                is RunEvent.Observed -> if (e.kind == ObsKind.Read.wire) when (e.place?.kind) {
                    PlaceKind.Site -> { site = e.place.name; onWeb = true }
                    PlaceKind.Page -> UrlHost.of(e.place.name)?.let { site = it; onWeb = true }
                    PlaceKind.App -> { app = e.place.name; onWeb = false }
                    else -> Unit
                }
                is RunEvent.ToolResult -> {
                    // A screen-read result may be truncated into incomplete JSON, so take ref→name from each element by text; used only to name the target
                    val verb = tools.verb(e.tool)
                    if (verb in READS) {
                        names = ELEMENT.findAll(e.resultJson).associate { m -> m.groupValues[1] to unquote(m.groupValues[2]) }
                        CURRENT_APP.find(e.resultJson)?.let { app = it.groupValues[1]; onWeb = false }
                        CURRENT_SITE.find(e.resultJson)?.let { site = it.groupValues[1]; onWeb = true }
                    } else {
                        // The screen included in an action result also reports where we are
                        CURRENT_SITE.find(e.resultJson)?.let { site = it.groupValues[1]; onWeb = true }
                            ?: CURRENT_APP.find(e.resultJson)?.let { app = it.groupValues[1]; onWeb = false }
                    }
                    if (verb !in ACTION_VERBS || out.size >= maxLines) continue
                    val a = args[e.step]
                    fun arg(k: String) = (a?.get(k) as? JsonPrimitive)?.contentOrNull
                    if (verb == Verb.LaunchApp || verb == Verb.ShareText) arg("package")?.let { app = it; onWeb = false }
                    if (verb == Verb.Navigate) arg("url")?.let(UrlHost::of)?.let { site = it; onWeb = true }
                    val target = when (verb) {
                        // Record only which app it was shared to, not the text: it may contain personal information
                        Verb.LaunchApp, Verb.ShareText -> arg("package")
                        Verb.PressKey -> arg("key") ?: arg("keys")
                        // Record only the site, not the URL: the query string may contain personal information
                        Verb.Navigate -> arg("url")?.let(UrlHost::of)
                        Verb.Tabs -> arg("action")
                        // Commands, paths and uploaded files may contain personal information: do not record them
                        Verb.RunCommand, Verb.RunBundledScript, Verb.WriteFile, Verb.ShareFile, Verb.Upload -> null
                        else -> arg("ref")?.let { names[it] }?.takeIf { it.isNotBlank() }
                    }
                    val result = parse(e.resultJson)
                    val error = (result?.get("error") as? JsonPrimitive)?.contentOrNull
                    val where = if (onWeb) site?.let { "website $it" } else app
                    out += ActionLine(e.step, e.tool, target, where, e.changed, error, typed = verb == Verb.TypeText)
                }
                else -> Unit
            }
        }
        return out
    }

    /** Actions whose target is not taken from a screen read. */

    fun render(lines: List<ActionLine>): String = lines.joinToString("\n") { l ->
        buildString {
            append(l.step).append(". ").append(l.tool)
            l.target?.let { append(" \"").append(it).append("\"") }
            if (l.typed) append(" typed <text>")
            l.where?.let { append(" on ").append(it) }
            when {
                l.error != null -> append(" error: ").append(l.error)
                l.changed == true -> append(" took effect")
                l.changed == false -> append(" screen unchanged")
            }
        }
    }

    private fun parse(s: String): JsonObject? = try {
        Json.parseToJsonElement(s) as? JsonObject
    } catch (e: Exception) {
        null
    }

    private fun unquote(s: String) = try {
        (Json.parseToJsonElement(s) as? JsonPrimitive)?.contentOrNull ?: ""
    } catch (e: Exception) {
        ""
    }
}

sealed interface DraftResult {
    /**
     * [isUpdateOf]: the model judged this to be the same kind of thing as an existing personal skill, so it is drafted as an update to it;
     * [replacesPending]: judged to be the same kind of thing as a draft awaiting confirmation; this is that draft's id — store it back in place of that draft instead of starting a new one.
     */
    data class Draft(val skill: PersonalSkill, val isUpdateOf: String?, val replacesPending: String? = null) : DraftResult
    data class Dropped(val reason: String) : DraftResult
}

/**
 * A single model call (without tools) turns a task that got done into a skill draft, which code then validates; if it does not pass, it is dropped without bothering the user.
 * The model receives the goal, the final answer, where the answer came from (which step read what, and where) and the action list; values in the goal and typed text become placeholders, and the raw text that was read is not included.
 * Existing personal skills are listed too (along with drafts awaiting confirmation): which of them this run is the same kind of thing as is stated by the model in the front matter as `updates: <name>`, and code only checks that the name really exists.
 */
class SkillDrafter(private val model: ChatModel) {
    suspend fun draft(input: DraftInput, existing: PersonalSkills, catalog: GuideCatalog): DraftResult {
        // This run's specific values (quoted content in the goal, the text typed this time, numbers) are replaced with placeholders, so the skill still works next time on a different subject;
        // the answer and the on-screen words are sent and received as-is: the page and button names in them are the procedure itself
        val literals = SkillRedactor.literals(input.goal, emptyList()) + SkillRedactor.textLiterals(typedTexts(input.events, input.tools))
        val keepDigits = catalogDigits(catalog)
        // Existing skill names are given as-is (replacing digits would break the match); they are the user's own skills, not this run's values
        val request = SkillRedactor.scrubNumbers(SkillRedactor.redact(request(input), literals), keepDigits) + "\n" + existingSkills(existing)
        val reply = try {
            model.complete(PROMPT, listOf(Message(Role.User, listOf(Part.Text(request)), Author.Mika)), emptyList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return DraftResult.Dropped("model_error")
        }
        val redacted = SkillRedactor.redact(stripFence(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }), literals)
        // Check it as-is first: if the draft contains passwords, card numbers and the like, drafting went off track, and it is rejected even after numbers are replaced
        if (SensitiveFilter.hit(redacted) != null) return DraftResult.Dropped("sensitive")
        // Read which one it updates before replacing numbers: digits in a skill name are part of the name
        val updates = (FrontMatter.parse(redacted).first["updates"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val md = PersonalSkills.withoutField(SkillRedactor.scrubNumbers(redacted, keepDigits), "updates")

        validate(md, input.toolNames, input.tools.nameOf(Verb.RunBundledScript), catalog.runnableScripts())?.let { return DraftResult.Dropped(it) }
        val meta = FrontMatter.parse(md).first
        val name = meta["name"] as String

        // The model points at a draft awaiting confirmation: replace that one (if it was an update to an existing skill, the new draft keeps updating that one);
        // if it points at an accepted one (in use, trial or suspended): treat it as an update to it. A name that does not match (no such skill, or it is a built-in) does not count and is treated as a new skill
        val pending = updates?.let { u -> existing.drafts().entries.firstOrNull { it.value.name == u } }
        val updateOf = if (pending != null) pending.value.replacesDraftOf else updates?.takeIf { existing.get(it) != null }
        // A new draft must not take the place of a built-in skill, an existing personal skill or a draft; an update draft always uses the name of the one it updates (accepting it overwrites that one, so the header name has to match)
        if (pending == null && updateOf == null &&
            (existing.get(name) != null || existing.pending().any { it.name == name } || catalog.items.any { it.name == name }))
            return DraftResult.Dropped("name_taken")
        val finalName = pending?.value?.name ?: updateOf ?: name
        // Which run it came from is written by code: numbers in the request were all replaced with placeholders, so the model cannot copy the real run id
        val finalMd = PersonalSkills.withField(PersonalSkills.withName(md, finalName), "learned_from", input.runId)

        val skill = PersonalSkill(
            name = finalName, markdown = finalMd, status = SkillStatus.pending, learnedFrom = input.runId, replacesDraftOf = updateOf,
            createdAt = input.events.lastOrNull()?.atMs ?: 0L, outcomes = emptyList(),
            baseHash = updateOf?.let { name -> existing.get(name)?.markdown?.let { PersonalSkills.fingerprint(it) } },
        )
        return DraftResult.Draft(skill, updateOf, pending?.key)
    }

    /** Existing personal skills (accepted ones first, drafts awaiting confirmation after, each sorted by name), one line each: name, display name, description. */
    private fun existingSkills(existing: PersonalSkills): String {
        val lines = (existing.list().sortedBy { it.name } + existing.pending().sortedBy { it.name }).map { s ->
            val meta = FrontMatter.parse(s.markdown).first
            "- ${s.name}(${meta["display"] as? String ?: s.name}):${meta["description"] as? String ?: ""}"
        }
        return "Existing personal skills (accepted first, drafts awaiting confirmation after):" + if (lines.isEmpty()) " none" else "\n" + lines.joinToString("\n")
    }


    /** The text typed with type_text this run (for password fields it was already replaced with *** in the record). */
    private fun typedTexts(events: List<RunEvent>, tools: ToolIndex): List<String> = events.filterIsInstance<RunEvent.ModelReply>()
        .filter { tools.verb(it.tool) == Verb.TypeText }
        .mapNotNull { e ->
            val o = try { Json.parseToJsonElement(e.argsJson ?: return@mapNotNull null) as? JsonObject } catch (x: Exception) { null }
            (o?.get("text") as? JsonPrimitive)?.contentOrNull?.takeIf { it != "***" }
        }

    /** Digits that come with built-in skill names, display names and aliases (such as the name of a railway app); these are kept in the draft. */
    private fun catalogDigits(catalog: GuideCatalog): Set<String> =
        catalog.items.filter { it.kind != PERSONAL_KIND }.flatMap { listOf(it.name, it.display) + it.aliases }
            .flatMap { Regex("\\d{2,}").findAll(SkillRedactor.norm(it)).map { m -> m.value }.toList() }.toSet()

    private fun request(input: DraftInput): String = buildString {
        appendLine("Run id: ${input.runId}")
        appendLine("Goal: ${input.goal}")
        appendLine("Final answer: ${input.answer}")
        if (input.sources.isNotEmpty()) {
            appendLine("Where the answer came from:")
            input.sources.forEach { s -> appendLine("- Step ${s.step} ${s.kind} ${s.place.orEmpty()}" + s.title?.let { " \"$it\"" }.orEmpty()) }
        }
        appendLine("Actions:")
        appendLine(ActionDigest.render(ActionDigest.of(input.events, input.tools)))
        append("Available tools: ").append(input.toolNames.sorted().joinToString(", "))
    }

    companion object {
        /** Strip the code block fence wrapped around the model's reply. */
        fun stripFence(text: String): String {
            val t = text.trim()
            if (!t.startsWith("```")) return t
            return t.substringAfter('\n').removeSuffix("```").trim()
        }

        const val MAX_CHARS = 4000

        /**
         * Format and safety validation of a skill document (both drafted drafts and the full text edited by the user before acceptance go through it); returns null when it passes, otherwise the reason:
         * the name is valid, there is a description, all five sections are present, no more than [MAX_CHARS] characters, every tool name in backticks is in [toolNames], and the sensitive filter does not match;
         * when the skill-script tool ([scriptTool]) is mentioned, the text must contain at least one script path and all of them must be in [runnableScripts] (marked runnable in the catalog).
         */
        fun validate(md: String, toolNames: Set<String>, scriptTool: String?, runnableScripts: Set<String> = emptySet()): String? {
            val (meta, body) = FrontMatter.parse(md)
            val name = meta["name"] as? String
            if (name == null || !PersonalSkills.NAME.matches(name)) return "invalid_name"
            if ((meta["description"] as? String).isNullOrBlank()) return "missing_description"
            val headings = body.lines().filter { it.startsWith("## ") }
            fun has(section: String) = headings.any { it.removePrefix("## ").trim().startsWith(section) }
            if (!SECTIONS.all(::has) && !LEGACY_SECTIONS.all(::has)) return "missing_section"
            if (md.length > MAX_CHARS) return "too_long"
            if (TOOL_MENTION.findAll(md).any { it.groupValues[1] !in toolNames }) return "unknown_tool"
            if (scriptTool != null && TOOL_MENTION.findAll(md).any { it.groupValues[1] == scriptTool }) {
                val scripts = SCRIPT_PATH.findAll(md).map { it.value }.toList()
                fun runnable(path: String) = runnableScripts.any { it == path || it.endsWith("/$path") }
                if (scripts.isEmpty() || !scripts.all(::runnable)) return "unrunnable_script"
            }
            if (SensitiveFilter.hit(md) != null) return "sensitive"
            return null
        }
        /** The five sections of a skill document, in the order the model writes them. */
        val SECTIONS = listOf("Entry", "Steps", "Common pitfalls", "How success is verified", "When to stop and hand off")
        /** Skills stored before the switch to English headings stay readable; validation accepts either set. */
        val LEGACY_SECTIONS = listOf("入口", "步骤", "容易出错的地方", "怎样算办成", "什么时候停下交给用户")
        /** Identifiers that look like tool names, inside backticks. */
        private val TOOL_MENTION = Regex("`([a-z][a-z0-9_]*)`")
        /** A skill script path in the text, such as `scripts/extract_pdf.py` or `skills/documents/scripts/extract_pdf.py`. */
        private val SCRIPT_PATH = Regex("[A-Za-z0-9_./-]*scripts/[A-Za-z0-9_./-]+\\.(?:py|mjs|js|sh)(?![A-Za-z0-9_])")

        val PROMPT: String get() = Prompts.role("skill_draft")
    }
}
