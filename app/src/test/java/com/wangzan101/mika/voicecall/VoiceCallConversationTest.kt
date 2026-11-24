package com.wangzan101.mika.voicecall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallConversationTest {
    private val parents = mapOf("task-1" to "main")

    @Test fun `the chat page shows an ordinary conversation and the call attaches to it`() {
        assertEquals("main", VoiceCallConversation.pick("main", parents::get) { error("must not open a new one") })
    }

    @Test fun `the chat page sits in a front-desk task conversation, the call attaches back to its front-desk conversation, and the earlier call stays`() {
        assertEquals("main", VoiceCallConversation.pick("task-1", parents::get) { error("must not open a new one") })
    }

    @Test fun `the chat page is a blank new conversation, it claims the next one, the chat page moves onto it too, and reopening shows the same one`() {
        var showing: String? = null
        val claim = { "fresh".also { showing = it } }
        val first = VoiceCallConversation.pick(showing, parents::get, claim)
        assertEquals("fresh", first)
        assertTrue(showing == "fresh")
        assertEquals(first, VoiceCallConversation.pick(showing, parents::get) { error("must not open another one the second time") })
    }
}
