package com.wangzan101.mika.core.cap.root

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.Risk as ApprovalRisk
import com.wangzan101.mika.core.tool.OutputClip
import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.Observation
import com.wangzan101.mika.core.screen.observation.Place
import com.wangzan101.mika.core.screen.observation.Trust
import com.wangzan101.mika.core.screen.observation.Untrusted
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.tool.int
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** The "place" that root commands declare: the confirmation card uses it to show a root command rather than some app. */
const val ROOT_CONFIRM_APP = "root"

/** Maximum command length; commands that change things are shown in full on the confirmation card. */
private const val COMMAND_MAX = 4_000
private const val DEFAULT_TIMEOUT_SEC = 30
private const val MAX_TIMEOUT_SEC = 300

/**
 * Runs one Android shell command as root (through su, uid 0) and returns the exit code, stdout and stderr (long output keeps its head and tail).
 * Read-only commands (see [RootCommandClass]) run directly; the rest declare themselves, per argument, as a step that needs asking: the user is shown the command first, and it runs only after they agree.
 * It does not touch the screen, and it goes into the action list.
 */
internal object RootRunCommand : AgentTool {
    override val capability: String get() = ROOT
    override val verb = Verb.RootCommand
    override val label = "root 命令"
    override val risk = Risk.Write
    override val offScreen = true
    override val logged = true
    override val wideTarget = true
    override val def = ToolDef("root_run_command", Prompts.tool("root_run_command"), mapOf(
        "command" to buildJsonObject {
            put("type", "string"); put("description", "The Android shell command to run as root, 1-$COMMAND_MAX characters.")
            put("minLength", 1); put("maxLength", COMMAND_MAX)
        },
        "timeoutSec" to int("Time limit in seconds, 1-$MAX_TIMEOUT_SEC (default $DEFAULT_TIMEOUT_SEC). The command is stopped when it runs out.", 1, MAX_TIMEOUT_SEC),
    ), listOf("command"))

    override fun available(ctx: ToolContext) = ctx.rootPort != null

    /** Read-only commands count as read-only; changing ones count as irreversible (recorded in the approval), but are no longer asked one by one — the user enabling "allow root commands" is itself the authorisation. */
    override fun approvalRisk(args: JsonObject): ApprovalRisk {
        val command = (args["command"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        return if (command.isNotEmpty() && RootCommandClass.classify(command) == RootCommandClass.Kind.ReadOnly) ApprovalRisk(ActionRisk.Read)
        else ApprovalRisk(ActionRisk.Irreversible)
    }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val command = a.str("command", max = COMMAND_MAX)!!
        val timeoutSec = a.int("timeoutSec", 1, MAX_TIMEOUT_SEC) ?: DEFAULT_TIMEOUT_SEC
        return when (val r = ctx.rootPort!!.run(command, timeoutSec * 1000L)) {
            is RootRun.Done -> done(r, command)
            RootRun.Denied -> fail("root_denied", Feedback["root.denied"])
            RootRun.NoAnswer -> fail("root_no_answer", Feedback["root.no_answer"])
            RootRun.Unavailable -> fail("root_unavailable", Feedback["root.unavailable"])
        }
    }

    /**
     * The result after running. The output (including on failure, which is still a fact) may be quoted in the answer, so it is recorded as an observation: the command was written by the assistant itself, so verbatim echoes in the output do not count as something it read;
     * what is read is mostly other apps' data, so it is treated as data only.
     */
    private fun done(r: RootRun.Done, command: String): ToolExecution {
        val (out, outClipped) = OutputClip.clip(r.stdout)
        val (err, errClipped) = OutputClip.clip(r.stderr, head = 1_000, tail = 3_000)
        val hints = listOfNotNull(
            Feedback["root.timed_out"].takeIf { r.timedOut },
            Feedback["root.truncated"].takeIf { outClipped || errClipped || r.droppedBytes > 0 },
        )
        return ToolExecution(buildJsonObject {
            put("ok", r.exitCode == 0 && !r.timedOut); put("exitCode", r.exitCode); put("timedOut", r.timedOut); put("durationMs", r.durationMs)
            put("stdout", Untrusted.wrap(out)); put("stderr", Untrusted.wrap(err))
            if (r.droppedBytes > 0) put("droppedBytes", r.droppedBytes)
            if (hints.isNotEmpty()) put("hint", hints.joinToString(" "))
        }, observations = listOf(Observation(ObsKind.Output, "Exit code ${r.exitCode}:\n$out", source = ROOT,
            place = Place.command("root_run_command「${command.take(200)}」"), trust = Trust.Untrusted, authored = command)))
    }

    private fun fail(error: String, message: String) = ToolExecution(buildJsonObject {
        put("ok", false); put("error", error); put("message", message); put("recoverable", false)
    })

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("command")
}

internal object RootGroup {
    val tools: List<AgentTool> = listOf(RootRunCommand)
}
