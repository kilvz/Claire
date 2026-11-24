package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.chat.ui.PipPhysics.Corner
import org.junit.Assert.assertEquals
import org.junit.Test

class PipPhysicsTest {
    // On a 1080×2400 screen: the top bar takes 300 (including the status bar) and the composer takes 250 (including the navigation bar); the system bars are 60 and 40
    private val collapsedTop = 300f
    private val collapsedBottom = 250f
    private val safeTop = 60f
    private val safeBottom = 40f

    @Test fun collapsedPipKeepsClearOfTheTopBarAndComposer() {
        assertEquals(collapsedTop to collapsedBottom, insets(1f))
    }

    @Test fun expandedPipOnlyAvoidsTheSystemBars() {
        assertEquals(safeTop to safeBottom, insets(0f))
    }

    @Test fun insetsSlideBetweenTheTwoStates() {
        val (top, bottom) = insets(0.5f)
        assertEquals(180f, top, 0.01f)
        assertEquals(145f, bottom, 0.01f)
        // Shares outside the range are clamped
        assertEquals(collapsedTop to collapsedBottom, insets(3f))
        assertEquals(safeTop to safeBottom, insets(-1f))
    }

    @Test fun expandedPipReachesTheScreenEdges() {
        val pipWidth = 220f
        val pipHeight = 470f
        val margin = 30f
        val collapsed = PipPhysics.Bounds(1080f, 2400f, pipWidth, pipHeight, margin, collapsedTop, collapsedBottom)
        val expanded = PipPhysics.Bounds(1080f, 2400f, pipWidth, pipHeight, margin, safeTop, safeBottom)
        // When expanded it hugs the status bar at the top and the navigation bar at the bottom: it can rest anywhere on the screen, not only in the middle
        assertEquals(30f to 90f, expanded.position(Corner.TopStart))
        assertEquals(830f to 1860f, expanded.position(Corner.BottomEnd))
        // When collapsed it still stays below the top bar and above the composer
        assertEquals(30f to 330f, collapsed.position(Corner.TopStart))
        assertEquals(830f to 1650f, collapsed.position(Corner.BottomEnd))
    }

    private fun insets(share: Float): Pair<Float, Float> =
        PipPhysics.restingInsets(collapsedTop, collapsedBottom, safeTop, safeBottom, share)
}
