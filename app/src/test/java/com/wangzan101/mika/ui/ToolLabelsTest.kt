package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.i18n.ResFileTexts
import com.wangzan101.mika.i18n.UiText
import com.wangzan101.mika.i18n.tr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ToolLabelsTest {
    @get:Rule val texts = ChineseTexts()

    /** Every tool that declares a label has UI text, and the English UI text has no Chinese left in it. */
    @Test
    fun everyDeclaredLabelIsLocalized() {
        val tools = knownTools()
        val labels = ToolLabels(tools)
        val declared = tools.names.mapNotNull { n -> tools[n]?.label?.let { n to it } }
        assertTrue(declared.size > 40)
        for ((name, label) in declared) assertTrue("$name: $label", label in ToolLabels.TOOL_LABELS)
        UiText.source = ResFileTexts.english
        for ((name, label) in declared) {
            assertTrue("$name: $label", label in ToolLabels.TOOL_LABELS)
            val shown = labels.label(name)
            assertTrue("$name: $shown", shown.isNotBlank())
            assertFalse("$name: $shown", shown.any { it in '\u3400'..'\u9fff' })
        }
        assertEquals("Tap", labels.label("tap"))
        assertEquals("Open web page", labels.label("browser_navigate"))
        assertEquals("Browse device files", labels.label("list_device_files"))
        assertEquals("Copy device file", labels.label("copy_device_file"))
    }

    /** Tools without a declared label look up UI text by tool name; only when nothing is found does the tool name itself show. */
    @Test
    fun toolWithoutDeclaredLabelIsLocalizedByName() {
        val labels = ToolLabels(knownTools())
        assertEquals("发送到我的微信", labels.label("wechat_send_owner"))
        assertEquals("unknown_tool_name", labels.label("unknown_tool_name"))
        UiText.source = ResFileTexts.english
        assertEquals("Send to my WeChat", labels.label("wechat_send_owner"))
    }

    /** An MCP tool name embeds a connector digest and is never shown as-is; the row already names the connector. */
    @Test
    fun mcpToolNamesFallBackToTheGenericLabel() {
        val labels = ToolLabels(knownTools())
        assertEquals(tr(R.string.tool_mcp), labels.label("mcp_github_661dcea9__search_repositories"))
        UiText.source = ResFileTexts.english
        assertEquals("MCP tool", labels.label("mcp_team_notes_1a2b3c4d__read"))
    }
}
