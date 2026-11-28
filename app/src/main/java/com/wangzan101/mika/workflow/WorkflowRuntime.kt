package com.wangzan101.mika.workflow

import com.wangzan101.mika.core.run.PauseFlag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Android adapter only starts an intent; TaskService owns execution and calls complete.
 * The user paused Claire ([paused]): start, continue and confirm are all rejected ([WorkflowProblem.Paused]);
 * a running workflow that reaches the point of dispatching the next step does not dispatch: it stops as "paused" (the claimed step awaiting dispatch is recorded as failed with [PauseFlag.REASON], parking at awaiting confirmation),
 * and after resume the user taps continue; it does not resume running automatically.
 */
class WorkflowRuntime(
    val store: WorkflowStore,
    private val paused: PauseFlag = PauseFlag.Never,
    private val launch: (WorkflowDispatch) -> Unit,
) {
    private data class Completion(val id: String, val successful: Boolean, val result: String, val problem: String)
    private val pending = linkedMapOf<String, Completion>()
    private val _saveError = MutableStateFlow(false)
    val saveError = _saveError.asStateFlow()

    @Synchronized fun start(template: WorkflowTemplate, input: String): String {
        refuseWhilePaused()
        val run = store.start(template.id, template.revision, input)
        advanceOrReport(run.id)
        return run.id
    }
    @Synchronized fun resume(id: String) { refuseWhilePaused(); store.resume(id); advanceOrReport(id) }
    @Synchronized fun resolve(id: String, attemptId: String, result: String?) {
        refuseWhilePaused()
        store.resolve(id, attemptId, result)
        advanceOrReport(id)
    }
    @Synchronized fun complete(id: String, successful: Boolean, result: String, problem: String = "") {
        pending[id] = Completion(id, successful, result, problem)
        retrySave()
    }
    @Synchronized fun retrySave() {
        try {
            store.load()
            pending.values.toList().forEach { value ->
                val runId = store.finish(value.id, value.successful, value.result, value.problem)
                pending.remove(value.id)
                if (runId != null) advance(runId)
            }
            store.data.value.runs.filter { it.phase == WorkflowPhase.Active && it.current == null }.forEach { advance(it.id) }
            _saveError.value = false
        } catch (_: Exception) { _saveError.value = true }
    }
    private fun advanceOrReport(id: String) {
        try { advance(id) } catch (_: WorkflowException) { _saveError.value = true }
    }
    private fun refuseWhilePaused() { if (paused.paused()) throw WorkflowException(WorkflowProblem.Paused) }
    /** The user just paused Claire: workflows that have not dispatched their next step all stop (see [advance]); the step currently running ends as its task is cancelled. */
    @Synchronized fun haltForPause() {
        try {
            store.load()
            store.data.value.runs.filter { it.phase == WorkflowPhase.Active }.forEach { advance(it.id) }
        } catch (_: Exception) { _saveError.value = true }
    }
    private fun advance(id: String) {
        if (paused.paused()) {
            // The claimed step awaiting dispatch is recorded as failed and the workflow parks at awaiting confirmation; if it has not reached the next step it stops directly as "paused". After resume the user taps continue
            val pending = store.pendingDispatch(id)
            if (pending != null) store.finish(pending.attemptId, false, "", PauseFlag.REASON) else store.pause(id)
            return
        }
        val next = store.pendingDispatch(id) ?: store.prepare(id) ?: return
        try { launch(next) }
        catch (_: Exception) {
            pending[next.attemptId] = Completion(next.attemptId, false, "", "start_failed")
            store.finish(next.attemptId, false, "", "start_failed")
            pending.remove(next.attemptId)
        }
    }
}
