package com.wangzan101.mika.core.run

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.ResourceScope
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable enum class ChildPhase { Running, Completed, Failed, Interrupted, Unknown }

@Serializable data class ChildRecord(val id: String, val parentId: String, val phase: ChildPhase,
    val result: String? = null, val atMs: Long,
    /** The question that was delegated; a single line in the UI that makes clear what this child task is doing. */
    val goal: String = "")

interface ChildRunStore {
    fun save(record: ChildRecord)
    fun get(id: String): ChildRecord?
    fun all(): List<ChildRecord>
}

class MemoryChildRunStore : ChildRunStore {
    private val records = linkedMapOf<String, ChildRecord>()
    @Synchronized override fun save(record: ChildRecord) { records[record.id] = record }
    @Synchronized override fun get(id: String) = records[id]
    @Synchronized override fun all() = records.values.toList()
}

data class ChildResult(val id: RunId, val phase: ChildPhase, val text: String)

fun interface ChildRunHost { suspend fun spawn(spec: RunSpec, format: String?): ChildResult }

/** The child Run saves its result preview before handing it to the parent Run; if the parent Run disappears, an Unknown record is kept and no automatic rerun happens. */
class ChildAgentRunner(
    private val create: (RunSpec, ResourceScope) -> AgentLoop,
    private val store: ChildRunStore,
    private val ownerAlive: (RunId) -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) : ChildRunHost {
    constructor(
        create: (RunSpec) -> AgentLoop,
        store: ChildRunStore,
        ownerAlive: (RunId) -> Boolean = { true },
        clock: () -> Long = System::currentTimeMillis,
    ) : this({ spec, _ -> create(spec) }, store, ownerAlive, clock)

    private val active = HashMap<RunId, MutableSet<AgentLoop>>()

    override suspend fun spawn(spec: RunSpec, format: String?): ChildResult {
        val resources = ResourceScope()
        try {
            return runChild(spec, format, resources)
        } finally {
            resources.close()
        }
    }

    private suspend fun runChild(spec: RunSpec, format: String?, resources: ResourceScope): ChildResult {
        require(spec.parent != null && spec.provenance.origin == com.wangzan101.mika.core.run.RunOrigin.Child)
        val parent = spec.parent
        val agent = create(spec, resources)
        val goal = spec.goal.take(200)
        synchronized(active) { active.getOrPut(parent) { mutableSetOf() } += agent }
        try {
            store.save(ChildRecord(spec.id.value, parent.value, ChildPhase.Running, atMs = clock(), goal = goal))
            val end = agent.run(spec, RunInbox())
            val raw = when (end) {
                is TaskState.Succeeded -> end.answer
                is TaskState.Failed -> end.reason
                TaskState.Cancelled -> INTERRUPTED
                else -> "no result"
            }
            // A failure must not be recorded as completed: spawn_agent's ok is decided by Completed, and Failed used to be written as Completed,
            // so the parent model would summarize a failed child task as a successful result.
            val phase = when (end) {
                is TaskState.Failed -> ChildPhase.Failed
                TaskState.Cancelled -> ChildPhase.Interrupted
                else -> ChildPhase.Completed
            }
            val checked = if (format == "json" && runCatching { Json.parseToJsonElement(raw) }.isFailure)
                "$raw\n(the child run's JSON did not pass validation; the original text is kept)" else raw
            // Only the stored preview is truncated; the original text handed back to the parent Run is truncated by spawn_agent according to this request's budget, with the original length reported.
            val preview = checked.take(MAX_RESULT_CHARS)
            store.save(ChildRecord(spec.id.value, parent.value, phase, preview, clock(), goal = goal))
            if (!ownerAlive(parent)) {
                store.save(ChildRecord(spec.id.value, parent.value, ChildPhase.Unknown, preview, clock(), goal = goal))
                return ChildResult(spec.id, ChildPhase.Unknown, checked)
            }
            return ChildResult(spec.id, phase, checked)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { store.save(ChildRecord(spec.id.value, parent.value, ChildPhase.Interrupted, INTERRUPTED, clock(), goal = goal)) }
            throw e
        } finally {
            synchronized(active) { active[parent]?.remove(agent); if (active[parent]?.isEmpty() == true) active.remove(parent) }
        }
    }

    fun cancelAll(parent: RunId) { synchronized(active) { active[parent]?.toList() }.orEmpty().forEach(AgentLoop::cancel) }

    companion object {
        /** The cap on the child task record's stored preview, not the cap on the result handed back to the parent task. */
        const val MAX_RESULT_CHARS = 4000
        private const val INTERRUPTED = "interrupted"
    }
}
