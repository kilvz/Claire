package com.wangzan101.mika.task

import android.util.Log
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.task.ConfirmationNotifier
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.JudgedOutcome
import com.wangzan101.mika.core.agent.LearnGate
import com.wangzan101.mika.core.agent.OutcomeJudge
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.StopSummary
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.learn.knowledge.AppKnowledgeDrafter
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.learn.knowledge.KnowledgeStore
import com.wangzan101.mika.core.learn.memory.MemoryExtractor
import com.wangzan101.mika.core.learn.memory.MemoryStore
import com.wangzan101.mika.core.learn.memory.TaskMemoryRecorder
import com.wangzan101.mika.core.learn.memory.UserUtterances
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.proactive.NoticeRuns
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.learn.replay.RouteRecorder
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.learn.skills.SkillDrafter
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.knowledge.FileRouteStore
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.settings.MemorySwitches
import com.wangzan101.mika.ui.ToolLabels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID

/** A set of runtime dependencies for learning; assembled at the start of execute and read by the teardown/learning phase. */
internal class LearnKit(
    val switches: MemorySwitches, val memory: MemoryStore?, val utterances: UserUtterances?, val dispatcher: ToolDispatcher,
    val agent: AgentLoop, val catalog: GuideCatalog, val knowledge: KnowledgeStore,
    val model: ChatModel, val light: ChatModel?, val runStore: RunStore?,
    val routesOn: Boolean, val priorTurns: List<Turn>,
)

/**
 * Reporting and learning after teardown (checkpoint summary, stop summary, outcome judging, memory extraction, route recording). The corresponding functions
 * from the original TaskRun were moved here wholesale. The transient TaskRun fields it needs are injected via lambdas.
 */
internal class RunTeardown(
    private val service: TaskService,
    private val conversationId: String,
    private val goal: String,
    private val state: TaskRunState,
    private val kit: () -> LearnKit?,
    private val env: TaskEnvironment,
    private val memoryRuntime: MemoryRuntime,
    private val snapshot: () -> TaskModelSnapshot?,
    private val routeStore: FileRouteStore,
    private val workflowAttemptId: () -> String?,
    private val noticeTainted: () -> Boolean,
    private val provenance: () -> RunProvenance,
) {
    /** Checkpoint summary after a real failure: assembled only from the records, with no further model call, and a terse failure reason is not rewritten into a success. */
    suspend fun checkpointSummary(): String? {
        val k = kit()
        val runId = state._runId.value
        val recorded = withContext(Dispatchers.IO) {
            try {
                runId?.let { id -> k?.runStore?.events(id) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        val events = recorded?.takeIf { StopSummary.worthSaying(it, null) } ?: synchronized(state.activityLog) { state.activityLog.toList() }
        if (!StopSummary.worthSaying(events, null)) return null
        val lang = com.wangzan101.mika.i18n.AppLanguage.resolved(service).tag
        val labels = ToolLabels(knownTools())
        return StopSummary.plain(k?.agent?.taskText ?: goal, events, null, labels::label, {
            ConfirmationNotifier.appLabel(service, it)
        }, language = lang)
    }

    /**
     * After the user stops: write a few lines about what was already done in this run (see [StopSummary]), reading only the run record and
     * touching nothing else. The light model is preferred, falling back to the main model when unset; with no model, on error or on timeout it is assembled straight from the record. Null when there is nothing worth reporting.
     */
    suspend fun stopSummary(draft: String?): String? {
        val k = kit()
        val runId = state._runId.value
        val recorded = withContext(Dispatchers.IO) { runId?.let { id -> runCatching { k?.runStore?.events(id) }.getOrNull() } }
        val events = recorded?.takeIf { e -> e.any { it is RunEvent.Activity } } ?: synchronized(state.activityLog) { state.activityLog.toList() }
        if (!StopSummary.worthSaying(events, draft)) return null
        // 还没完成装配就停下的也用开局快照，绝不在收尾时重读已经改变的设置。
        val (main, light) = if (k != null) k.model to k.light else snapshot()?.let { it.main to it.light } ?: (null to null)
        val lang = com.wangzan101.mika.i18n.AppLanguage.resolved(service).tag
        val labels = ToolLabels(knownTools())
        return StopSummary(light ?: main, main, language = lang).summarize(k?.agent?.taskText ?: goal, events, draft, labels::label) {
            ConfirmationNotifier.appLabel(service, it)
        }
    }

    /**
     * Called after the result has been handed to the user (without slowing that down or changing the delivered answer): the answer is
     * judged against the user's request ([OutcomeJudge], light model preferred) and recorded in the run record and this turn of the conversation; only runs that succeeded ([LearnGate]) record a route, and the replayed route is marked successful.
     * Abandoned, failed and cancelled runs are not judged (the outcome is already clear) and return null; a failed judgement is [RunOutcome.Unknown].
     */
    suspend fun settleOutcome(end: TaskState, conversations: ConversationStore): JudgedOutcome? {
        val k = kit() ?: return null
        if (end !is TaskState.Succeeded) return null
        val judged = OutcomeJudge(k.light ?: k.model, k.model).judge(k.agent.taskText ?: goal, end.answer, k.priorTurns)
        val runId = state._runId.value
        if (runId != null) withContext(Dispatchers.IO) {
            k.runStore?.append(runId, judged.event(System.currentTimeMillis()))
            if (BuildConfig.DEBUG) k.runStore?.mirrorToExternal(runId)
            runCatching { conversations.updateTurn(conversationId, runId) { it.copy(result = judged.outcome.wire) } }
                .onFailure { Log.w(TAG, "could not save run outcome", it) }
        }
        k.agent.noteOutcome(judged.outcome)
        // Only runs that succeeded are recorded as routes; taken from the persisted run events
        if (workflowAttemptId() == null && runId != null && LearnGate.shouldSaveRoute(k.routesOn, end, judged.outcome, runId, goal)) k.runStore?.let { s ->
            saveRoute(goal, withContext(Dispatchers.IO) { s.events(runId) }, k.dispatcher.index)
        }
        return judged
    }

    /**
     * Called after the result notification has been sent: record the outcome of the personal skills that were used, extract memories and draft
     * skills, and attach the notices to this turn's result card and save them into the conversation. It runs in a process-level scope, so it is not interrupted when the service exits.
     */
    suspend fun learnAfter(end: TaskState, outcome: RunOutcome?, conversations: ConversationStore) {
        rememberTask()
        val k = kit() ?: return
        val runId = state._runId.value
        val learning = RunLearning(
            k.switches, k.memory, memoryRuntime.skills(), k.catalog,
            extractor = k.memory?.let { MemoryExtractor(k.light ?: k.model) },
            drafter = SkillDrafter(k.model),
            appDrafter = AppKnowledgeDrafter(k.light ?: k.model),
            appDrafts = memoryRuntime.appKnowledge(),
            screenMaps = memoryRuntime.screenMaps(),
            ids = { UUID.randomUUID().toString() },
            autoApprove = env.evalHooks.autoApproveSkillDrafts,
            yieldToForeground = { while (service.hasActiveRuns()) delay(500) },
        )
        val notes = learning.learn(RunAftermath(
            conversationId, runId, k.agent.taskText ?: goal, end, k.utterances.takeIf { k.memory != null }?.all.orEmpty(), k.dispatcher.savedChanges, k.agent.usedMemoryIds,
            k.dispatcher.definitions().map { it.name }.toSet(), k.dispatcher.index,
            events = { withContext(Dispatchers.IO) { runId?.let { k.runStore?.events(it) }.orEmpty() } },
            origin = provenance().origin,
            knowledge = k.knowledge,
            outcome = outcome ?: RunOutcome.Unknown,
            noticeTainted = noticeTainted() || k.agent.carriedObserved,
        ))
        if (runId == null || notes.memoryNotes.isEmpty() && notes.usedMemory.isEmpty() && notes.skillNotes.isEmpty()) return
        memoryRuntime.publish(notes)
        withContext(Dispatchers.IO) {
            runCatching {
                conversations.updateTurn(conversationId, runId) { it.copy(memoryNotes = notes.memoryNotes, usedMemory = notes.usedMemory, skillNotes = notes.skillNotes) }
            }.onFailure { Log.w(TAG, "could not save memory notes", it) }
        }
    }

    /**
     * After the task ends: record a cross-conversation summary from this run's events (it does not slow delivery, and a failure does not affect the task outcome).
     */
    private suspend fun rememberTask() {
        val runId = state._runId.value ?: return
        val settings = memoryRuntime.settings()
        if (!settings.switches.value.taskMemoryEnabled) return
        val events = withContext(Dispatchers.IO) { runCatching { kit()?.runStore?.events(runId) }.getOrNull() } ?: return
        val labels = ToolLabels(knownTools())
        val recorded = TaskMemoryRecorder.record(goal, events, System.currentTimeMillis(), UUID.randomUUID().toString(), labels::label) {
            ConfirmationNotifier.appLabel(service, it)
        } ?: return
        val task = NoticeRuns.summary(recorded, events, noticeTurnInContext = noticeTainted())
        withContext(Dispatchers.IO) {
            if (!settings.switches.value.taskMemoryEnabled) return@withContext
            runCatching { memoryRuntime.taskMemory().add(task) }
                .onFailure { Log.w(TAG, "task memory not saved", it) }
        }
    }

    /** Record a run that succeeded as a route; failing to record it (it shouldn't be recorded, or the write fails) does not affect the task outcome. */
    private suspend fun saveRoute(goal: String, events: List<RunEvent>, tools: ToolIndex) = withContext(Dispatchers.IO) {
        runCatching {
            val route = RouteRecorder.record(goal, events, System.currentTimeMillis(), tools) ?: return@runCatching
            val versions = route.packages.mapNotNull { p ->
                runCatching { service.packageManager.getPackageInfo(p, 0).versionName }.getOrNull()?.let { p to it }
            }.toMap()
            routeStore.putReplacing(route.copy(appVersions = versions))
        }.onFailure { Log.w(TAG, "route not saved", it) }
    }

    private companion object {
        const val TAG = "MikaTask"
    }
}
