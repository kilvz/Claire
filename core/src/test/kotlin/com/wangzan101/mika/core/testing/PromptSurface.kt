package com.wangzan101.mika.core.testing

import com.wangzan101.mika.core.model.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import java.io.File
import java.security.MessageDigest

/**
 * Golden files for the prompt surface: written in full (not digested), so reviewing a change shows exactly what differs.
 * Golden files live in core/src/test/resources/prompt-surface/; regenerate with:
 *   ./gradlew :core:test --tests '*PromptSurface*' -PupdateSnapshots
 */
object PromptSurface {
    private val dir = File(System.getProperty("mika.promptSurfaceDir") ?: "src/test/resources/prompt-surface")
    private val update = System.getProperty("mika.updateSnapshots") == "true"

    fun check(name: String, actual: String) {
        val file = File(dir, "$name.txt")
        if (update) {
            file.parentFile.mkdirs()
            file.writeText(actual)
            return
        }
        if (!file.exists()) fail("没有金样 ${file.path};用 ./gradlew :core:test --tests '*PromptSurface*' -PupdateSnapshots 生成")
        assertEquals("提示词表面 $name 变了;确认是有意的改动后用 -PupdateSnapshots 重新生成", file.readText(), actual)
    }

    /** One capability combination: the full system prompt, then the tools sent to the model (name + digest of the definition; the full definitions are under tools). */
    fun system(system: String, tools: List<ToolSpec>): String = buildString {
        appendLine(system)
        appendLine()
        appendLine("==== tools (${tools.size}) ====")
        tools.forEach { appendLine("${it.name} #${hash(spec(it))}") }
    }

    /** The full picture of one request: the complete system prompt, then each tool's full definition in the order it was sent to the model. */
    fun layer(system: String, tools: List<ToolSpec>): String = buildString {
        appendLine(system)
        appendLine()
        appendLine("==== tools (${tools.size}) ====")
        tools.forEach { append(spec(it)); appendLine() }
    }

    fun tools(all: List<ToolSpec>): String = buildString {
        all.forEach { append(spec(it)); appendLine() }
    }

    private fun spec(t: ToolSpec) = "## ${t.name}\n${t.description}\n${t.parametersJsonSchema}\n"

    /** Full prompts of the internal roles (standard, verifier, describe, locate, and so on). */
    fun roles(): String = buildString {
        for (name in ROLES) {
            appendLine("## $name")
            appendLine(role(name))
        }
    }

    private val ROLES = listOf("describe", "intent_gate", "task_stages", "locate", "memory_extract", "route_match", "skill_draft", "app_knowledge", "app_knowledge_input", "verifier", "stop_summary", "notice_triage", "notice_learn")

    private fun role(name: String): String =
        checkNotNull(PromptSurface::class.java.getResourceAsStream("/prompts/roles/$name.md")) { "missing role prompt $name" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** Write out a set of "tag -> text sent to the model" pairs one by one. */
    fun entries(items: List<Pair<String, String>>): String = buildString {
        items.forEach { (k, v) -> appendLine("### $k"); appendLine(v) }
    }

    fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }
}
