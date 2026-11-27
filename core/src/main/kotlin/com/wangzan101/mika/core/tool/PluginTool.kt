package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * Description of one plugin tool: read from the manifest on the app side and handed to core. [name] is the assembled full name (`plugin_<id without dots>_<tool name>`).
 * [scriptDir]/[scriptPath] is where the script lives in the plugin directory; [inputSchema] is the tool parameter schema supplied by the plugin.
 */
data class PluginToolSpec(
    val name: String,
    val description: String,
    val scriptPath: String,
    val scriptDir: File,
    val inputSchema: JsonObject,
    val timeoutSec: Int,
    val risk: Risk,
)

/** Runs one plugin script (runs sh inside the app process). [env] holds extra environment variables to add to the process environment. */
interface ScriptRunner {
    suspend fun run(script: File, argsFile: File, env: Map<String, String>, timeoutMs: Long): ScriptResult

    /** Writes [stdin] to the script's stdin and reads stdout as the result (used by prompt hooks); not supported by default. */
    suspend fun runStdin(script: File, stdin: String, env: Map<String, String>, timeoutMs: Long): ScriptResult =
        ScriptResult(1, "", "stdin execution not supported", timedOut = false)
}

/** How a finished script looks: exit code, output, and whether it was cut off by a timeout. */
data class ScriptResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

/**
 * Plugin tool: writes the argument JSON to a temporary file, hands it to [runner] to run the script, then treats the script's stdout (JSON) as the result.
 * A script that never exits, prints non-JSON, or returns `ok:false` all become a clean failure; the task is never brought down.
 */
internal class PluginScriptTool(
    private val spec: PluginToolSpec,
    private val runner: ScriptRunner,
    private val argsDir: File = File(System.getProperty("java.io.tmpdir"), "mika-plugin-args"),
) : AgentTool {
    override val def: ToolDef = spec.let { s ->
        val normalized = ToolSchema.normalizeObject(s.inputSchema)
        ToolDef(s.name, s.description, normalized.props, normalized.required)
    }
    override val verb = Verb.PluginScript
    override val risk: Risk get() = spec.risk
    override val offScreen: Boolean get() = true

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        argsDir.mkdirs()
        val argsFile = File(argsDir, "args-${System.nanoTime()}.json")
        argsFile.writeText(a.o.toString())
        val result = try {
            runner.run(File(spec.scriptDir, spec.scriptPath), argsFile, emptyMap(), spec.timeoutSec * 1000L)
        } finally {
            argsFile.delete()
        }
        if (result.timedOut) return fail("timed_out", "script did not finish within ${spec.timeoutSec}s")
        if (result.exitCode != 0) return fail("script_failed", result.stderr.ifBlank { result.stdout }.take(2000))
        val parsed = runCatching { Json.parseToJsonElement(result.stdout) as? JsonObject }.getOrNull()
            ?: return fail("bad_output", "script returned non-JSON output: ${result.stdout.take(200)}")
        if (parsed["ok"] == JsonPrimitive(false)) {
            val error = (parsed["error"] as? JsonPrimitive)?.contentOrNull ?: "script_error"
            val message = (parsed["message"] as? JsonPrimitive)?.contentOrNull ?: error
            return fail(error, message)
        }
        return ToolExecution(parsed)
    }
}

/** The plugin as a capability: no screen, no guard, no pacing; it only puts its own tools into the task. */
internal class PluginCapability(private val tools: List<AgentTool>) : Capability {
    override val id = "plugin"
    override fun status() = CapabilityStatus.Available
    override fun tools() = tools
    override fun sections(env: PromptEnv) = emptyList<String>()
}
