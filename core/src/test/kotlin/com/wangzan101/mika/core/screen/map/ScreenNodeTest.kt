package com.wangzan101.mika.core.screen.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScreenNodeTest {
    @Test fun screenIdIsDeterministicAndStable() {
        val a = ScreenId.of("com.example.app", "关于本机|设置")
        val b = ScreenId.of("com.example.app", "关于本机|设置")
        assertEquals(a, b) // same input twice gives the same id
        assertEquals(16, a.length) // 16 hex
        assertNotEquals(ScreenId.of("com.example.app", "关于本机|设置"), ScreenId.of("com.other.app", "关于本机|设置")) // different app
        assertNotEquals(ScreenId.of("com.example.app", "关于本机|设置"), ScreenId.of("com.example.app", "关于本机|设置|更多")) // different signature
        assertEquals(a, ScreenId.of("com.example.app", "关于本机|设置")) // stable, does not change over time
    }

    @Test fun codecRoundTrips() {
        val n = ScreenNode(
            id = ScreenId.of("com.example.app", "关于本机|设置"),
            app = "com.example.app",
            name = "",
            anchors = mapOf("关于本机" to 3, "设置" to 1),
            levels = emptyMap(),
            transitions = mapOf("k" to Transition("click", "设置", TransitionEffect.NAVIGATED, "abc123", 2, 0)),
            visits = 5,
            status = "confirmed",
        )
        assertEquals(n, ScreenNodeCodec.decode(ScreenNodeCodec.encode(n)))
    }

    @Test fun transitionKeyUsesTupleNotCounters() {
        val a = Transition("click", "设置", TransitionEffect.NAVIGATED, "target", count = 1, offTrack = 0)
        val b = Transition("click", "设置", TransitionEffect.NAVIGATED, "target", count = 9, offTrack = 3)
        assertEquals(a.key(), b.key()) // the same (action, target, effect, destination screen) tuple is one edge; the visit and off-track counts are tracked separately
        val c = Transition("click", "返回", TransitionEffect.NAVIGATED, "target", count = 1)
        assertNotEquals(a.key(), c.key()) // a different target gives a different edge
    }
}
