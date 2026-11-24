package com.wangzan101.mika.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LeakedToolCallTest {
    // Same as the real tool list: put tool names that are also common words, such as wait and find, first
    private val tools = listOf("wait", "find", "ask_user", "web_read_page", "phone_observe")

    /** An XML-style call that the server did not parse, which ended up verbatim in the text. */
    @Test fun xmlStyleCallIsFound() {
        val text = "<tool_call>\n<function=ask_user>\n<parameter=question>\n想先确认一下:是要开户,还是了解集合竞价?\n</parameter>\n</function>\n</tool_call>"
        assertEquals("ask_user", LeakedToolCall.find(text, tools))
    }

    @Test fun jsonStyleCallIsFound() {
        val text = "<tool_call>\n{\"name\": \"web_read_page\", \"arguments\": {}}\n</tool_call>"
        assertEquals("web_read_page", LeakedToolCall.find(text, tools))
    }

    @Test fun otherMarkersAreFound() {
        assertEquals("phone_observe", LeakedToolCall.find("[TOOL_CALLS][{\"name\": \"phone_observe\", \"arguments\": {}}]", tools))
        assertEquals("phone_observe", LeakedToolCall.find("<｜tool▁calls▁begin｜><｜tool▁call▁begin｜>function<｜tool▁sep｜>phone_observe", tools))
    }

    /** The user is asking about exactly these formats: the answer mentions the markup but none of this run's tools, so it is a normal answer. */
    @Test fun markupWithoutAKnownToolIsNotALeak() {
        assertNull(LeakedToolCall.find("有的模型写工具调用时用 `<tool_call>` 包起来,里面是 `<function=名字>`。", tools))
    }

    /** Mentioning a tool name in the answer (for example, to explain what was done) without call markup does not count either. */
    @Test fun toolNameWithoutMarkupIsNotALeak() {
        assertNull(LeakedToolCall.find("我用 phone_observe 看过屏幕,安卓版本是 14。", tools))
    }

    /** A common word before the marker that matches a tool name (wait, find) does not count: what is reported is the tool actually called after the marker. */
    @Test fun toolNameIsTakenAfterTheMarker() {
        val text = "Wait, let me find the page first.\n<tool_call>\n<function=web_read_page>\n</function>\n</tool_call>"
        assertEquals("web_read_page", LeakedToolCall.find(text, tools))
    }

    /** A tool name that is only part of another word does not count as mentioning that tool. */
    @Test fun toolNameMustBeAWholeWord() {
        assertNull(LeakedToolCall.find("<tool_call>{\"name\": \"ask_user_later\"}</tool_call>", tools))
        assertNull(LeakedToolCall.find("<tool_call>{\"name\": \"my_ask_user\"}</tool_call>", tools))
    }
}
