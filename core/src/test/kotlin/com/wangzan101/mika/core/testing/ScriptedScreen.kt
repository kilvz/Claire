package com.wangzan101.mika.core.testing

import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement

/** One screen in the script. */
data class Screen(val app: String, val elements: List<UiElement>)

fun button(ref: String, name: String, y: Int = 500) =
    UiElement(ref, "button", name, 540, y * 24 / 10, listOf(0, y - 40, 999, y + 40), null, true, false, false)

fun label(ref: String, name: String, y: Int = 200) =
    UiElement(ref, "text", name, 540, y * 24 / 10, listOf(0, y - 40, 999, y + 40), null, false, false, false)

fun field(ref: String, name: String, y: Int = 900, hint: Boolean = true) =
    UiElement(ref, "edit", name, 540, y * 24 / 10, listOf(0, y - 40, 999, y + 40), null, true, true, false, hint = hint)

/**
 * Phone screen that advances through the script: a screen read (ids s1, s2, …) returns the current screen; each write action
 * takes the next result from [results] (Ok(changed=true) once they run out). If the result is an Ok reporting a changed screen,
 * move on to the next screen (after the last screen it stays there). Peeks, which do not register an id, are numbered p1, p2, ….
 */
class ScriptedScreen(private val screens: List<Screen>, results: List<ActionResult> = emptyList()) : ScreenPort {
    private val results = ArrayDeque(results)
    private var index = 0
    private var reads = 0
    private var peeks = 0
    val calls = mutableListOf<String>()
    val current: Screen get() = screens[index]

    private fun read(id: String) = ScreenSnapshot(id, current.app, current.elements, 1080, 2400)
    override suspend fun snapshot() = read("s${++reads}")
    override suspend fun peek() = read("p${++peeks}")
    override suspend fun screenshotPng(): ByteArray? = null

    private fun act(what: String): ActionResult {
        calls += what
        val r = results.removeFirstOrNull() ?: ActionResult.Ok(changed = true)
        if (r is ActionResult.Ok && r.changed != false && index < screens.lastIndex) index++
        return r
    }

    override suspend fun tapRef(ref: String, snapshotId: String) = act("tap $ref $snapshotId")
    override suspend fun tapXY(x: Int, y: Int) = act("tapXY $x $y")
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = act("type $ref $text")
    override suspend fun clearText(ref: String?, snapshotId: String?) = act("clear $ref")
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = act("scroll $down")
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = act("phone_swipe")
    override suspend fun back() = act("phone_back")
    override suspend fun pressKey(keyCode: Int) = act("key $keyCode")
    override suspend fun openApp(packageName: String, cold: Boolean) = act("open $packageName")
    override suspend fun waitStable(timeoutMs: Long) = true
    override suspend fun listApps() = screens.map { it.app }.distinct().map { it to it }
}
