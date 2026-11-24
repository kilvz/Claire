package com.wangzan101.mika.core.learn.knowledge

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.LearnGate
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.learn.memory.SensitiveFilter
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.learn.skills.ActionDigest
import com.wangzan101.mika.core.learn.skills.ActionLine
import com.wangzan101.mika.core.learn.skills.Eligibility
import com.wangzan101.mika.core.learn.skills.SkillDrafter
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.Verb
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
enum class AppKnowledgeStage { Draft, Trial, Verified, Archived }

/**
 * One version of a self-learned knowledge supplement for an app (a short list of facts only, appended after that app's description).
 * [lesson]: learned from a run that failed or stumbled; [revises]: the supplement in use for this app when it was drafted, and this one is its revision (null for the first supplement).
 */
@Serializable
data class AppKnowledgeDraft(val id: String, val appName: String, val packageName: String,
    val markdown: String, val sourceRun: String, val stage: AppKnowledgeStage, val createdAt: Long,
    val trialOutcomes: List<Boolean> = emptyList(), val lesson: Boolean = false, val revises: String? = null,
    /** The app name shown to the user (e.g. "Settings"); older records do not have it, and the UI falls back to [appName]. */
    val display: String = "")

/** A self-learned supplement appended after the app description when injecting; when [trial] is true it is still on trial and no completed task has used it yet. */
data class LearnedNote(val text: String, val trial: Boolean)


/**
 * Self-learned app knowledge. Each app has at most one pending one ([AppKnowledgeStage.Draft]), and only one is injected ([inUse]).
 * An accepted revision temporarily replaces the one it revises during the trial (that one is neither injected nor deleted); the old one is archived only once the revision becomes verified, and if the revision is archived the old one takes over again.
 */
interface AppKnowledgeDraftStore {
    fun all(): List<AppKnowledgeDraft>
    /** When the same app already has a pending one, or [AppKnowledgeDraft.revises] is not the one injected now (a stale revision), it is not accepted and false is returned. */
    fun putPending(draft: AppKnowledgeDraft): Boolean
    /**
     * If the revision is not the one injected now (it is out of date), it is not accepted: it is archived on the spot and false is returned. After accepting, archiving or a trial changes the one in use,
     * stale pending revisions for the same app are archived along with it, so they do not block later drafts.
     */
    fun approve(id: String): Boolean
    fun reject(id: String): Boolean
    /** A run that used the trial copy has ended: [good] says whether it counts as done ([LearnGate]); per [AppKnowledgeTrial.next] it becomes verified, stays, or is archived. */
    fun recordTrial(appName: String, good: Boolean): AppKnowledgeDraft?
    fun notes(): Map<String, LearnedNote>

    /** The supplement currently injected for this app. */
    fun inUse(appName: String): AppKnowledgeDraft? = AppKnowledgeTrial.inUse(all(), appName)
}

/** How trial outcomes change the stage; same as personal skills: a smooth success becomes verified, one learned from a lesson needs two bad results in a row to be archived, and anything else is archived after a single bad one. */
object AppKnowledgeTrial {
    const val LESSON_BAD_STREAK = 2

    fun next(d: AppKnowledgeDraft, good: Boolean): AppKnowledgeDraft {
        val outcomes = d.trialOutcomes + good
        val stage = when {
            good -> AppKnowledgeStage.Verified
            !d.lesson -> AppKnowledgeStage.Archived
            outcomes.size >= LESSON_BAD_STREAK && outcomes.takeLast(LESSON_BAD_STREAK).none { it } -> AppKnowledgeStage.Archived
            else -> AppKnowledgeStage.Trial
        }
        return d.copy(stage = stage, trialOutcomes = outcomes)
    }

    /** The one injected for [appName] among [rows]: on trial or verified, and not temporarily shadowed by a revision that is on trial. */
    fun inUse(rows: List<AppKnowledgeDraft>, appName: String): AppKnowledgeDraft? {
        val app = rows.filter { it.appName == appName }
        val shadowed = app.filter { it.stage == AppKnowledgeStage.Trial }.mapNotNull { it.revises }.toSet()
        return app.firstOrNull { (it.stage == AppKnowledgeStage.Trial || it.stage == AppKnowledgeStage.Verified) && it.id !in shadowed }
    }
}

/** One run the drafter looks at: [end] and [outcome] are its end state and the outcome judged after the answer was delivered. */
data class AppRunInput(
    val runId: String, val goal: String, val end: TaskState, val outcome: RunOutcome, val events: List<RunEvent>, val tools: ToolIndex,
)

/**
 * Which kind a run's passage through an app is: [Done] went smoothly; [Bumpy] got done but hit errors, the UI did not change, or it went back and forth on the way;
 * [NotDone] did not get done or only partly got done. The latter two are the "take fewer detours next time" lessons. [wire] is the outcome written to the model in the request.
 */
enum class AppRunKind(val wire: String) { Done("done"), Bumpy("bumpy"), NotDone("not_done") }

/**
 * Turns a run's passage through an app into a revision of that app's knowledge supplement: it asks a model once (with no tools), handing it the app's built-in description,
 * the supplement in use, the outcome and a condensed action list (with error codes and whether the UI changed), and asks for the full revised supplement back; when there is nothing worth adding (most often) it produces nothing.
 *
 * The returned supplement is accepted as is; a whole supplement that writes down things like passwords or verification codes is not accepted.
 */
class AppKnowledgeDrafter(private val model: ChatModel) {
    suspend fun draft(run: AppRunInput, app: KnowledgeEntry, builtIn: String?, current: AppKnowledgeDraft?, id: String, atMs: Long): AppKnowledgeDraft? {
        if (app.surface != Surface.Android || app.packages.isEmpty()) return null
        if (Eligibility.interrupted(run.events) != null || run.events.any { it is RunEvent.Replay && it.matched }) return null
        val lines = ActionDigest.of(run.events, run.tools).filter { it.where in app.packages }
        val kind = kind(run.end, run.outcome, lines, run.tools) ?: return null
        // With an already verified supplement, one smooth success mostly proves nothing new (the model would just reply NONE): do not ask
        if (kind == AppRunKind.Done && current?.stage == AppKnowledgeStage.Verified) return null
        val said = when (val end = run.end) {
            is TaskState.Succeeded -> end.answer
            is TaskState.Failed -> end.reason
            else -> return null
        }
        val existing = current?.let { facts(it.markdown) }
        val request = Prompts.fill(INPUT, mapOf(
            "app" to app.display + " (" + app.packages.joinToString(", ") + ")",
            "outcome" to kind.wire,
            "goal" to run.goal,
            "said" to said,
            "built_in" to builtIn.orEmpty(),
            "current" to existing.orEmpty(),
            "actions" to ActionDigest.render(lines),
        ))
        val reply = try {
            model.complete(PROMPT, listOf(Message(Role.User, listOf(Part.Text(request)), Author.Mika)), emptyList())
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return null }
        val raw = SkillDrafter.stripFence(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
        if (raw.isEmpty() || raw.equals(NOTHING, ignoreCase = true)) return null
        if (!valid(raw, run.tools)) return null
        val facts = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        if (facts == existing) return null
        return AppKnowledgeDraft(id, app.name, app.packages.first(), facts, run.runId, AppKnowledgeStage.Draft, atMs,
            lesson = kind != AppRunKind.Done, revises = current?.id, display = app.display)
    }

    companion object {
        /** On a smooth success, at least this many effective actions in the app are worth writing down (the same threshold as personal skill drafting). */
        const val MIN_DONE_ACTIONS = Eligibility.MIN_ACTIONS
        /** A lesson needs at least this many actions in the app: runs that stop after one or two steps mostly get stuck at the start (not signed in, cannot open) and say nothing about the UI. */
        const val MIN_LESSON_ACTIONS = 3
        /** This many actions that errored or left the UI unchanged counts as a stumble: one miss happens often, two mean the entry point or the UI differs from what was expected. */
        const val BUMPY_MISSES = 2
        /** This many back navigations counts as a detour: backing out once after viewing details is common, twice mostly means the wrong entry point was taken and then backed out of. */
        const val BUMPY_BACKS = 2
        /** The supplement has at most this many lines and this many characters: it is appended after the description when injecting and should not be longer than the description itself. */
        const val MAX_FACTS = 12
        const val MAX_CHARS = 1500
        /** The single word the model replies with when there is nothing worth adding. */
        const val NOTHING = "NONE"

        val PROMPT: String get() = Prompts.role("app_knowledge")
        private val INPUT: String get() = Prompts.role("app_knowledge_input")

        private val TOOL_MENTION = Regex("`([a-z][a-z0-9_]*)`")
        /**
         * The fact lines of a supplement: drop the header, headings and blank lines, and prefix "- " to lines that do not start with it. Older versions drafted the whole APP.md,
         * so this also still allows revising onward and comparing which lines changed.
         */
        fun facts(markdown: String): String = FrontMatter.parse(markdown).second.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .joinToString("\n") { if (it.startsWith("- ")) it else "- " + it.removePrefix("-").removePrefix("*").trim() }

        /** Which kind this run's passage through [app] is; null when not worth learning from (cancelled, not judged done, failed verification, too few actions). */
        fun kind(end: TaskState, outcome: RunOutcome, lines: List<ActionLine>, tools: ToolIndex): AppRunKind? {
            val misses = lines.count { it.error != null || it.changed == false }
            val backs = lines.count { tools.verb(it.tool) == Verb.Back }
            val bumpy = lines.size >= MIN_LESSON_ACTIONS && (misses >= BUMPY_MISSES || backs >= BUMPY_BACKS)
            val notDone = (end is TaskState.Succeeded && (outcome == RunOutcome.Partial || outcome == RunOutcome.NotPossible)) ||
                (end is TaskState.Failed && (end.kind == FailKind.GaveUp || end.kind == FailKind.StepLimit || end.kind == FailKind.TokenLimit))
            return when {
                LearnGate.done(end, outcome) -> when {
                    bumpy -> AppRunKind.Bumpy
                    lines.count { it.error == null } >= MIN_DONE_ACTIONS -> AppRunKind.Done
                    else -> null
                }
                notDone && lines.size >= MIN_LESSON_ACTIONS -> AppRunKind.NotDone
                else -> null
            }
        }

        /** Among the apps this run touched on the phone, the ones that have a knowledge entry (in order of appearance, once each). Apps without an entry have nowhere to attach a supplement, so they are not learned from. */
        fun touched(events: List<RunEvent>, tools: ToolIndex, knowledge: KnowledgeStore): List<KnowledgeEntry> =
            ActionDigest.of(events, tools).mapNotNull { it.where }.distinct()
                .mapNotNull { knowledge.byPackage(it)?.takeIf { e -> e.surface == Surface.Android } }.distinctBy { it.name }

        /** Only fact lines starting with "- ", within the line and character limits, mentioning no unregistered tool names, and not hit by the sensitive filter. */
        private fun valid(facts: String, tools: ToolIndex): Boolean {
            val lines = facts.lines().filter { it.isNotBlank() }
            return lines.isNotEmpty() && lines.size <= MAX_FACTS && facts.length <= MAX_CHARS &&
                lines.all { it.startsWith("- ") && it.length > 4 } &&
                TOOL_MENTION.findAll(facts).all { it.groupValues[1] in tools.names } &&
                SensitiveFilter.hit(facts) == null
        }
    }
}
