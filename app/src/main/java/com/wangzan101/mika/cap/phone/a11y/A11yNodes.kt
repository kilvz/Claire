package com.wangzan101.mika.cap.phone.a11y

import android.view.accessibility.AccessibilityNodeInfo

// Accessibility node-tree traversal: each limit is passed in by the caller. The sequences are lazy and stop on a hit, without fetching extra parents or children.

/** Ancestors going up (excluding self), nearest first, at most [max]; stops at the root. */
internal fun AccessibilityNodeInfo.ancestors(max: Int): Sequence<AccessibilityNodeInfo> =
    generateSequence(parent) { it.parent }.take(max)

/** Self, then up to [maxUp] ancestors above it. */
internal fun AccessibilityNodeInfo.selfAndAncestors(maxUp: Int): Sequence<AccessibilityNodeInfo> =
    generateSequence(this) { it.parent }.take(maxUp + 1)

/** Self and the nodes below it, breadth-first, visiting at most [max]. */
internal fun AccessibilityNodeInfo.breadthFirst(max: Int): Sequence<AccessibilityNodeInfo> = sequence {
    val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(this@breadthFirst) }
    var visited = 0
    while (queue.isNotEmpty() && visited++ < max) {
        val n = queue.removeFirst()
        yield(n)
        for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
    }
}
