package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.ApprovalSettings
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.action.execution
import com.wangzan101.mika.core.safety.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.MemoryRunStore
import com.wangzan101.mika.core.run.RunEventBus
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunPhase
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.run.RunStore
import com.wangzan101.mika.core.run.StoredRun
import com.wangzan101.mika.core.tool.ToolDispatcher
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The transport layer stub implements this interface; external connections only get the ID and the event stream. */
fun interface McpTaskHost {
    fun submit(spec: RunSpec): McpTaskLink
}

class McpTaskLink(val id: RunId, val events: RunEventBus, val cancel: () -> Unit = {})

/** A model-free session-style Run. The catalog is frozen at the start, and every tool call goes through ActionExecutor. */
class McpToolSession internal constructor(
    val spec: RunSpec,
    private val tools: ToolDispatcher,
    private val store: RunStore,
    approvals: ApprovalSettings,
    private val clock: () -> Long,
) {
    val events = RunEventBus()
    private val lock = Mutex()
    private var step = 0
    private var closed = false
    private val offered = tools.definitions().map { it.name }.toSet()
    private val executor = ActionExecutor(tools, OperationGuards(), null, gate = {},
        recorder = ActionRecorder(events, clock), offScreen = tools.offScreenGuard(), approvals = approvals,
        provenance = spec.provenance, autonomous = true)

    init {
        store.save(StoredRun(spec.id, spec.threadId, spec.goal, RunPhase.Running, roundInProgress = true))
        events.event(RunEvent.State(clock(), "mcp_session_started", spec.id.value))
    }

    fun tools() = tools.definitions()

    suspend fun call(name: String, argumentsJson: String): JsonObject = lock.withLock {
        if (closed) return@withLock error("session_closed")
        if (name !in offered) return@withLock error("unknown_tool")
        step++
        val outcome = executor.run(Part.ToolCall("mcp_$step", name, argumentsJson), Origin.McpTool(step))
        val content = outcome.execution().content
        events.event(RunEvent.ToolResult(clock(), step, name, content.toString(), null, null, 0, callId = "mcp_$step"))
        content
    }

    suspend fun close() = lock.withLock {
        if (closed) return@withLock
        closed = true
        store.get(spec.id)?.let { store.save(it.copy(phase = RunPhase.Ended, roundInProgress = false)) }
        events.event(RunEvent.State(clock(), "mcp_session_ended", spec.id.value))
    }

    private fun error(code: String) = buildJsonObject { put("ok", false); put("error", code) }
}

/** Core entry point of the local transport stub; a real socket/authorization layer only needs to map onto these three operations. */
class McpServer(
    private val assemble: (RunSpec) -> ToolDispatcher,
    private val tasks: McpTaskHost,
    private val store: RunStore = MemoryRunStore(),
    private val approvals: ApprovalSettings = ApprovalSettings.ASK,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun openToolSession(threadId: String, attendance: Attendance = Attendance.Unattended): McpToolSession {
        val spec = RunSpec(RunId(UUID.randomUUID().toString()), "MCP tool session", threadId,
            provenance = RunProvenance(RunOrigin.Mcp, attendance))
        return McpToolSession(spec, assemble(spec), store, approvals, clock)
    }

    fun submitTask(goal: String, threadId: String, attendance: Attendance = Attendance.Unattended): McpTaskLink {
        val spec = RunSpec(RunId(UUID.randomUUID().toString()), goal, threadId,
            provenance = RunProvenance(RunOrigin.Mcp, attendance))
        return tasks.submit(spec)
    }
}
