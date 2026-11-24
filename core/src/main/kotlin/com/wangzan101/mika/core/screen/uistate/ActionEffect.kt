package com.wangzan101.mika.core.screen.uistate

import com.wangzan101.mika.core.screen.visual.FrameChange
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** The actual effect of an action on the app's or web page's UI state: judged from the UI state before and after the action (element table, widget states, input focus, location), with visual differences only as corroboration; [wire] is how effect.kind is written in the result. */
enum class EffectKind(val wire: String) {
    /** The screen changed clearly, and the change is consistent with this action. */
    Done("done"),
    /** Only small local changes (focus, a switch, one or two elements), or possibly still loading: take a look at the result. */
    SmallChange("small_change"),
    /** Nothing changed: try something different, do not repeat it as is. */
    NoEffect("no_effect"),
    /** Went elsewhere: on the phone the foreground switched to another app, on the web a different page was loaded (URL or tab changed); see [NewPlace] for the new place. */
    PlaceChanged("place_changed");

    companion object {
        fun of(wire: String?): EffectKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The structural difference between the UI states (element tables) before and after an action. Elements are aligned by
 * content (role + name + resource ID + whether it is a placeholder), not by ref (refs from different reads are not
 * interchangeable): same content, same position and same state counts as unchanged; only the widget states
 * ([UiElement.states]) changing counts as [toggled]; same content at a different position counts as [moved]
 * (scrolling, re-layout); text changed inside the same input field counts as [edited]; the rest are
 * [added]/[removed]. [focusMoved]: the element holding input focus changed (or focus appeared or disappeared).
 * Only elements that carry information ([UiElement.meaningful]) are considered.
 */
data class InterfaceDiff(
    val added: Int,
    val removed: Int,
    val moved: Int,
    val edited: Int,
    val toggled: Int,
    val focusMoved: Boolean,
    val total: Int,
) {
    val changes: Int get() = added + removed + moved + edited + toggled
    val none: Boolean get() = changes == 0 && !focusMoved

    /** Enough changes (thresholds in [EffectTuning]). */
    val major: Boolean get() = changes >= max(EffectTuning.MAJOR_MIN, ceil(total * EffectTuning.MAJOR_SHARE).toInt())

    companion object {
        fun between(before: ScreenSnapshot, after: ScreenSnapshot): InterfaceDiff {
            val a = before.elements.filter { it.meaningful }
            val b = after.elements.filter { it.meaningful }
            // First pair up fully identical ones, then those differing only in state (a switch was flipped), then moved ones, then ones whose text changed within the same input field
            val leftA = a.toMutableList()
            val leftB = b.toMutableList()
            pairOff(leftA, leftB) { x, y -> key(x) == key(y) && x.bounds == y.bounds && x.states == y.states }
            val toggled = pairOff(leftA, leftB) { x, y -> key(x) == key(y) && x.bounds == y.bounds }
            val moved = pairOff(leftA, leftB) { x, y -> key(x) == key(y) }
            val edited = pairOff(leftA, leftB) { x, y -> x.editable && y.editable && x.role == y.role && x.resourceId == y.resourceId && near(x, y) }
            val focusA = a.firstOrNull { it.focused }?.let(::place)
            val focusB = b.firstOrNull { it.focused }?.let(::place)
            return InterfaceDiff(leftB.size, leftA.size, moved, edited, toggled, focusA != focusB, max(a.size, b.size))
        }

        /** Pairs elements up per [match] and removes them from both sides; returns the number of pairs made. */
        private fun pairOff(a: MutableList<UiElement>, b: MutableList<UiElement>, match: (UiElement, UiElement) -> Boolean): Int {
            var n = 0
            val it = a.iterator()
            while (it.hasNext()) {
                val x = it.next()
                val j = b.indexOfFirst { y -> match(x, y) }
                if (j >= 0) { b.removeAt(j); it.remove(); n++ }
            }
            return n
        }

        private fun key(e: UiElement) = listOf(e.role, e.name, e.resourceId.orEmpty(), e.hint.toString())
        private fun place(e: UiElement) = listOf(e.role, e.resourceId.orEmpty(), e.bounds.toString())

        private fun near(x: UiElement, y: UiElement): Boolean {
            if (x.bounds.size != 4 || y.bounds.size != 4) return x.resourceId != null
            return distance(x, y) <= EffectTuning.NEAR
        }
    }
}

/**
 * The new place reached after an action: [name] is the generic designation (app package name, website host name,
 * consistent with the "where" used when reading the screen), and [kind] and [detail] are the details specific to that
 * kind of surface (phone "app":{package}; web "web":{url,title,tabId}).
 */
data class NewPlace(val name: String, val kind: String, val detail: JsonObject)

/**
 * The input field as read back after typing: [text] is what is in the field right now (an empty string when a
 * placeholder is shown; null for password fields, which are not read out), [matches] whether it is consistent with
 * what was to be typed (whitespace is ignored when comparing: some fields group digits with spaces).
 */
data class Readback(val text: String?, val secret: Boolean, val matches: Boolean)

/**
 * Facts from the port used to judge the effect: [changed] whether the port verified a change during the action;
 * [switchedTo] the app the port saw the foreground switch to; [verified] the readback right after typing matched;
 * [loading] the port says the page has not settled yet (network, navigation still in flight); [onUserScreen] the
 * action brought this app up on the user's own screen and left it there (the user is using it), so no visible change
 * on Claire's screen does not mean the tap missed.
 */
data class PortFacts(
    val changed: Boolean? = null,
    val switchedTo: String? = null,
    val verified: Boolean = false,
    val loading: Boolean = false,
    val onUserScreen: String? = null,
)

/**
 * The actual effect of one action: the kind, where it went (when it changed place), the UI structure and frame
 * differences, the input readback, and whether it may still be loading.
 * [treeBlind]: no change was seen (the kind is still [EffectKind.NoEffect]), but this screen's tree cannot read the
 * spot that was tapped (the tree is unusable, or a part is missing and the tap was not on any element); taps that only
 * change focus or visibility look the same, so it cannot be said the tap missed.
 */
data class ActionEffect(
    val kind: EffectKind,
    val place: NewPlace? = null,
    val diff: InterfaceDiff? = null,
    val frame: FrameChange? = null,
    val readback: Readback? = null,
    val loading: Boolean = false,
    val treeBlind: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("kind", kind.wire)
        place?.let { put("to", it.name); put(it.kind, it.detail) }
        diff?.let { t ->
            if (t.added > 0) put("added", t.added)
            if (t.removed > 0) put("removed", t.removed)
            if (t.moved > 0) put("moved", t.moved)
            if (t.edited > 0) put("edited", t.edited)
            if (t.toggled > 0) put("toggled", t.toggled)
            if (t.focusMoved) put("focusMoved", true)
        }
        frame?.let { put("frameChanged", (it.whole * 100).roundToInt() / 100.0) }
        readback?.let { r ->
            put("readback", if (r.secret) HIDDEN else r.text?.take(READBACK_MAX))
            put("readbackMatches", r.matches)
        }
        if (loading) put("mayStillBeLoading", true)
        if (treeBlind) put("treeBlind", true)
    }

    companion object {
        const val HIDDEN = "(hidden)"
        private const val READBACK_MAX = 200

        /** Actions where a lack of visible change must not be called a miss: taps and gestures (when they only change focus or visibility, neither the tree nor the frame may move). */
        private val BLIND_VERBS = setOf(Verb.Tap, Verb.PhoneGesture)

        /**
         * This screen's tree cannot read the spot this action acted on: the tree is unusable, or a part is missing and
         * the tap (the center of the frame region [region]) is not on any informative element. For taps on an element
         * from the tree ([target] not null) this only counts when the tree is unusable. [quality] is whether this
         * screen's tree was judged usable at the latest check, null when unknown.
         */
        internal fun treeBlind(verb: Verb, quality: TreeQuality?, before: ScreenSnapshot?, target: UiElement?, region: List<Int>?): Boolean {
            if (verb !in BLIND_VERBS) return false
            return when (quality) {
                TreeQuality.Unusable -> true
                TreeQuality.Partial -> {
                    if (target != null || region == null || region.size != 4) return false
                    val x = (region[0] + region[2]) / 2
                    val y = (region[1] + region[3]) / 2
                    before?.elements.orEmpty().none { e -> e.meaningful && e.bounds.size == 4 && x in e.bounds[0]..e.bounds[2] && y in e.bounds[1]..e.bounds[3] }
                }
                else -> false
            }
        }

        /**
         * Judges the effect from the UI state before and after the action. When [before]/[after] cannot be read it
         * judges only from the port facts and the frame; when neither is available it is null (no effect reported).
         * [target] is the action's target element (from the copy the model read, used to find the input field).
         * Typing ([Verb.TypeText]) carries [typed] (the text to type); with [pressedEnter] there is no readback
         * (after Enter the field may be cleared or the page may have changed).
         *
         * When unsure, it does not claim there was no effect: if the port says something changed it is at least a
         * small change; if the screen is moving or the port says it is still loading, it also counts only as a small
         * change (possibly still loading).
         */
        fun classify(
            verb: Verb,
            before: ScreenSnapshot?,
            after: ScreenSnapshot?,
            port: PortFacts,
            frame: FrameChange?,
            target: UiElement? = null,
            typed: String? = null,
            pressedEnter: Boolean = false,
            secret: Boolean = false,
        ): ActionEffect? {
            // The port itself saw the foreground switch apps: trust it (both reads may fall before or after the switch)
            port.switchedTo?.let { return ActionEffect(EffectKind.PlaceChanged, NewPlace(it, "app", buildJsonObject { put("package", it) }), frame = frame) }
            // Brought up on the user's own screen: the place changed, just not on Claire's screen
            port.onUserScreen?.let {
                return ActionEffect(EffectKind.PlaceChanged, NewPlace(it, "app", buildJsonObject { put("package", it); put("onUserScreen", true) }), frame = frame)
            }
            val raw = judge(verb, before, after, port, frame, target, typed, pressedEnter, secret) ?: return null
            val loading = port.loading || after?.changedDuringRead == true || after?.let(::showsProgress) == true
            return when {
                raw.kind != EffectKind.NoEffect -> raw.copy(loading = loading && raw.kind == EffectKind.SmallChange)
                loading -> raw.copy(kind = EffectKind.SmallChange, loading = true)
                port.changed == true -> raw.copy(kind = EffectKind.SmallChange)
                else -> raw
            }
        }

        private fun judge(
            verb: Verb, before: ScreenSnapshot?, after: ScreenSnapshot?, port: PortFacts, frame: FrameChange?,
            target: UiElement?, typed: String?, pressedEnter: Boolean, secret: Boolean,
        ): ActionEffect? {
            if (before == null || after == null) {
                return when {
                    port.changed == true || frame?.visible == true -> ActionEffect(EffectKind.SmallChange, frame = frame)
                    port.changed == false -> ActionEffect(EffectKind.NoEffect, frame = frame)
                    else -> null
                }
            }
            moved(before, after)?.let { return ActionEffect(EffectKind.PlaceChanged, it, frame = frame) }
            val diff = InterfaceDiff.between(before, after)
            val readback = if ((verb == Verb.TypeText || verb == Verb.ClearText) && !pressedEnter) {
                readback(after, target, if (verb == Verb.ClearText) "" else typed, secret)
            } else null
            val kind = when {
                readback != null && !readback.secret && readback.matches -> EffectKind.Done
                // The port read back right after writing and it matched, but that field cannot be found in this UI snapshot (or it is a password field): rely on the port's verification
                port.verified && (readback == null || readback.secret) && !pressedEnter -> EffectKind.Done
                readback != null && !readback.secret -> if (diff.none && frame?.visible != true) EffectKind.NoEffect else EffectKind.SmallChange
                diff.major -> EffectKind.Done
                diff.none -> when {
                    frame?.large == true && max(before.elements.count { it.meaningful }, after.elements.count { it.meaningful }) < EffectTuning.FEW_ELEMENTS -> EffectKind.Done
                    frame?.visible == true -> EffectKind.SmallChange
                    else -> EffectKind.NoEffect
                }
                else -> EffectKind.SmallChange
            }
            return ActionEffect(kind, diff = diff, frame = frame, readback = readback)
        }

        /** The UI has a progress-bar-like widget (it is loading). */
        private fun showsProgress(s: ScreenSnapshot) = s.elements.any { it.role.contains("progress", ignoreCase = true) && UiStates.DISABLED !in it.states }

        /** Switched app or loaded another page: on the web it compares the URL (ignoring anything after #) and the tab, on the phone the foreground package name. Null when nothing switched. */
        private fun moved(before: ScreenSnapshot, after: ScreenSnapshot): NewPlace? {
            val wb = before.web; val wa = after.web
            if (wb != null || wa != null) {
                if (wb == null || wa == null) return null
                if (wb.tabId != wa.tabId || wb.url.substringBefore('#') != wa.url.substringBefore('#')) {
                    return NewPlace(after.packageName ?: wa.url, "web", buildJsonObject { put("url", wa.url); put("title", wa.title); put("tabId", wa.tabId) })
                }
                return null
            }
            val pa = after.packageName ?: return null
            return if (before.packageName != null && before.packageName != pa) NewPlace(pa, "app", buildJsonObject { put("package", pa) }) else null
        }

        /**
         * The text in the input field right now: the same field (same role, same resource ID, nearby position) takes
         * precedence, otherwise the focused input field; null when none is found (no readback reported).
         * [want] is the text that should be there (an empty string when clearing); when it is null no readback is
         * done.
         */
        private fun readback(after: ScreenSnapshot, target: UiElement?, want: String?, secret: Boolean): Readback? {
            want ?: return null
            val fields = after.elements.filter { it.editable }
            val field = target?.let { t ->
                fields.filter { it.role == t.role && it.resourceId == t.resourceId }.minByOrNull { distance(it, t) }
                    ?.takeIf { distance(it, t) <= EffectTuning.SAME_FIELD }
            } ?: fields.firstOrNull { it.focused } ?: return null
            if (secret || field.secret) return Readback(null, secret = true, matches = false)
            val text = if (field.hint) "" else field.name
            return Readback(text, secret = false, matches = norm(text) == norm(want))
        }

        private fun norm(s: String) = s.filterNot { it.isWhitespace() }
    }
}

/** Distance between two element centers (within 0–999); 0 when a bounds is missing. */
private fun distance(a: UiElement, b: UiElement): Int {
    if (a.bounds.size != 4 || b.bounds.size != 4) return 0
    val dx = (a.bounds[0] + a.bounds[2]) / 2 - (b.bounds[0] + b.bounds[2]) / 2
    val dy = (a.bounds[1] + a.bounds[3]) / 2 - (b.bounds[1] + b.bounds[3]) / 2
    return kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toInt()
}

/** One sentence for the model based on the effect (effect.* in prompts/feedback/tools.md); a clear change needs no note. */
internal object EffectFeedback {
    /** [portSwitched]: the port already reported that the foreground was switched away (that note is already in the result), so do not repeat it. */
    fun hint(e: ActionEffect, verb: Verb, portSwitched: Boolean): String? {
        val r = e.readback?.takeIf { !it.secret && !it.matches }
        val differs = r?.let { Feedback.format("effect.readback_differs", "readback" to JsonPrimitive(it.text.orEmpty()).toString()) }
        return when (e.kind) {
            EffectKind.Done -> null
            EffectKind.PlaceChanged -> if (portSwitched) null else Feedback.format("effect.place_changed", "to" to e.place?.name)
            EffectKind.SmallChange -> differs ?: Feedback[if (e.loading) "effect.loading" else "effect.small_change"]
            EffectKind.NoEffect -> listOfNotNull(
                differs,
                Feedback[when {
                    e.treeBlind -> "effect.no_visible_change"
                    verb == Verb.Back -> "effect.no_effect.back"
                    verb == Verb.Scroll || verb == Verb.Swipe -> "effect.no_effect.scroll"
                    else -> "effect.no_effect"
                }],
            ).joinToString(" ")
        }
    }
}
