package com.wangzan101.mika.core.prompt

import com.wangzan101.mika.core.agent.BatchRunner
import com.wangzan101.mika.core.testing.PromptSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.wangzan101.mika.core.testing.BATCHABLE
import com.wangzan101.mika.core.assembly.allTools

/** The layering rules for prompts/ (see prompts/README.md): higher layers name no tools, each tool has one description, there are no stray files, and feedback entries are self-contained; each layer's size goes into the golden sample. */
class PromptLayoutTest {
    private val root = File("src/main/resources/prompts")
    private fun files(layer: String) = File(root, layer).listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet()
    private fun size(layer: String) = File(root, layer).listFiles().orEmpty().filter { it.isFile }.sumOf { it.readBytes().size }

    private val toolNames = allTools().map { it.name } + BatchRunner.spec(BATCHABLE).name
    /** Tool names that are also ordinary English words: used as plain words in system/, they do not count as naming a tool. */
    private val plainWords = setOf("phone_screenshot", "phone_back", "wait", "phone_tap", "phone_scroll", "phone_swipe")

    @Test fun systemLayerNamesNoTools() {
        for (f in files("system")) {
            val text = File(root, "system/$f").readText()
            val named = toolNames.filter { it !in plainWords && Regex("(?<![A-Za-z_])${Regex.escape(it)}(?![A-Za-z_])").containsMatchIn(text) }
            assertTrue("system/$f names tools $named; tool rules belong in capabilities/ or tools/", named.isEmpty())
        }
    }

    /** The general tool rules are always present and name no capability's tools (they appear even when that capability is absent). */
    @Test fun generalNamesNoCapabilityTool() {
        val text = File(root, "capabilities/general.md").readText()
        val named = allTools().filter { it.capability != null }.map { it.name }
            .filter { Regex("(?<![A-Za-z_])${Regex.escape(it)}(?![A-Za-z_])").containsMatchIn(text) }
        assertTrue("capabilities/general.md names capability tools $named; put them in that capability's section", named.isEmpty())
    }

    @Test fun everyToolHasExactlyOneDescriptionFile() {
        val used = allTools().associate { it.name to it.promptFile } + (BatchRunner.spec(BATCHABLE).name to "do_steps")
        val defs = allTools().associate { it.name to it.description } + (BatchRunner.spec(BATCHABLE).name to BatchRunner.spec(BATCHABLE).description)
        for ((tool, file) in used) {
            assertTrue("$tool: missing prompts/tools/$file.md", File(root, "tools/$file.md").isFile)
            // The description really comes from this file: it matches once the template's placeholders are replaced with arbitrary text
            val template = File(root, "tools/$file.md").readText().trim()
            val pattern = template.split(Regex("\\{\\{[a-z0-9_]+}}")).joinToString(".+") { Regex.escape(it) }
            assertTrue("$tool does not load prompts/tools/$file.md", Regex(pattern).matches(defs.getValue(tool)))
        }
        assertEquals("orphan or missing files in prompts/tools", used.values.map { "$it.md" }.toSet(), files("tools"))
    }

    /** Entry tools (the ones still offered when the capability group is stowed) each have a short description and there are no extras; the short descriptions name only tools registered for the task. */
    @Test fun everyEntryToolHasShortDescription() {
        val entries = allTools().filter { it.entry != null }.map { it.name }
        assertEquals(setOf("phone_launch_app", "phone_list_apps", "phone_set_alarm", "phone_set_timer", "phone_add_calendar_event", "phone_calendar_bulk_insert", "web_navigate", "web_read_page", "run_command", "list_folder_files", "audio_generate", "audio_transcribe", "speak", "image_generate", "video_frames", "video_generate"), entries.toSet())
        assertEquals(entries.map { "$it.md" }.toSet(), files("entries"))
        for (e in entries) {
            val text = File(root, "entries/$e.md").readText()
            val named = Regex("(?<![A-Za-z_])(?:phone|web)_[a-z_]+").findAll(text).map { it.value }.filter { it !in toolNames }.toList()
            assertTrue("entries/$e.md names unknown tools $named", named.isEmpty())
        }
    }

    @Test fun noOrphanFiles() {
        assertEquals(setOf("identity.md", "principles.md"), files("system"))
        assertEquals((listOf("phone", "app-access", "batch", "web", "sandbox", "audio", "transcribe", "image", "video", "general", "inline-screen", "channels", "groups", "handover", "root", "device_files", "mcp-lookup")).map { "$it.md" }.toSet(), files("capabilities"))
        assertEquals(Feedback.FILES.map { "$it.md" }.toSet(), files("feedback"))
        val roles = setOf("app_classifier", "describe", "intent_gate", "task_stages", "intent_decision", "locate", "memory_extract", "route_match", "route_decision", "skill_draft", "app_knowledge", "app_knowledge_input", "verifier", "outcome_judge", "user_screen", "view_image", "stop_summary", "conversation_title", "teach_skill", "notice_triage", "notice_learn", "notice_notes", "notice_day", "notice_week", "notice_month", "front_desk", "front_desk_phone")
        assertEquals(roles.map { "$it.md" }.toSet(), files("roles"))
        roles.forEach { assertTrue(Prompts.role(it).isNotBlank()) }
        assertEquals(setOf("README.md"), root.listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet())
    }

    /** Things written for the user to read (skills, app knowledge, memories) must follow the user's language: these role prompts have to say so, or the model writes them in English. */
    /** Conversation titles are written in the message's language: the prompt must not lead with Chinese characters. */
    @Test fun conversationTitlesFollowTheMessagesLanguage() {
        val text = Prompts.role("conversation_title")
        assertTrue("conversation_title does not require the message's language", text.contains("same language as the message"))
        assertFalse("conversation_title still asks for Chinese characters first", text.contains("Chinese characters"))
    }

    @Test fun learnedTextFollowsTheUsersLanguage() {
        mapOf(
            "skill_draft" to "in the language of the \"goal\"",
            "teach_skill" to "in the language of the \"goal\"",
            "app_knowledge" to "in the language of the \"goal\"",
            "memory_extract" to "in the language the user spoke in",
        ).forEach { (role, phrase) ->
            assertTrue("$role does not state that the user's language must be used", Prompts.role(role).contains(phrase))
        }
    }

    /** Every key in the feedback directory is used by some code (failure descriptions are taken whole by prefix). */
    @Test fun noOrphanFeedbackKeys() {
        val code = File("src/main/kotlin").walk().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val unused = Feedback.keys.filter { k -> "\"$k\"" !in code && !(k.startsWith("failure.") && "\"failure.\"" in code) }
        assertTrue("feedback keys not used by any code: $unused", unused.isEmpty())
    }

    /**
     * Every feedback entry is self-contained: no leading or trailing whitespace (surrounding newlines are trimmed on read anyway, so a composition relying on them would silently lose characters), and
     * code never joins feedback to other text with + or a string template; if they must be combined, the separation belongs in the template, filled in through {{placeholder}}.
     */
    @Test fun feedbackEntriesAreSelfContained() {
        for (f in Feedback.FILES) for ((k, v) in Feedback.parse(File(root, "feedback/$f.md").readText())) {
            assertTrue("feedback $k starts or ends with whitespace", v == v.trim())
        }
        val problems = mutableListOf<String>()
        for (file in File("src/main/kotlin").walk().filter { it.extension == "kt" }) {
            val s = file.readText()
            for (m in Regex("""Feedback(\[|\.format\()""").findAll(s)) {
                val end = closing(s, m.range.last)
                val before = s.substring(0, m.range.first).trimEnd()
                val after = s.substring(end + 1).trimStart()
                if (before.endsWith("+") || after.startsWith("+") || before.endsWith("\${")) problems += "${file.name}: ${s.substring(m.range.first, end + 1).take(60)}"
            }
            // Names that retrieve feedback (val X get() = Feedback…, fun x(…) = Feedback.format…) must not be concatenated either
            val names = Regex("""val ([A-Z_0-9]+): String get\(\) = Feedback""").findAll(s).map { it.groupValues[1] } +
                Regex("""fun ([a-zA-Z0-9]+)\([^)]*\) = Feedback\.format""").findAll(s).map { it.groupValues[1] }
            for (n in names.toSet()) {
                val used = Regex("""(\+\s*$n\b)|(\b$n\s*\+)|(\$$n\b)|(\$\{$n\b)""")
                // Other files may use it too
                File("src/main/kotlin").walk().filter { it.extension == "kt" }.forEach { g ->
                    used.find(g.readText())?.let { problems += "${g.name}: ${it.value} concatenates feedback $n" }
                }
            }
        }
        assertTrue("feedback used as part of a concatenation: $problems", problems.isEmpty())
    }

    /** Find the matching closing bracket starting from the opening bracket at [open]; string literals are skipped. */
    private fun closing(s: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < s.length) {
            when (s[i]) {
                '"' -> { i++; while (s[i] != '"') { if (s[i] == '\\') i++; i++ } }
                '(', '[' -> depth++
                ')', ']' -> if (--depth == 0) return i
            }
            i++
        }
        error("unbalanced at $open")
    }

    /**
     * Each layer's size (bytes, UTF-8) is written into the prompt surface's golden sample (prompt-surface/sizes.txt):
     * no hard limit is enforced; growth is visible in the committed diff, and reviews decide whether it is warranted.
     */
    @Test fun layerSizesRendered() {
        val text = buildString {
            for (layer in listOf("system", "capabilities", "tools", "entries", "feedback", "roles")) {
                appendLine("$layer: ${size(layer)} bytes, ${files(layer).size} files")
            }
        }
        print(text)
        PromptSurface.check("sizes", text)
    }
}
