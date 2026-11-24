package com.wangzan101.mika.core.cap.web.cdp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.screen.uistate.CdpKey
import com.wangzan101.mika.core.screen.uistate.CdpKeys
import com.wangzan101.mika.core.screen.uistate.KeyCombo
import com.wangzan101.mika.core.screen.uistate.KeyModifier

// Real mouse and keyboard input on web pages (the Input domain). Coordinates are always CSS pixels of the main frame's viewport, matching what the page script reports.
// Pacing (path, hold durations, key gaps, wheel notches) comes from [InputPacing]; the default [InputPacing.Instant] adds no pauses.
// The wheel may only be used on the tab currently being shown: on a background tab mouseWheel hangs and blocks every later Input command for this page (the caller is responsible for checking).

/** Conversion between view pixels and CSS pixels; [scale] is how many view pixels each CSS pixel occupies (view width ÷ page viewport width). */
object CdpGeometry {
    fun scale(viewWidth: Int, cssViewportWidth: Double): Double =
        if (viewWidth > 0 && cssViewportWidth > 0) viewWidth / cssViewportWidth else 1.0

    fun toCss(viewPx: Int, scale: Double): Double = if (scale > 0) viewPx / scale else viewPx.toDouble()
}

// ---------- One input action ----------

/**
 * The result of one input action. [NotSent]: not a single event that would change the page went out (the session is already closed, or the very first send was rejected by the protocol),
 * so redoing it with native input is safe. [Uncertain]: it failed after events were sent (including timeouts), so the page may already have received them and it must not be done again;
 * in that case any mouse buttons and keyboard keys still held have been released as far as possible.
 */
sealed interface InputOutcome<out T> {
    data class Done<T>(val value: T) : InputOutcome<T>
    data class NotSent(val error: Throwable) : InputOutcome<Nothing>
    data class Uncertain(val error: Throwable) : InputOutcome<Nothing>
}

/**
 * Every command sent during one input action goes through this: it records whether an event may already have reached the page and which mouse buttons and keyboard keys are currently held,
 * so they can be released on failure. Read-only queries (fetching the viewport, reading options) do not count as events.
 */
class InputTrack internal constructor(
    val session: CdpSession,
    private val timeoutMs: Long,
    val pacing: InputPacing = InputPacing.Instant,
) {
    var mayHaveDelivered = false
        private set
    private var pressedAt: Pair<Double, Double>? = null
    private var dragging = false
    private val heldKeys = ArrayList<CdpKey>()

    /** A command that changes the page. Session already closed before sending: not delivered. The protocol returned an error: this send had no effect, so if nothing was sent before it still counts as not delivered. */
    internal suspend fun effect(method: String, params: JsonObject): JsonObject {
        if (session.closed.value) throw CdpConnectionClosedException("CDP session closed before $method")
        val before = mayHaveDelivered
        mayHaveDelivered = true
        try {
            return session.send(method, params, timeoutMs = timeoutMs)
        } catch (e: CdpException) {
            mayHaveDelivered = before
            throw e
        }
    }

    internal suspend fun query(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject =
        session.send(method, params, timeoutMs = timeoutMs)

    internal suspend fun mouse(type: String, x: Double, y: Double, button: String, buttons: Int, clickCount: Int) {
        val s = session.inputScale
        // Record before sending: this press may have taken effect even if it times out
        if (type == "mousePressed") pressedAt = x to y
        effect(
            "Input.dispatchMouseEvent",
            buildJsonObject {
                put("type", type); put("x", x * s); put("y", y * s)
                put("button", button); put("buttons", buttons); put("clickCount", clickCount)
                put("pointerType", "mouse")
            },
        )
        session.pointer = x to y
        if (type == "mouseReleased") pressedAt = null
    }

    internal suspend fun wheel(x: Double, y: Double, deltaY: Double) {
        val s = session.inputScale
        effect(
            "Input.dispatchMouseEvent",
            buildJsonObject {
                put("type", "mouseWheel"); put("x", x * s); put("y", y * s)
                put("button", "none"); put("buttons", 0); put("deltaX", 0); put("deltaY", deltaY)
                put("pointerType", "mouse")
            },
        )
        session.pointer = x to y
    }

    /** Pauses briefly according to the pacing; no pause with [Instant][InputPacing.Instant]. */
    internal suspend fun pause(ms: Long) {
        if (ms > 0) delay(ms)
    }

    internal suspend fun drag(type: String, x: Double, y: Double, data: JsonObject) {
        dragging = true
        val s = session.inputScale
        effect("Input.dispatchDragEvent", buildJsonObject { put("type", type); put("x", x * s); put("y", y * s); put("data", data) })
        if (type == "drop") { dragging = false; pressedAt = null }
    }

    internal suspend fun key(type: String, k: CdpKey, modifiers: Int, text: String?) {
        if (type != "keyUp") heldKeys += k
        effect(
            "Input.dispatchKeyEvent",
            buildJsonObject {
                put("type", type)
                if (modifiers != 0) put("modifiers", modifiers)
                put("key", k.key); put("code", k.code)
                put("windowsVirtualKeyCode", k.keyCode); put("nativeVirtualKeyCode", k.keyCode)
                if (text != null) { put("text", text); put("unmodifiedText", text) }
            },
        )
        if (type == "keyUp") heldKeys.remove(k)
    }

    /** Releases the held keys as best it can: every send has a short limit and failures are ignored (the page's Input may already be stuck). */
    internal suspend fun release() {
        suspend fun quiet(method: String, params: JsonObject) {
            runCatching { session.send(method, params, timeoutMs = RELEASE_TIMEOUT_MS) }
        }
        if (dragging) quiet("Input.cancelDragging", JsonObject(emptyMap()))
        pressedAt?.let { (x, y) ->
            quiet("Input.dispatchMouseEvent", buildJsonObject {
                put("type", "mouseReleased"); put("x", x * session.inputScale); put("y", y * session.inputScale)
                put("button", "left"); put("buttons", 0); put("clickCount", 1); put("pointerType", "mouse")
            })
        }
        for (k in heldKeys.asReversed()) {
            quiet("Input.dispatchKeyEvent", buildJsonObject {
                put("type", "keyUp"); put("key", k.key); put("code", k.code)
                put("windowsVirtualKeyCode", k.keyCode); put("nativeVirtualKeyCode", k.keyCode)
            })
        }
        pressedAt = null; dragging = false; heldKeys.clear()
    }

    companion object {
        const val RELEASE_TIMEOUT_MS = 1_000L
    }
}

/** Performs one input action without pacing (see below). */
suspend fun <T> CdpSession.input(timeoutMs: Long, block: suspend InputTrack.() -> T): InputOutcome<T> = input(timeoutMs, InputPacing.Instant, block)

/** Performs one input action with pacing from [pacing]; each command is bounded by [timeoutMs]. Failures are split into "not sent" and "uncertain"; in the latter case the held keys have been released. */
suspend fun <T> CdpSession.input(timeoutMs: Long, pacing: InputPacing, block: suspend InputTrack.() -> T): InputOutcome<T> {
    val t = InputTrack(this, timeoutMs, pacing)
    return try {
        InputOutcome.Done(t.block())
    } catch (e: CancellationException) {
        withContext(NonCancellable) { t.release() }
        throw e
    } catch (e: Exception) {
        if (!t.mayHaveDelivered) InputOutcome.NotSent(e)
        else {
            t.release()
            InputOutcome.Uncertain(e)
        }
    }
}

/**
 * CSS width of the page's visual viewport, taken from the browser's layout data (Page.getLayoutMetrics) without going through the page script:
 * the page cannot change it, so it is used to convert view pixels into the CSS pixels that Input expects.
 */
suspend fun InputTrack.viewportCssWidth(): Double {
    val m = query("Page.getLayoutMetrics")
    fun width(k: String) = (m[k] as? JsonObject)?.get("clientWidth")?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 }
    return width("cssVisualViewport") ?: width("cssLayoutViewport") ?: throw CdpException(0, "no viewport size", "Page.getLayoutMetrics")
}

/** Moves there (along a path according to the pacing, then pauses), presses, holds briefly and releases; the page sees a trustworthy mouse click (pointerType mouse). */
suspend fun InputTrack.mouseClick(x: Double, y: Double) {
    approach(x, y)
    pause(pacing.dwell())
    mouse("mousePressed", x, y, "left", 1, 1)
    pause(pacing.press())
    mouse("mouseReleased", x, y, "left", 0, 1)
}

/** Only moves the mouse (along a path according to the pacing): triggers mouseover/mouseenter and CSS :hover. */
suspend fun InputTrack.mouseMove(x: Double, y: Double) = approach(x, y)

/** Moves the pointer from its last position (or from somewhere nearby if unknown) to (x, y), with the final send landing exactly on the target. */
private suspend fun InputTrack.approach(x: Double, y: Double) {
    val from = session.pointer ?: pacing.origin(x, y)
    val points = pacing.path(from, x to y)
    points.forEachIndexed { i, (px, py) ->
        if (i > 0) pause(pacing.moveStep())
        mouse("mouseMoved", px, py, "none", 0, 0)
    }
}

/**
 * Moves the pointer to (x, y), then turns the wheel, scrolling [distance] CSS pixels notch by notch (positive is downward), pausing between notches.
 * Only for the tab currently being shown (see the top of the file).
 */
suspend fun InputTrack.mouseWheel(x: Double, y: Double, distance: Double) {
    approach(x, y)
    pacing.notches(distance).forEachIndexed { i, d ->
        if (i > 0) pause(pacing.wheelGap())
        wheel(x, y, d)
    }
}

/**
 * The pointer does not move; the wheel turns at its current position (or at ([x], [y]) if unknown), scrolling [distance] CSS pixels notch by notch (positive is downward).
 * For scrolling casually during a dwell: no mouse move is sent and nothing is hovered. Only for the tab currently being shown (see the top of the file).
 */
suspend fun InputTrack.wheelInPlace(x: Double, y: Double, distance: Double) {
    val (px, py) = session.pointer ?: (x to y)
    pacing.notches(distance).forEachIndexed { i, d ->
        if (i > 0) pause(pacing.wheelGap())
        wheel(px, py, d)
    }
}

/**
 * Holds the left button and drags from the start point to the end point in [steps] steps. Dragging implemented with pointer events (sliders, reordering) receives the moves directly;
 * when the page uses native HTML drag-and-drop (draggable), the browser is first asked to hand the drag to us (setInterceptDrags),
 * and once the drag data arrives, dragEnter/dragOver/drop are sent at the destination. Returns whether the native drag-and-drop path was taken.
 */
suspend fun InputTrack.mouseDrag(fromX: Double, fromY: Double, toX: Double, toY: Double, steps: Int, stepDelayMs: Long = 16): Boolean = coroutineScope {
    val intercept = runCatching { query("Input.setInterceptDrags", buildJsonObject { put("enabled", true) }) }.isSuccess
    val intercepted = if (intercept) async(start = CoroutineStart.UNDISPATCHED) { session.events.first { it.method == "Input.dragIntercepted" } } else null
    try {
        approach(fromX, fromY)
        pause(pacing.dwell())
        mouse("mousePressed", fromX, fromY, "left", 1, 1)
        pause(pacing.press())
        val n = steps.coerceAtLeast(1)
        for (i in 1..n) {
            delay(stepDelayMs)
            if (intercepted?.isCompleted == true) break
            val f = i.toDouble() / n
            mouse("mouseMoved", fromX + (toX - fromX) * f, fromY + (toY - fromY) * f, "left", 1, 0)
        }
        val data = intercepted?.takeIf { it.isCompleted }?.await()?.params?.get("data") as? JsonObject
        if (data != null) {
            for (type in listOf("dragEnter", "dragOver", "drop")) drag(type, toX, toY, data)
        } else {
            mouse("mouseReleased", toX, toY, "left", 0, 1)
        }
        data != null
    } finally {
        intercepted?.cancel()
        if (intercept) runCatching { query("Input.setInterceptDrags", buildJsonObject { put("enabled", false) }) }
    }
}

/** Presses a key combination once: the modifiers are pressed in order, the main key is pressed and released, then the modifiers are released in reverse order. Keys that produce text use keyDown (with text); the others use rawKeyDown. */
suspend fun InputTrack.pressCombo(combo: KeyCombo) {
    var held = 0
    for (m in combo.modifiers) {
        held = held or m.bit
        key("rawKeyDown", m.key, held, null)
        pause(pacing.modifierGap())
    }
    val text = combo.text
    val k = if (text != null && text != combo.key.text) combo.key.copy(key = text) else combo.key
    key(if (text != null) "keyDown" else "rawKeyDown", k, held, text)
    pause(pacing.keyHold())
    key("keyUp", k, held, null)
    for (m in combo.modifiers.asReversed()) {
        pause(pacing.modifierGap())
        held = held and m.bit.inv()
        key("keyUp", m.key, held, null)
    }
}

/**
 * Typing: when paced, letters, digits and spaces are typed key by key (uppercase letters with Shift), with varying gaps between keys; the remaining characters (punctuation, Chinese, newlines)
 * are handed to the focused element in whole runs, like an IME commit. When not paced, or when the text is longer than [InputPacing.Limits.maxKeyedChars], the whole text is handed to the focused element (like a paste).
 * Newlines in the text never cause Enter to be pressed.
 */
suspend fun InputTrack.typeText(text: String) {
    if (!pacing.paced || text.length > pacing.limits.maxKeyedChars) return insertText(text)
    var first = true
    for (run in TypingRuns.split(text)) {
        if (!first) pause(pacing.keyGap())
        first = false
        if (!run.keyed) {
            insertText(run.text)
            continue
        }
        run.text.forEachIndexed { i, c ->
            if (i > 0) pause(pacing.keyGap())
            val k = CdpKeys.named(c.toString())!!
            if (c in 'A'..'Z') pressCombo(KeyCombo(listOf(KeyModifier.Shift), k))
            else {
                key("keyDown", k, 0, k.text)
                pause(pacing.keyHold())
                key("keyUp", k, 0, null)
            }
        }
    }
}

/** How typing is split into runs: a [keyed] run is typed key by key, the rest are submitted whole. */
object TypingRuns {
    data class Run(val text: String, val keyed: Boolean)

    fun keyable(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == ' '

    fun split(text: String): List<Run> {
        val out = ArrayList<Run>()
        val cur = StringBuilder()
        var keyed: Boolean? = null
        for (c in text) {
            val k = keyable(c)
            if (keyed != null && k != keyed) {
                out += Run(cur.toString(), keyed)
                cur.clear()
            }
            keyed = k
            cur.append(c)
        }
        if (keyed != null) out += Run(cur.toString(), keyed)
        return out
    }
}

/** Hands the whole text to the current focus (fires an input event just like an IME commit). */
suspend fun InputTrack.insertText(text: String) {
    effect("Input.insertText", buildJsonObject { put("text", text) })
}

// ---------- Native drop-downs ----------

data class SelectOption(val text: String, val value: String, val disabled: Boolean)

sealed interface SelectOutcome {
    /** [byScript]: the keyboard did not select it, so the value was set by script and input/change were fired manually (not trusted events). */
    data class Selected(val index: Int, val text: String, val changed: Boolean, val byScript: Boolean) : SelectOutcome
    data object NotASelect : SelectOutcome
    data object Disabled : SelectOutcome
    data class NoSuchOption(val available: List<String>) : SelectOutcome
    data class OptionDisabled(val text: String) : SelectOutcome
    /** Neither the keyboard nor a script could make it select this item. */
    data class NotApplied(val text: String) : SelectOutcome
}

object SelectMatch {
    /** First an exact match on value, then a match on the shown text (trimmed), and finally case-insensitive; null when none match. */
    fun indexOf(options: List<SelectOption>, wanted: String): Int? {
        val w = wanted.trim()
        options.indexOfFirst { it.value == wanted }.takeIf { it >= 0 }?.let { return it }
        options.indexOfFirst { it.text.trim() == w }.takeIf { it >= 0 }?.let { return it }
        return options.indexOfFirst { it.text.trim().equals(w, ignoreCase = true) || it.value.equals(w, ignoreCase = true) }.takeIf { it >= 0 }
    }
}

private const val SELECT_OF = "function(){var n=this;while(n&&!(n.tagName&&n.tagName.toUpperCase()==='SELECT')){" +
    "n=n.parentElement||(n.parentNode&&n.parentNode.host)||null;}return n;}"
private const val SELECT_INFO = "function(){return {disabled:!!this.disabled,multiple:!!this.multiple,index:this.selectedIndex," +
    "options:Array.prototype.slice.call(this.options,0,500).map(function(o){return {text:String(o.label||o.text||''),value:String(o.value),disabled:!!o.disabled};})};}"
private const val SELECT_INDEX = "function(i){return this.multiple?(this.options[i]&&this.options[i].selected?i:-1):this.selectedIndex;}"
private const val SELECT_SET = "function(i){if(this.multiple){this.options[i].selected=true;}else{" +
    "Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype,'selectedIndex').set.call(this,i);}" +
    "this.dispatchEvent(new Event('input',{bubbles:true}));this.dispatchEvent(new Event('change',{bubbles:true}));" +
    "return this.multiple?(this.options[i].selected?i:-1):this.selectedIndex;}"

private fun callParams(objectId: String, fn: String, args: List<Int>, byValue: Boolean) = buildJsonObject {
    put("objectId", objectId); put("functionDeclaration", fn); put("returnByValue", byValue)
    if (args.isNotEmpty()) put("arguments", buildJsonArray { args.forEach { add(buildJsonObject { put("value", it) }) } })
}

private fun callResult(r: JsonObject): JsonObject {
    (r["exceptionDetails"] as? JsonObject)?.let { throw CdpEvaluationException(it) }
    return r["result"]?.jsonObject ?: JsonObject(emptyMap())
}

/**
 * Selects an item of the native drop-down at (x, y). How: find the node by coordinates (DOM.getNodeForLocation, piercing shadow roots and iframes),
 * walk up to the select; after focusing it, move to the target option one step at a time with the arrow keys, reading back the current option after each press.
 * Arrow keys are trusted keyboard events, so page frameworks see real input/change, and the system picker is never shown.
 * Only when the arrow keys cannot get there (the page intercepts keys, a multi-select) is the value set by script and input/change fired manually. Focusing, key presses and scripted setting all count as events that change the page.
 */
suspend fun InputTrack.selectOptionAt(x: Double, y: Double, wanted: String): SelectOutcome {
    runCatching { query("DOM.enable") }
    val loc = query(
        "DOM.getNodeForLocation",
        buildJsonObject { put("x", x.toInt()); put("y", y.toInt()); put("includeUserAgentShadowDOM", false); put("ignorePointerEventsNone", true) },
    )
    val backend = (loc["backendNodeId"] as? JsonPrimitive)?.intOrNull ?: return SelectOutcome.NotASelect
    val node = query("DOM.resolveNode", buildJsonObject { put("backendNodeId", backend) })["object"]?.jsonObject
    val nodeId = (node?.get("objectId") as? JsonPrimitive)?.contentOrNull ?: return SelectOutcome.NotASelect
    val sel = callResult(query("Runtime.callFunctionOn", callParams(nodeId, SELECT_OF, emptyList(), byValue = false)))
    val selectId = (sel["objectId"] as? JsonPrimitive)?.contentOrNull ?: return SelectOutcome.NotASelect
    try {
        val info = callResult(query("Runtime.callFunctionOn", callParams(selectId, SELECT_INFO, emptyList(), byValue = true)))["value"] as? JsonObject
            ?: return SelectOutcome.NotASelect
        if ((info["disabled"] as? JsonPrimitive)?.booleanOrNull == true) return SelectOutcome.Disabled
        val multiple = (info["multiple"] as? JsonPrimitive)?.booleanOrNull == true
        val options = (info["options"] as? JsonArray).orEmpty().mapNotNull { o ->
            val m = o as? JsonObject ?: return@mapNotNull null
            SelectOption(
                (m["text"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim(),
                (m["value"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                (m["disabled"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
        val target = SelectMatch.indexOf(options, wanted) ?: return SelectOutcome.NoSuchOption(options.map { it.text })
        val want = options[target]
        if (want.disabled) return SelectOutcome.OptionDisabled(want.text)
        suspend fun current() =
            (callResult(query("Runtime.callFunctionOn", callParams(selectId, SELECT_INDEX, listOf(target), byValue = true)))["value"] as? JsonPrimitive)?.intOrNull ?: -1
        val start = (info["index"] as? JsonPrimitive)?.intOrNull ?: -1
        if (!multiple && start == target || multiple && current() == target) return SelectOutcome.Selected(target, want.text, changed = false, byScript = false)
        if (!multiple) {
            effect("DOM.focus", buildJsonObject { put("objectId", selectId) })
            var at = start
            var presses = 0
            // Read back after each press; if pressing did not move it (the page intercepts keys), stop pressing, and never press more times than there are options
            while (at != target && presses <= options.size) {
                pressCombo(KeyCombo(emptyList(), CdpKeys.named(if (at < target) "ArrowDown" else "ArrowUp")!!))
                presses++
                val now = current()
                if (now == at) break
                at = now
            }
            if (at == target) return SelectOutcome.Selected(target, want.text, changed = true, byScript = false)
        }
        val set = (callResult(effect("Runtime.callFunctionOn", callParams(selectId, SELECT_SET, listOf(target), byValue = true)))["value"] as? JsonPrimitive)?.intOrNull
        return if (set == target) SelectOutcome.Selected(target, want.text, changed = true, byScript = true) else SelectOutcome.NotApplied(want.text)
    } finally {
        runCatching { query("Runtime.releaseObject", buildJsonObject { put("objectId", selectId) }) }
    }
}
