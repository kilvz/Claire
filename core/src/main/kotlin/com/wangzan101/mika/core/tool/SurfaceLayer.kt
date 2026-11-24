package com.wangzan101.mika.core.tool

import kotlinx.serialization.json.JsonObject

/**
 * What the layer shared by screen capabilities (phone, web) does around every call: track which screen is being operated on, take a look before and after an action to report the actual effect, attach the screen as it is after the action,
 * wait for the screen to settle, and show it as it is now. The dispatcher only reaches it through this interface and knows nothing about screen reads, elements or ids as screen state; the wiring layer supplies the implementation at startup.
 * A screen is identified by the capability id.
 */
internal interface SurfaceLayer {
    /** The screen currently being operated on; when no call is in flight it is the last one operated on successfully, and at task start it is the first one. */
    val current: String

    /** A call starts, acting on the screen [id]; when it is not a screen or is not given, keep the current one. */
    fun enter(id: String?)

    /** That call ends; when [succeeded], its screen becomes the most recently operated screen. */
    fun leave(succeeded: Boolean)

    /** Which screen this tool acts on; null when it is not a screen tool. */
    fun surfaceOf(tool: AgentTool?): String?

    /** The result given to the model when a tool for this screen is driven with ids from another screen (not executed); null when it cannot tell or nothing is misaimed. */
    fun misaimed(tool: AgentTool, args: JsonObject, tools: ToolIndex): ToolExecution?

    /** This call is not on-screen (or did not go through [around]): the action target left by the previous call is not kept. */
    fun skip()

    /**
     * Wraps one tool execution: do the on-screen preparation first, [body] receives whether "the screen side reports the actual effect this time", then do the on-screen wrap-up
     * (attach the screen diff as per [inlineScreen], attach the image, report the effect, clear the points obtained from looking at the image).
     */
    suspend fun around(tool: AgentTool, args: JsonObject, inlineScreen: Boolean, body: suspend (reportsEffect: Boolean) -> ToolExecution): ToolExecution

    /** When on, capture the target element and anchor before a write action (set by the loop when the replay switch is on). */
    var captureTargets: Boolean

    /** For the run record: the port id that the model's id maps to right now; empty when there is none. */
    fun portRecord(tool: AgentTool?, args: JsonObject): JsonObject

    /** Which reads the model can still see right now (by id). */
    fun setVisible(ids: Set<String>)

    /** Wait for the most recently operated screen to settle, at most [timeoutMs]; returns normally even when the state cannot be read. */
    suspend fun settle(timeoutMs: Long)

    /** The text visible right now on the most recently operated screen (one entry per element, ids not registered); empty when it cannot be read. */
    suspend fun visibleText(): List<String>

    /**
     * On the most recently operated screen, wait until [until] holds, or stop when something unexpected is seen, the deadline is reached, or [interrupted] is true (the user cut in);
     * time is taken from [clock]. The screen read does not register ids.
     */
    suspend fun waitUntil(until: Until, clock: () -> Long, interrupted: () -> Boolean): UntilOutcome

    /** The current frame of the most recently operated screen and its image id; null when it is protected or cannot be captured. */
    suspend fun imageNow(): Pair<ByteArray, Int>?

    /** How it looks right now (content and image from the same moment); null when nothing is open. The observation has not been recorded into the ledger yet. */
    suspend fun viewNow(): ToolDispatcher.Glance?

    /** The version of the current place (the web page's mobile or desktop version); null when there is none or it cannot be read. */
    suspend fun mode(): String?
}
