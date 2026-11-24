package com.wangzan101.mika.core.screen.map

import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.learn.replay.Anchors
import com.wangzan101.mika.core.learn.replay.Selector
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMapBuilderTest {
    private fun nav(step: Int, tool: String, target: Selector?, before: Anchors, after: Anchors, effective: Boolean = true) =
        RunEvent.ActionTarget(atMs = step.toLong(), step = step, tool = tool, argsJson = "{}", target = target,
            before = before, after = after, effective = effective)

    @Test fun buildNavigatesBetweenTwoScreensDeterministically() {
        val app = "com.example.app"
        val a = Anchors(app, listOf("主页", "设置"))
        val b = Anchors(app, listOf("关于本机"))
        val events = listOf(
            nav(1, "phone_tap", Selector("button", "设置", null, listOf(1, 2, 3, 4), 0), a, b),
            nav(2, "phone_back", null, b, a),
        )
        val builder = ScreenMapBuilder()
        val g1 = builder.apply("run1", events)
        val g2 = builder.apply("run1", events)

        // One graph, two screens
        assertEquals(setOf(app), g1.keys)
        val graph = g1.getValue(app)
        assertEquals(2, graph.nodes.size)

        val screenA = graph.nodes.values.first { it.anchors.containsKey("主页") }
        val screenB = graph.nodes.values.first { it.anchors.containsKey("关于本机") }

        // Screen ids are deterministic: different structure, different id
        assertTrue(screenA.id != screenB.id)
        assertEquals(2, screenA.visits)
        assertEquals(2, screenB.visits)
        assertEquals("confirmed", screenA.status)
        assertEquals("confirmed", screenB.status)

        // Transition edges: tapping "Settings" on A → B (NAVIGATED); back on B → A
        val aTrans = screenA.transitions.values.single()
        assertEquals("phone_tap", aTrans.action)
        assertEquals("设置", aTrans.target)
        assertEquals(TransitionEffect.NAVIGATED, aTrans.effect)
        assertEquals(screenB.id, aTrans.to)
        assertEquals(1, aTrans.count)

        val bTrans = screenB.transitions.values.single()
        assertEquals("phone_back", bTrans.action)
        assertEquals(null, bTrans.target)
        assertEquals(TransitionEffect.NAVIGATED, bTrans.effect)
        assertEquals(screenA.id, bTrans.to)

        // Determinism: the same batch of events run twice serializes byte-for-byte identically
        val enc = Json { prettyPrint = false }
        val s1 = enc.encodeToString(g1.mapValues { it.value.nodes })
        val s2 = enc.encodeToString(g2.mapValues { it.value.nodes })
        assertEquals(s1, s2)
    }

    @Test fun classifiesLeftAppAndStateChangeEffects() {
        val app = "com.example.app"
        val other = "com.other.app"
        val home = Anchors(app, listOf("主页"))
        val detail = Anchors(app, listOf("详情"))
        val foreign = Anchors(other, listOf("他App"))

        val events = listOf(
            nav(1, "phone_tap", Selector("button", "详情", null, listOf(0, 0, 1, 1), 0), home, detail, effective = true),
            nav(2, "phone_tap", Selector("button", "分享", null, listOf(0, 0, 1, 1), 0), detail, foreign, effective = true),
        )
        val graphs = ScreenMapBuilder().apply("run2", events)
        val graph = graphs.getValue(app)
        val homeNode = graph.nodes.values.first { it.anchors.containsKey("主页") }
        val detailNode = graph.nodes.values.first { it.anchors.containsKey("详情") }

        // Navigating to another screen
        assertEquals(TransitionEffect.NAVIGATED, homeNode.transitions.values.single().effect)
        assertEquals(detailNode.id, homeNode.transitions.values.single().to)
        // Jumping to another app → LEFT_APP, with to empty
        assertEquals(TransitionEffect.LEFT_APP, detailNode.transitions.values.single().effect)
        assertEquals(null, detailNode.transitions.values.single().to)
        // Another app gets its own graph
        assertNotNull(graphs[other])
    }

    @Test fun stateChangeSameScreenAddsStateChangedEdge() {
        val app = "com.example.app"
        val home = Anchors(app, listOf("主页", "开关"))
        val events = listOf(
            nav(1, "phone_tap", Selector("switch", "开关", null, listOf(0, 0, 1, 1), 0), home, home, effective = true),
        )
        val graphs = ScreenMapBuilder().apply("run3", events)
        val node = graphs.getValue(app).nodes.values.single()
        assertEquals(1, node.transitions.size)
        val t = node.transitions.values.single()
        assertEquals(TransitionEffect.STATE_CHANGED, t.effect)
        assertEquals(node.id, t.to)
    }
}
