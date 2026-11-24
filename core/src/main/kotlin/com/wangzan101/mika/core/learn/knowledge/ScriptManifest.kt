package com.wangzan101.mika.core.learn.knowledge

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import com.wangzan101.mika.core.workspace.SandboxPaths

/** One item in the skill script manifest (`scripts/manifest.json`); [file] is a path relative to the skill directory. */
@Serializable
data class ScriptEntry(val file: String, val runnable: Boolean, val needs: List<String> = emptyList(), val network: Boolean = false, val reason: String? = null)

/**
 * `scripts/manifest.json` under a skill directory: `[{file, runnable, needs, network, reason?}]`, where [ScriptEntry.file] is a path relative to the skill directory.
 * run_bundled_script, knowledge_read and skill_read all treat it as the source of truth.
 */
object ScriptManifest {
    const val NEEDS_NETWORK = "needs network access: the user can turn on \"Allow scripts to go online\" (允许脚本联网) in Claire's settings"
    private val json = Json { ignoreUnknownKeys = true }
    private val INTERPRETERS = mapOf("py" to "/usr/bin/python3", "mjs" to "/usr/bin/node", "js" to "/usr/bin/node", "sh" to "/bin/sh")

    /** Empty when it cannot be read; entries whose path is not relative to the skill directory are dropped. */
    fun parse(text: String): List<ScriptEntry> = try {
        json.decodeFromString<List<ScriptEntry>>(text).filter { safe(it.file) }
    } catch (e: SerializationException) {
        emptyList()
    } catch (e: IllegalArgumentException) {
        emptyList()
    }

    /** Use absolute paths: unaffected by a same-named file in the workspace or by PATH. */
    fun interpreterFor(file: String): String? = INTERPRETERS[file.substringAfterLast('.', "").lowercase()]

    /** Whether it can run right now, and the reason when it cannot: one marked not runnable uses its own reason; one that needs the network while networking is off is told to turn it on in settings. */
    fun effectiveRunnable(e: ScriptEntry, networkAllowed: Boolean): Pair<Boolean, String?> = when {
        !e.runnable -> false to (e.reason ?: "cannot be run for now")
        interpreterFor(e.file) == null -> false to "unsupported script type"
        e.network && !networkAllowed -> false to NEEDS_NETWORK
        else -> true to null
    }

    private fun safe(rel: String): Boolean {
        if (rel.isBlank() || rel.startsWith("/")) return false
        val base = "${SandboxPaths.SKILLS}/x"
        return SandboxPaths.normalize(rel, base)?.startsWith("$base/") == true
    }
}
