package com.wangzan101.mika.task

import com.wangzan101.mika.core.general.NOTICES_READ
import com.wangzan101.mika.core.proactive.ObservedContext
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.agent.LearnGate
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.learn.knowledge.FrontMatter
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.learn.knowledge.KnowledgeStore
import com.wangzan101.mika.core.learn.knowledge.AppKnowledgeDrafter
import com.wangzan101.mika.core.learn.knowledge.AppKnowledgeDraftStore
import com.wangzan101.mika.core.learn.knowledge.AppKnowledgeStage
import com.wangzan101.mika.core.learn.knowledge.AppRunInput
import com.wangzan101.mika.core.learn.memory.Change
import com.wangzan101.mika.core.learn.memory.MemoryExtractor
import com.wangzan101.mika.core.learn.memory.MemorySource
import com.wangzan101.mika.core.learn.memory.MemoryStore
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.screen.observation.PlaceKind
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.learn.skills.DraftInput
import com.wangzan101.mika.core.learn.skills.DraftResult
import com.wangzan101.mika.core.learn.skills.Eligibility
import com.wangzan101.mika.core.learn.skills.PersonalSkills
import com.wangzan101.mika.core.learn.skills.SkillDrafter
import com.wangzan101.mika.core.learn.skills.SkillFollow
import com.wangzan101.mika.core.learn.skills.SkillNote
import com.wangzan101.mika.core.learn.skills.SkillOutcomes
import com.wangzan101.mika.core.learn.skills.SkillResult
import com.wangzan101.mika.core.learn.skills.SkillStatus
import com.wangzan101.mika.core.screen.map.ScreenMapBuilder
import com.wangzan101.mika.core.screen.map.ScreenMapGrower
import com.wangzan101.mika.core.screen.map.ScreenMapStore
import com.wangzan101.mika.memory.RunNotes
import com.wangzan101.mika.settings.MemorySwitches
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wangzan101.mika.core.tool.ToolIndex

/** All the material needed for learning after a run ends; handed over by [TaskRun] when the task wraps up. */
data class RunAftermath(
    val conversationId: String,
    val runId: String?,
    val goal: String,
    val end: TaskState,
    /** What the user said this time; empty when memory is off. */
    val utterances: List<String>,
    /** Changes written by memory_save / memory_edit during the task. */
    val saved: List<Change>,
    val usedMemory: List<String>,
    val toolNames: Set<String>,
    /** Resolves the tool actions that were performed, by name. */
    val tools: ToolIndex,
    /** This run's events (read from the run record); empty when they cannot be read. */
    val events: suspend () -> List<RunEvent>,
    val origin: RunOrigin = RunOrigin.Ui,
    val knowledge: KnowledgeStore? = null,
    /** The outcome judged after the answer is handed over; learn only from what succeeded ([LearnGate]). Not judged successful, or never judged, is [RunOutcome.Unknown]. */
    val outcome: RunOutcome = RunOutcome.Unknown,
    /**
     * There were notice reminders in this conversation ([com.wangzan101.mika.core.agent.NoticeTurn]), or this task started with the segment gathered from notifications
     * ([com.wangzan101.mika.core.proactive.ObservedContext]): what the user says here most likely responds to a notice someone else wrote
     * ("OK, do what he said"), so, like tasks that read web pages, memory is not extracted from it automatically. What the user explicitly asks to remember (memory_save) is unaffected.
     */
    val noticeTainted: Boolean = false,
)

/**
 * After a task ends: record the results of the personal skills used and of the app knowledge under trial (which may auto-suspend, promote to official, or archive), extract memory from the user's words,
 * and draft skills from tasks that succeeded; for apps that were handled, draft revisions to the knowledge supplement from runs that succeeded, failed or stumbled (see [AppKnowledgeDrafter]).
 * Success is judged by the outcome decided after the answer is handed over ([RunAftermath.outcome]), not by "an answer was handed over", and does not require the final review to have run ([LearnGate]).
 * Everything happens in a background coroutine after the result notification is sent ([launchAfter]); an error in any step does not affect the task's ending.
 *
 * One more case where nothing is drafted: this run took a replayed route ([RunEvent.Replay] has matching steps) — the route is already remembered,
 * and drafting again would only copy the same route. Runs that only executed a few batched steps (do_steps) are drafted as usual; those were planned by the model itself.
 */
class RunLearning(
    private val switches: MemorySwitches,
    /** Null when the master memory switch is off. */
    private val store: MemoryStore?,
    private val skills: PersonalSkills,
    private val catalog: GuideCatalog,
    private val extractor: MemoryExtractor?,
    private val drafter: SkillDrafter?,
    private val ids: () -> String,
    /** Evaluation hook: accept drafts as soon as they are made (training rounds). */
    private val autoApprove: Boolean = false,
    private val yieldToForeground: suspend () -> Unit = {},
    private val appDrafter: AppKnowledgeDrafter? = null,
    private val appDrafts: AppKnowledgeDraftStore? = null,
    /** Screen-map store of each app; when null, no graphs are grown during wrap-up. */
    private val screenMaps: ScreenMapStore? = null,
) {
    suspend fun learn(a: RunAftermath): RunNotes {
        yieldToForeground()
        val events = guarded(emptyList()) { a.events() }
        growGraphs(a, events)
        val used = events.filterIsInstance<RunEvent.SkillUsed>()
        // Skills that were actually followed (merely reading them does not count): both recording results and deciding whether to draft depend on it
        val followed = SkillFollow.followed(events, used, a.tools, catalog::skillPlaces)
        val skillNotes = recordOutcomes(followed, used, a.end, a.outcome).toMutableList()
        // App knowledge under trial and personal skills use the same result: cancelled and unsuccessful ones are not recorded
        val trial = SkillOutcomes.resultOf(a.end, a.outcome)?.takeIf { it != SkillResult.unreviewed }
        val trialNames = events.filterIsInstance<RunEvent.State>().filter { it.name == "knowledge_trial_used" }.map { it.detail }.distinct()
        if (trial != null) trialNames.forEach { name -> guarded(null) { appDrafts?.recordTrial(name, trial == SkillResult.verified) } }

        val changes = a.saved.toMutableList()
        val store = store
        val extractor = extractor
        // Without a run id there is nowhere to attach the note, so the user cannot see or undo it: do not extract
        val learnable = a.origin == RunOrigin.Ui && a.end is TaskState.Succeeded
        val readWeb = events.filterIsInstance<RunEvent.Observed>().any { it.place?.kind in setOf(PlaceKind.Site, PlaceKind.Page) }
        // Tasks that read notices are like tasks that read web pages: what the user says this time mostly responds to something someone else wrote, so do not extract automatically
        val readNotices = events.filterIsInstance<RunEvent.Activity>().any { it.label == NOTICES_READ }
            // Same for tasks that started with the segment gathered from notifications (the run record has that entry; when the record cannot be read, a.noticeTainted covers it)
            || events.filterIsInstance<RunEvent.State>().any { it.name == ObservedContext.RUN_STATE }
        if (learnable && !readWeb && !readNotices && !a.noticeTainted && a.runId != null && store != null && extractor != null && switches.autoRemember && a.utterances.isNotEmpty()) {
            val now = store.clock()
            changes += guarded(emptyList()) {
                yieldToForeground()
                extractor.extract(a.utterances, MemoryExtractor.related(a.utterances, store.items.value), store) { q ->
                    MemorySource("user_said", q, a.conversationId, now, a.origin.name)
                }
            }
        }

        // Only learn the approach from what succeeded: if the answer says honestly that it did not succeed, only partly succeeded, or it was never judged, nothing is drafted
        val done = learnable && LearnGate.done(a.end, a.outcome)
        if (done) draft(a, events, followed.toList())?.let(skillNotes::add)
        // App knowledge is also learned from failed and stumbling runs (whether it is worth it is decided by AppKnowledgeDrafter from what happened)
        if (a.origin == RunOrigin.Ui) skillNotes += draftApps(a, events)
        return RunNotes(a.conversationId, a.runId.orEmpty(), MemoryExtractor.notes(changes), a.usedMemory, skillNotes)
    }

    /**
     * Each skill is read and rewritten on its own (results are not lost even when other tasks record at the same time); an error in one does not affect the others.
     * The result is recorded only on the skills that were followed this time ([followed], see [SkillFollow]): merely reading one records nothing;
     * when more than one was followed the result cannot be attributed to any of them, so none is recorded, lest an unused skill end up recorded as unsuccessful and get suspended (#569).
     */
    private suspend fun recordOutcomes(followed: Set<String>, used: List<RunEvent.SkillUsed>, end: TaskState, outcome: RunOutcome): List<SkillNote> {
        val result = SkillOutcomes.resultOf(end, outcome) ?: return emptyList()
        if (followed.size != 1) return emptyList()
        return used.filter { it.name in followed }.groupBy { it.name }.mapNotNull { (name, uses) ->
            val next = guarded(null) {
                skills.update(name) { s -> if (s.status == SkillStatus.active || s.status == SkillStatus.trial) SkillOutcomes.record(s, result, followed = true) else null }
            } ?: return@mapNotNull null
            when (next.status) {
                SkillStatus.suspended -> SkillNote(tr(R.string.skill_suspended, name), suspended = name)
                SkillStatus.active -> if (uses.isNotEmpty() && result == SkillResult.verified) SkillNote(tr(R.string.skill_verified, name)) else null
                else -> null
            }
        }
    }

    private suspend fun draft(a: RunAftermath, events: List<RunEvent>, used: List<String>): SkillNote? {
        val d = drafter ?: return null
        val runId = a.runId ?: return null
        if (!switches.learnSkills) return null
        if (events.any { it is RunEvent.Replay && it.matched }) return null
        // Something done by following a personal skill only has its result recorded and is not drafted again; merely reading a skill without following it does not block drafting (this time it was accomplished on its own)
        val followed = used.firstOrNull().takeIf { SkillOutcomes.resultOf(a.end, a.outcome) == SkillResult.verified }
        val done = a.end as? TaskState.Succeeded
        if (Eligibility.check(events, followed, a.tools, done = LearnGate.done(a.end, a.outcome)) != null) return null
        val r = guarded(null) {
            yieldToForeground()
            d.draft(DraftInput(runId, a.goal, done?.answer.orEmpty(), done?.sources.orEmpty(), events, a.toolNames, a.tools), skills, catalog)
        }
        val draft = r as? DraftResult.Draft ?: return null
        // It is an update to a pending draft: write it back into that draft instead of starting a new one
        val id = draft.replacesPending ?: ids()
        guarded(Unit) { skills.putPending(id, draft.skill) }
        if (autoApprove) {
            guarded(null) { skills.approve(id) }
            return null
        }
        val description = FrontMatter.parse(draft.skill.markdown).first["description"] as? String ?: draft.skill.name
        return SkillNote(tr(R.string.skill_learned, description), draftId = id)
    }

    /** Judge each app that was handled this time and has a knowledge entry, once each; if an app already has a pending revision, do not draft again. */
    private suspend fun draftApps(a: RunAftermath, events: List<RunEvent>): List<SkillNote> {
        val drafter = appDrafter ?: return emptyList()
        val drafts = appDrafts ?: return emptyList()
        val knowledge = a.knowledge ?: return emptyList()
        val runId = a.runId ?: return emptyList()
        if (!switches.learnSkills) return emptyList()
        val run = AppRunInput(runId, a.goal, a.end, a.outcome, events, a.tools)
        return AppKnowledgeDrafter.touched(events, a.tools, knowledge).mapNotNull { app ->
            if (guarded(true) { drafts.all().any { it.appName == app.name && it.stage == AppKnowledgeStage.Draft } }) return@mapNotNull null
            val current = guarded(null) { drafts.inUse(app.name) }
            val draft = guarded(null) {
                yieldToForeground()
                drafter.draft(run, app, knowledge.builtIn(app.name), current, ids(), events.lastOrNull()?.atMs ?: 0L)
            } ?: return@mapNotNull null
            if (!guarded(false) { drafts.putPending(draft) }) return@mapNotNull null
            SkillNote(tr(if (draft.revises != null) R.string.skill_app_revised else R.string.skill_app_draft, app.display))
        }
    }

    /** An error in any learning step does not affect the other steps; cancellation is still propagated. */
    private suspend fun <T> guarded(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fallback
    }

    /** Wrap-up graph growth: replay this run's events to obtain screen/edge fragments and merge them into the stored graphs by screen id. A failure is only counted and does not affect the task's ending. */
    private suspend fun growGraphs(a: RunAftermath, events: List<RunEvent>) {
        val store = screenMaps ?: return
        if (a.origin != RunOrigin.Ui) return
        val fragments = ScreenMapBuilder().apply(a.runId ?: "", events)
        if (fragments.isEmpty()) return
        guarded(Unit) {
            withContext(Dispatchers.IO) { ScreenMapGrower.grow(store, fragments) }
        }
    }

    companion object {
        /** Send the result notification first, then learn in the background: the user sees the result first, and learning a little slower does not matter. */
        suspend fun launchAfter(scope: CoroutineScope, notify: suspend () -> Unit, learn: suspend () -> Unit): Job {
            notify()
            return scope.launch { learn() }
        }
    }
}
