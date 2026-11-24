package com.wangzan101.mika.core.action

/** How substep frames are saved: left to the caller (a model single step saves them in its own order), saved on the spot only when the UI changed, or not saved. */
enum class Frames { Caller, WhenChanged, None }

/** Who the blocked explanation is written for: the model (Chinese explanation, confirmation flow), the page script (the error the script receives), or the caller explaining by code. */
enum class RefusalStyle { Model, Script, Code }

/**
 * The places where one origin intentionally differs from the others, written only here; the execution order itself is identical for every origin.
 * [source] is the source in the run record.
 */
data class OriginPolicy(
    val source: String,
    /** Can let a payment through with the user's approval; other origins stop on a payment and hand it back to the model, never assuming the user approved. */
    val usesGrant: Boolean,
    /** Judges irreversibility even without a confirmation gate: a substep that hits stops, without relying on the gate. */
    val judgeWithoutGate: Boolean,
    /** Which element is tapped cannot be determined: true means not executed; false means allowed but with a trace left. */
    val refuseUnresolved: Boolean,
    /** Do not execute as soon as a guard flags anything (replay follows the route, so any anomaly is handed back to the model). */
    val stopOnGuardWarning: Boolean,
    /** The caller already waited at the locked-screen gate before reading the screen (replay must verify against the screen from after that wait), so do not wait again before executing. */
    val gatedByCaller: Boolean,
    /** Reads are counted only within the call they belong to; at most a reminder, never stopping the task (polling reads inside a page script). */
    val isolatedReads: Boolean,
    /** When non-null: once an action starts it is completed and recorded (not cancellable), waiting at most this long. */
    val actionCapMs: Long?,
    val frames: Frames,
    /** The target element and before/after anchors the route needs (when replay is enabled). */
    val routeTargets: Boolean,
    /** A call blocked by the confirmation gate is itself the result handed to the model, so it is recorded in the run record as usual. */
    val recordsRefusal: Boolean,
    val refusals: RefusalStyle,
)

/** Where an action comes from; [step] is the loop turn it belongs to. */
sealed interface Origin {
    val step: Int
    val policy: OriginPolicy

    /** A single tool call by the model. */
    data class Model(override val step: Int) : Origin {
        override val policy get() = MODEL
    }

    /** One step inside do_steps. */
    data class Batch(override val step: Int) : Origin {
        override val policy get() = BATCH
    }

    /** A `mika.*` action inside web_script. */
    data class Script(override val step: Int) : Origin {
        override val policy get() = SCRIPT
    }

    /** One step of experience replay (no model call). */
    data object Replay : Origin {
        override val step = 0
        override val policy get() = REPLAY
    }

    /** A low-level tool call from the MCP server; there is no model, but it still has to pass through the same execution entry point. */
    data class McpTool(override val step: Int) : Origin {
        override val policy get() = MCP_TOOL
    }

    companion object {
        /** The cap for one page-script action: loading a URL waits at most 20 seconds, with headroom left for the page to settle. */
        const val SCRIPT_ACTION_CAP_MS = 40_000L

        // Only a model single step can let a payment through with user approval; other origins stop on a payment and hand it back
        private val MODEL = OriginPolicy(
            "model", usesGrant = true, judgeWithoutGate = false, refuseUnresolved = false, stopOnGuardWarning = false,
            gatedByCaller = false, isolatedReads = false, actionCapMs = null, frames = Frames.Caller, routeTargets = true,
            recordsRefusal = true, refusals = RefusalStyle.Model,
        )
        private val BATCH = OriginPolicy(
            "batch", usesGrant = false, judgeWithoutGate = true, refuseUnresolved = true, stopOnGuardWarning = false,
            gatedByCaller = false, isolatedReads = false, actionCapMs = null, frames = Frames.WhenChanged, routeTargets = true,
            recordsRefusal = false, refusals = RefusalStyle.Code,
        )
        // A script is a program on a web page and cannot be replayed as a step of a route
        private val SCRIPT = OriginPolicy(
            "script", usesGrant = false, judgeWithoutGate = true, refuseUnresolved = true, stopOnGuardWarning = false,
            gatedByCaller = false, isolatedReads = true, actionCapMs = SCRIPT_ACTION_CAP_MS, frames = Frames.WhenChanged, routeTargets = false,
            recordsRefusal = false, refusals = RefusalStyle.Script,
        )
        // Replay matches the model single step: no judgment when no confirmation gate is configured
        private val REPLAY = OriginPolicy(
            "replay", usesGrant = false, judgeWithoutGate = false, refuseUnresolved = true, stopOnGuardWarning = true,
            gatedByCaller = true, isolatedReads = false, actionCapMs = null, frames = Frames.None, routeTargets = true,
            recordsRefusal = false, refusals = RefusalStyle.Code,
        )
        private val MCP_TOOL = OriginPolicy(
            "mcp_tool", usesGrant = false, judgeWithoutGate = true, refuseUnresolved = true, stopOnGuardWarning = true,
            gatedByCaller = false, isolatedReads = false, actionCapMs = null, frames = Frames.None, routeTargets = false,
            recordsRefusal = true, refusals = RefusalStyle.Code,
        )
    }
}

/** Who initiated the action. Origin is kept as the name older callers use; RunOrigin only says where this run came from. */
typealias Invoker = Origin
