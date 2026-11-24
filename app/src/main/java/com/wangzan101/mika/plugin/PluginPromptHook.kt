package com.wangzan101.mika.plugin

import com.wangzan101.mika.core.tool.ScriptRunner
import java.io.File

/**
 * Prompt hook: passes the assembled system prompt through every plugin script that has `promptTransform` enabled in
 * turn (the original prompt goes in on stdin, the rewritten one comes out on stdout).
 * If a single script times out after 3 seconds or exits non-zero, continue with the original prompt; `MIKA_MODEL` is
 * placed in the script environment, scoped per model. Results are cached by (content + model).
 */
class PluginPromptHook(
    private val registry: PluginRegistry,
    private val runner: ScriptRunner,
    private val modelName: () -> String,
    private val env: () -> Map<String, String> = { emptyMap() },
    private val timeoutMs: Long = 3000,
) {
    private val cache = mutableMapOf<String, String>()

    suspend fun transform(prompt: String): String {
        val key = "$prompt\u0000${modelName()}"
        cache[key]?.let { return it }
        var out = prompt
        for (record in registry.enabledPlugins()) {
            val m = registry.manifest(record.id) ?: continue
            val scriptPath = m.promptTransform ?: continue
            val dir = registry.pluginDir(record.id) ?: continue
            val result = runner.runStdin(File(dir, scriptPath), out, env() + ("MIKA_MODEL" to modelName()), timeoutMs)
            if (!result.timedOut && result.exitCode == 0) out = result.stdout
        }
        cache[key] = out
        return out
    }
}
