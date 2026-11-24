package com.wangzan101.mika.task

import com.wangzan101.mika.core.remote.RunExportLookup
import com.wangzan101.mika.core.remote.RunExportRecord
import com.wangzan101.mika.core.remote.RunExportSource
import com.wangzan101.mika.core.remote.RunRecordExport
import com.wangzan101.mika.core.remote.RemoteTask
import com.wangzan101.mika.record.RunStore

/** How a desktop task id corresponds to its run record. Only tasks registered in [RemoteTaskHub] are recognized. */
interface RemoteRunIndex {
    fun scopeIndex(owner: String): RemoteRunIndex = this
    fun task(id: String): RemoteTask?
    /** Run record id; null when no record has been started yet. In-progress tasks are also resolved from the run that is still alive. */
    fun rememberedRunId(taskId: String): String?
    /** Which desktop task this run record belongs to; null when there is no matching receipt or the conversation has been deleted. */
    fun taskForRun(runId: String): RemoteTask?
}

/**
 * Run record export for the task API: reads the already persisted run.json and events.jsonl in [RunStore], by task id or run record id.
 * Runs started on the phone are not exported. Screenshot files are not read.
 */
class RemoteRunExport(
    private val index: RemoteRunIndex,
    private val store: () -> RunStore,
) : RunExportSource {
    override fun scope(owner: String): RunExportSource = RemoteRunExport(index.scopeIndex(owner), store)

    override fun load(id: String): RunExportLookup {
        val clean = id.trim()
        if (clean.isEmpty()) return RunExportLookup.Missing
        index.task(clean)?.let { return forTask(it) }
        if (!RunRecordExport.RUN_ID.matches(clean)) return RunExportLookup.Missing
        val runs = store()
        val summary = runs.summary(clean) ?: return RunExportLookup.Missing
        val known = index.taskForRun(clean) ?: return RunExportLookup.Missing
        // origin is only run metadata and never an ownership index; whenever the index has no entry, nothing may be exported.
        return RunExportLookup.Found(RunExportRecord(known.id, known.conversation, clean, summary, runs.events(clean)))
    }

    private fun forTask(task: RemoteTask): RunExportLookup {
        val runId = index.rememberedRunId(task.id)
            ?: return RunExportLookup.Found(RunExportRecord(task.id, task.conversation, null, null, emptyList()))
        val runs = store()
        val summary = runs.summary(runId) ?: return RunExportLookup.Gone(task.id, runId)
        return RunExportLookup.Found(RunExportRecord(task.id, task.conversation, runId, summary, runs.events(runId)))
    }
}
