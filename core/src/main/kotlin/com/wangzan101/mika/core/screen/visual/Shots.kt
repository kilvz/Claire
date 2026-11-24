package com.wangzan101.mika.core.screen.visual

import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.tool.RECALL_KEEP
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.refExpired
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The most recent [RECALL_KEEP] screen reads, for view_past_screens to look back at; protected pages and payment pages keep no image. */
internal class Recall(val id: Int, val step: Int, val app: String?, val png: ByteArray?, val elementsText: String)

/**
 * Frame ids and look-backs shared by several screens: screenshotId counts up uniformly within a task, so view_past_screens finds those of any screen.
 * [step] is the round this call belongs to (the loop's "step N"), matching the step in look-back entries.
 */
internal class Shots {
    var step = 0
    var lastId = 0
        private set
    val recalls = ArrayDeque<Recall>()

    fun next(): Int = ++lastId

    fun remember(r: Recall) {
        recalls.addLast(r)
        while (recalls.size > RECALL_KEEP) recalls.removeFirst()
    }

    /**
     * The frame's screenshotId was used as a snapshotId (common when acting from the image): a frame id is for look-backs only and cannot be used to tap.
     * Screen-read ids are never all digits, so an all-digit value that does not exceed the last frame id issued is exactly this mistake; state plainly which one to use.
     */
    fun idAsSnapshot(o: JsonObject): ToolExecution? {
        val p = o["snapshotId"] as? JsonPrimitive ?: return null
        val n = p.content.trim().toIntOrNull() ?: return null
        if (n < 1 || n > lastId) return null
        return refExpired(Feedback.format("screenshot_id_as_snapshot", "id" to n), "screenshot_id")
    }
}
