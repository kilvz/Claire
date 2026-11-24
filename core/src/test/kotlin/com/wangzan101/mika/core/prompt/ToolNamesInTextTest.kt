package com.wangzan101.mika.core.prompt

import com.wangzan101.mika.core.assembly.allTools
import com.wangzan101.mika.core.assembly.knownToolNames
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.assembly.legacyNames
import com.wangzan101.mika.core.learn.knowledge.KnowledgeIndex
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.wangzan101.mika.core.tool.Verb
import org.junit.Test
import java.io.File

/**
 * Tool names mentioned in the knowledge, skills and prompts shipped with the app must all be currently registered:
 * screen-prefixed ones (phone_…, web_…), names from before the rename (browser_…, launch_app and the like) and old names
 * in backticks all count as mentioning a tool. It also guards the legacy name table: every old name maps to a current tool.
 */
class ToolNamesInTextTest {
    private val roots = listOf("src/main/resources/knowledge", "src/main/resources/skills", "src/main/resources/prompts").map(::File)
    private val known = knownToolNames()
    private val legacy = legacyNames()
    private val prefixed = Regex("(?<![A-Za-z0-9_{.=&/-])(?:phone|web|browser)_[a-z0-9_]*[a-z0-9](?![A-Za-z0-9_])")
    private val word = Regex("(?<![A-Za-z0-9_`{.=&/-])[a-z][a-z0-9]*(?:_[a-z0-9]+)+(?![A-Za-z0-9_`}])")
    private val ticked = Regex("`([a-z][a-z0-9_]*)`")

    @Test fun everyToolMentionIsARegisteredTool() {
        val bad = mutableListOf<String>()
        for (root in roots) for (f in root.walkTopDown().filter { it.isFile && it.extension in setOf("md", "json") }) {
            val text = f.readText()
            val mentions = prefixed.findAll(text).map { it.value } +
                word.findAll(text).map { it.value }.filter { legacy.replacements(it).isNotEmpty() } +
                ticked.findAll(text).map { it.groupValues[1] }.filter { legacy.replacements(it).isNotEmpty() }
            mentions.filter { it !in known }.distinct().forEach { bad += "${f.path}: $it" }
        }
        assertTrue("tool names that are not registered:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    /**
     * Everything the knowledge and skills tell the model to run must actually exist: script paths (`scripts/x.py`, `sites/a.b/scripts/x.py`,
     * a bare file name `x.py`) must match a script marked runnable in the manifest; an identifier used as a command (a backticked `a_b`
     * preceded or followed by wording such as use / run / invoke / command / script, or written as a `a_b(` call) must be a registered tool, a tool argument or a header key.
     */
    @Test fun everyCommandMentionExists() {
        val bad = commandMentions(File("src/main/resources"))
        assertTrue("commands or scripts that do not exist:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    private fun commandMentions(resources: File): List<String> {
        val runnable = GuideCatalog.loadBundled().runnableScripts()
        val byFileName = runnable.map { it.substringAfterLast('/') }.toSet()
        val allowed = known + allTools().flatMap { it.def.props.keys } + KnowledgeIndex.KEYS
        val bad = mutableListOf<String>()
        for (dir in listOf("knowledge", "skills")) for (f in File(resources, dir).walkTopDown().filter { it.isFile && it.extension == "md" }) {
            val rel = f.relativeTo(resources).invariantSeparatorsPath
            // The entry directory, written the same way as run_bundled_script's skill argument: skills/documents, skills/documents
            val entry = rel.removePrefix("knowledge/").split('/').take(2).joinToString("/")
            val text = f.readText()
            for (m in scriptPath.findAll(text)) {
                val path = m.value.removePrefix("/opt/mika/skills/").let { if (it.startsWith("scripts/")) "$entry/$it" else it }
                if (path !in runnable) bad += "$rel: ${m.value}"
            }
            scriptFile.findAll(text).map { it.value }.filter { it !in byFileName }.forEach { bad += "$rel: $it" }
            for (m in tickedIdentifier.findAll(text)) {
                val name = m.groupValues[1]
                if (name in allowed) continue
                val before = text.substring(maxOf(0, m.range.first - 16), m.range.first).split('\n', '。', '；', ';').last()
                val after = text.substring(m.range.last + 1, minOf(text.length, m.range.last + 9))
                if (callBefore.containsMatchIn(before) || callAfter.containsMatchIn(after)) bad += "$rel: $name"
            }
            called.findAll(text).map { it.groupValues[1] }.filter { it !in known }.forEach { bad += "$rel: $it(" }
        }
        return bad.distinct()
    }

    private val scriptPath = Regex("[A-Za-z0-9_./-]*scripts/[A-Za-z0-9_./-]+\\.(?:py|mjs|js|sh|ts)(?![A-Za-z0-9_])")
    private val scriptFile = Regex("(?<![A-Za-z0-9_./-])[a-z0-9_-]+\\.(?:py|mjs|sh)(?![A-Za-z0-9_])")
    private val tickedIdentifier = Regex("`([a-z][a-z0-9]*(?:_[a-z0-9]+)+)`")
    private val callBefore = Regex("用|跑|调用|运行|执行|通过|命令|脚本|工具|\\b(?:run|call|invoke|via|command|script|tool)s?\\b", RegexOption.IGNORE_CASE)
    private val callAfter = Regex("^(?:\\(|\\s*的?(?:工具|命令|脚本)|\\s*(?:command|script|tool)s?\\b)", RegexOption.IGNORE_CASE)
    private val called = Regex("(?<![A-Za-z0-9_.`])([a-z][a-z0-9]*(?:_[a-z0-9]+)+)\\(")

    @Test fun legacyTableFollowsTheRule() {
        assertEquals(listOf("phone_tap", "web_tap"), legacy.replacements("tap"))
        assertEquals(listOf("phone_launch_app"), legacy.replacements("launch_app"))
        assertEquals(listOf("web_navigate"), legacy.replacements("browser_navigate"))
        assertEquals(listOf("web_wait_for"), legacy.replacements("browser_wait"))
        // Names that did not change are not in the table
        assertTrue(legacy.replacements("finish").isEmpty() && legacy.replacements("run_command").isEmpty())
        // Every old name maps to a current tool, and the tools one old name maps to do the same thing; except those split into separate knowledge and skill tools
        val split = setOf(setOf(Verb.KnowledgeSearch, Verb.SkillSearch), setOf(Verb.KnowledgeRead, Verb.SkillRead))
        for (old in legacy.names) {
            val now = legacy.replacements(old)
            assertTrue(old, now.isNotEmpty() && now.all { it in known })
            val verbs = now.map { knownTools().verb(it) }.toSet()
            assertTrue(old, verbs.size == 1 || verbs in split)
        }
    }

    @Test fun rewriteChangesOnlyToolMentions() {
        assertEquals("用 `phone_launch_app` 打开,再 `phone_tap` / `web_tap` 按钮;tap 一下", legacy.rewrite("用 `launch_app` 打开,再 `tap` 按钮;tap 一下"))
        assertEquals("先 web_navigate 再 phone_type_text / web_type_text", legacy.rewrite("先 browser_navigate 再 type_text"))
        assertEquals("not_a_tool 与 phone_tap 不动", legacy.rewrite("not_a_tool 与 phone_tap 不动"))
    }
}
