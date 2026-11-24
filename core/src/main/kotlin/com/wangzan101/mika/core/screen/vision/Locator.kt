package com.wangzan101.mika.core.screen.vision

import com.wangzan101.mika.core.screen.uistate.ScreenFamily
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.screen.uistate.ScreenDiagnosis
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.TreeAdequacy
import com.wangzan101.mika.core.screen.uistate.TreeQuality
import com.wangzan101.mika.core.screen.uistate.UiElement
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/** When the hash distance between two screenshots does not exceed this they count as the same frame (control tree in sync with the frame, @v refs still valid). */
const val LOCATE_SCREEN_CHANGE = 8

sealed interface LocateResult {
    /** [x]/[y]/[box] are 0–999 normalized, in the same coordinate system as tap; [source] is "elements" or "vision". */
    data class Found(
        val ref: String,
        val snapshotId: String,
        val x: Int,
        val y: Int,
        val box: IntArray?,
        val seen: String?,
        val source: String,
        val note: String?,
        /** Marked by sight alone with nothing to corroborate it (this screen's tree is unusable, so there is nothing to compare against): check the effect after tapping. */
        val unverified: Boolean = false,
    ) : LocateResult

    data class NotFound(val reason: String, val message: String) : LocateResult
}

/** The frame cannot be obtained or must not be shown to the vision model; [reason] is protected_content, no_app_open, etc. */
class ScreenUnavailable(val reason: String, message: String) : Exception(message)

/** Messages given to the model when something cannot be found or looked at; they name the tools of this screen's own set. */
private class LocateMessages(f: ScreenFamily) {
    private val describe = f.tool("describe")
    private val dump = f.tool("list_elements")
    val notFoundNext = "Try another description or pass the exact on-screen label as text, look with $describe, or call hand_to_user."
    val protectedScreen = "This screen is protected and cannot be captured, so nothing on it can be located by sight. " +
        "Use $dump and tap by ref, or call hand_to_user."
    val noApp = "Nothing is open on this screen yet, so there is nothing to look at. Open an app or a page first."
    val noShot = "The screen could not be captured right now. Wait a moment and try again, or use $dump."
    val unchecked = "The screen could not be read, so its picture was not sent to the vision model. " +
        "Wait a moment and try again, or use $dump."
    val checkSeen = "Check that seen is what you want before tapping; if it is not, do not tap it."
    val uncorroborated = "Something was marked by sight, but this vision model's boxes are not verified and nothing backed this one up " +
        "(no on-screen label under it matching the text, and a second look did not mark the same element), so it was not used. " +
        "Use $describe to look at the screen, then tap by coordinates with a target; or pass the exact on-screen label as text."
    val visionOnly = "Marked by sight alone: this screen's element list cannot back the mark up, so it was not cross-checked. " +
        "Check that seen is what you want, tap it, then read the result's effect: if it is no_effect, the mark missed; do not tap it again, " +
        "find it again with a different description or tap a visibleText center instead."
}

private val BBOX_2D = Regex("\"?bbox_2d\"?\\s*:\\s*\\[\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*]")
private val BARE_BOX = Regex("^\\[\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*]$")
private val FOUR_NUMBERS = Regex("\\[\\s*\\d+(?:\\.\\d+)?\\s*,\\s*\\d+(?:\\.\\d+)?\\s*,\\s*\\d+(?:\\.\\d+)?\\s*,\\s*\\d+(?:\\.\\d+)?\\s*]")
private val SEEN = Regex("\"seen\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
private val YES = Regex("^(是|yes(?![A-Za-z0-9_]))", RegexOption.IGNORE_CASE)
private const val MAX_SEEN = 120

/** How many times visual locating is attempted in total (a failure gets one retry): a model's box selection / second look can be flaky, so one flaky result should not be judged as no progress on the task. */
private const val VISION_ATTEMPTS = 2

/** Transient failures in visual locating that are worth retrying; environment failures (screenshot protection, no app open, no screenshot) and "found but wrong / uncorroborated" (name_mismatch, unverified_grounding) are not retried. */
private val TRANSIENT_VISION = setOf("not_found", "not_confirmed", "vision_unavailable")

private val PROMPTS: Pair<String, String> by lazy {
    val (locate, confirm) = Prompts.role("locate").split("<!-- confirm -->")
    locate.trim() to confirm.trim()
}
private val DESCRIBE_PROMPT: String get() = Prompts.role("describe")

/**
 * Finds an element on screen: when an exact label is given and the control tree is in sync with the frame, first look for a unique exact match in the control tree;
 * otherwise have the vision model draw a box, then check the name and confirm it with a second look at a red-framed image. The found point is registered into [registry].
 * Frames of protected pages (system screenshot protection) are never handed to the vision model.
 */
class Locator(private val screen: ScreenPort, private val vision: VisionService, private val registry: LocateRegistry, family: ScreenFamily) {
    private val m = LocateMessages(family)
    private class Box(val l: Int, val t: Int, val r: Int, val b: Int, val seen: String?)

    suspend fun locate(target: String, text: String?): LocateResult {
        val first = capture().let { it as? Shot.Ok ?: return (it as Shot.Missing).asNotFound() }
        val elements = if (text != null) readElements() else null
        val second = capture().let { it as? Shot.Ok ?: return (it as Shot.Missing).asNotFound() }
        val synced = elements != null && hamming(first.hash, second.hash) <= LOCATE_SCREEN_CHANGE

        if (text != null && synced) {
            val hit = exactMatch(elements!!, text)
            if (hit != null) {
                val b = hit.bounds
                val x = mid(b[0], b[2]); val y = mid(b[1], b[3])
                val (ref, sid) = registry.put(x, y, second.hash)
                return LocateResult.Found(ref, sid, x, y, intArrayOf(b[0], b[1], b[2], b[3]), null, "elements", null)
            }
        }

        clearForVision()?.let { return it.asNotFound() }
        return locateByVision(target, text, elements, synced, second)
    }

    /**
     * Visual locating (box selection + confirmation by a second look) is inherently flaky: models are non-deterministic, so transient failures (no box given, second look not confirmed, name mismatch,
     * vision call failed, corroboration failed) get one retry before giving up, instead of treating one flaky result as no progress on the task. Environment failures are already blocked in [locate] and never reach here.
     */
    private suspend fun locateByVision(target: String, text: String?, elements: List<UiElement>?, synced: Boolean, shot: Shot.Ok): LocateResult {
        val (locatePrompt, confirmPrompt) = PROMPTS
        var last: LocateResult.NotFound? = null
        repeat(VISION_ATTEMPTS) {
            when (val r = visionOnce(target, text, elements, synced, shot, locatePrompt, confirmPrompt)) {
                is LocateResult.Found -> return r
                is LocateResult.NotFound -> {
                    if (r.reason !in TRANSIENT_VISION) return r
                    last = r
                }
            }
        }
        return last!!
    }

    private suspend fun visionOnce(target: String, text: String?, elements: List<UiElement>?, synced: Boolean, shot: Shot.Ok, locatePrompt: String, confirmPrompt: String): LocateResult {
        val located = try {
            vision.askWithGrounding(locatePrompt, if (text != null) "$target (label: \"$text\")" else target, listOf(shot.png))
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return LocateResult.NotFound("vision_unavailable", "Locating by sight failed this time. ${m.notFoundNext}")
        }
        val box = parseBox(located.text)
            ?: return LocateResult.NotFound("not_found", "The described element was not found on the screen. ${m.notFoundNext}")

        if (text != null && box.seen != null && disagree(box.seen, text)) {
            return LocateResult.NotFound("name_mismatch", "The closest match reads \"${box.seen.take(40)}\", not \"$text\", so it was not used. ${m.notFoundNext}")
        }
        val x = mid(box.l, box.r); val y = mid(box.t, box.b)
        val occupant = if (text != null && synced) elementAt(elements!!, x, y) else null
        if (occupant != null && occupant.name.isNotBlank() && disagree(occupant.name, text!!)) {
            return LocateResult.NotFound("name_mismatch", "The spot that looked like it is labelled \"${occupant.name.take(40)}\", not \"$text\", so it was not used. ${m.notFoundNext}")
        }

        val boxArr = intArrayOf(box.l, box.t, box.r, box.b)
        val confirmed = try {
            val framed = vision.ops.drawFrame(shot.png, boxArr)
            YES.containsMatchIn(vision.ask(confirmPrompt.replace("{target}", target), "Yes or no?", listOf(framed)).trim())
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return LocateResult.NotFound("vision_unavailable", "Locating by sight failed this time. ${m.notFoundNext}")
        }
        if (!confirmed) return LocateResult.NotFound("not_confirmed", "Something was marked, but a second look did not confirm it is the described element. ${m.notFoundNext}")

        val nameChecked = occupant != null && occupant.name.isNotBlank()
        // For a model whose grounding is not verified, a sight-only box needs corroboration: the element at the box center is the wanted label (the name was already checked above), or a second box plus two confirmations agree and the text seen matches.
        // When the tree is unusable, or is missing a piece and what is wanted is not in the tree, there is nothing to compare against: hand it out anyway, marked as uncorroborated, and let the effect after tapping speak
        val visionOnly = !located.groundingVerified && !nameChecked && nothingToCheckAgainst(text)
        if (!located.groundingVerified && !nameChecked && !visionOnly) {
            val again = try {
                secondLook(target, text, box, shot.png, locatePrompt, confirmPrompt)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                return LocateResult.NotFound("vision_unavailable", "Locating by sight failed this time. ${m.notFoundNext}")
            }
            if (!again) return LocateResult.NotFound("unverified_grounding", m.uncorroborated)
        }

        val (ref, sid) = registry.put(x, y, shot.hash)
        if (visionOnly) return LocateResult.Found(ref, sid, x, y, boxArr, box.seen, "vision", m.visionOnly, unverified = true)
        return LocateResult.Found(ref, sid, x, y, boxArr, box.seen, "vision", m.checkSeen.takeUnless { nameChecked && box.seen != null })
    }

    /**
     * There is nothing on this screen to corroborate a sight-box with: the tree is unusable, or it is missing a piece and the wanted text ([text]) is not in the tree (counted as absent when no text is given).
     * An adequate tree, an inconclusive verdict, and an unreadable screen do not count: corroboration is still required.
     */
    private suspend fun nothingToCheckAgainst(text: String?): Boolean {
        val s = peekOrNull() ?: return false
        return when (TreeAdequacy.ofTree(s).quality) {
            TreeQuality.Unusable -> true
            TreeQuality.Partial -> text == null || s.elements.none { it.name.isNotBlank() && norm(text) in norm(it.name) }
            else -> false
        }
    }

    /**
     * Boxes the same image again: only if each box contains the other's center, the text seen agrees (both must have some), and the second box also passes a red-framed second-look confirmation does it count as corroboration.
     */
    private suspend fun secondLook(target: String, text: String?, first: Box, png: ByteArray, locatePrompt: String, confirmPrompt: String): Boolean {
        val box = parseBox(vision.ask(locatePrompt, if (text != null) "$target (label: \"$text\")" else target, listOf(png))) ?: return false
        val seenA = first.seen ?: return false
        val seenB = box.seen ?: return false
        if (disagree(seenA, seenB) || (text != null && disagree(seenB, text))) return false
        if (!contains(first, mid(box.l, box.r), mid(box.t, box.b)) || !contains(box, mid(first.l, first.r), mid(first.t, first.b))) return false
        val framed = vision.ops.drawFrame(png, intArrayOf(box.l, box.t, box.r, box.b))
        return YES.containsMatchIn(vision.ask(confirmPrompt.replace("{target}", target), "Yes or no?", listOf(framed)).trim())
    }

    private fun contains(b: Box, x: Int, y: Int) = x in b.l..b.r && y in b.t..b.b

    /** Hands the current screenshot and "what I want to do now" to the vision model and returns its description as-is. Throws [ScreenUnavailable] when no frame can be obtained, ModelError when the model fails. */
    suspend fun describe(task: String): String {
        val shot = capture().let { it as? Shot.Ok ?: throw (it as Shot.Missing).let { m -> ScreenUnavailable(m.reason, m.message) } }
        clearForVision()?.let { throw ScreenUnavailable(it.reason, it.message) }
        return vision.ask(DESCRIBE_PROMPT, "What I want to do now: $task", listOf(shot.png))
    }

    /**
     * Whether the current frame may be given to the vision model: null when it may; otherwise the reason
     * (protected_content, or vision_unavailable when the screen cannot be read).
     */
    internal suspend fun visionBlocker(): String? = clearForVision()?.reason

    private sealed interface Shot {
        class Ok(val png: ByteArray, val hash: Long) : Shot
        class Missing(val reason: String, val message: String) : Shot {
            fun asNotFound() = LocateResult.NotFound(reason, message)
        }
    }

    /**
     * Takes one screenshot. Nothing is captured when a screenshot-protected window is on screen (the capture would be black anyway, and things like payment pages must not leave the device);
     * when nothing can be captured, it distinguishes "no app open yet" from a temporary failure.
     */
    private suspend fun capture(): Shot {
        if (screen.isProtected()) return Shot.Missing("protected_content", m.protectedScreen)
        val png = screen.screenshotPng()
        if (png == null) {
            val s = peekOrNull()
            if (s?.captureError == ScreenDiagnosis.DISPLAY_CAPTURE_UNAVAILABLE) {
                return Shot.Missing(s.captureError, Feedback["display_capture_unavailable"])
            }
            val empty = s != null && s.packageName == null && s.elements.isEmpty() && !s.hasWindow
            return if (empty) Shot.Missing("no_app_open", m.noApp) else Shot.Missing("screenshot_unavailable", m.noShot)
        }
        // Look again after capturing: a protected page may have come up during the capture
        if (vision.ops.isMostlyBlack(png) && screen.isProtected()) return Shot.Missing("protected_content", m.protectedScreen)
        return Shot.Ok(png, vision.ops.aHash(png))
    }

    /**
     * Reads the screen once more before handing it to the vision model: protected pages are not sent, nor is an unreadable screen.
     * Payment pages are handled like ordinary pages. Null when it may be sent.
     */
    private suspend fun clearForVision(): Shot.Missing? {
        val s = peekOrNull() ?: return Shot.Missing("vision_unavailable", m.unchecked)
        if (s.captureError == ScreenDiagnosis.DISPLAY_CAPTURE_UNAVAILABLE) {
            return Shot.Missing(s.captureError, Feedback["display_capture_unavailable"])
        }
        if (s.protected) return Shot.Missing("protected_content", m.protectedScreen)
        return null
    }

    private suspend fun readElements(): List<UiElement>? = peekOrNull()?.takeIf { !it.protected }?.elements

    private suspend fun peekOrNull(): ScreenSnapshot? =
        try { screen.peek() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    /** The unique element whose name or resource ID (whitespace removed, lowercased) exactly equals [text]; nested or same-center elements count as one, and multiple distinct matches yield null. */
    private fun exactMatch(elements: List<UiElement>, text: String): UiElement? {
        val want = norm(text)
        val hits = elements.filter { e -> onScreen(e) && (norm(e.name) == want || e.resourceId?.let { norm(it) == want } == true) }
        val distinct = hits.filterIndexed { i, e -> hits.take(i).none { o -> center(o) == center(e) || nested(o, e) } }
        return distinct.singleOrNull()
    }

    /** The smallest element covering that point that takes up less than half the screen. */
    private fun elementAt(elements: List<UiElement>, x: Int, y: Int): UiElement? = elements.filter { e ->
        val b = e.bounds
        b.size == 4 && x in b[0]..b[2] && y in b[1]..b[3] && area(e) < 999L * 999 / 2
    }.minByOrNull(::area)

    private fun parseBox(reply: String): Box? {
        val t = reply.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (t == "null") return null
        if (Regex("bbox_2d").findAll(t).count() > 1 || FOUR_NUMBERS.findAll(t).count() > 1) return null
        val m = BBOX_2D.find(t) ?: BARE_BOX.find(t) ?: return null
        val (x1, y1, x2, y2) = m.groupValues.drop(1).map { it.toDouble() }
        if (listOf(x1, y1, x2, y2).any { it > 1000 } || x2 <= x1 || y2 <= y1) return null
        // A fractional 0–1 box is a different coordinate convention, so converting it is unreliable
        if (maxOf(x1, y1, x2, y2) <= 1) return null
        fun scale(v: Double) = (v * 999 / 1000).roundToInt()
        val seen = SEEN.find(t)?.groupValues?.get(1)?.let(::unescape)?.trim()?.take(MAX_SEEN)?.ifEmpty { null }
        return Box(scale(x1), scale(y1), scale(x2), scale(y2), seen)
    }

    private fun unescape(raw: String): String =
        try { (Json.parseToJsonElement("\"$raw\"") as JsonPrimitive).content } catch (e: Exception) { raw }

    private fun disagree(a: String, b: String): Boolean {
        val x = norm(a); val y = norm(b)
        return x.isNotEmpty() && y.isNotEmpty() && y !in x && x !in y
    }

    /** On screen and having area (screen reads pin off-screen elements to the edge, giving them zero area). */
    private fun onScreen(e: UiElement): Boolean {
        val b = e.bounds
        return b.size == 4 && b[0] >= 0 && b[1] >= 0 && b[2] <= 999 && b[3] <= 999 && b[2] > b[0] && b[3] > b[1]
    }

    private fun norm(s: String) = s.filterNot(Char::isWhitespace).lowercase()
    private fun mid(a: Int, b: Int) = ((a + b) / 2.0).roundToInt()
    private fun center(e: UiElement) = mid(e.bounds[0], e.bounds[2]) to mid(e.bounds[1], e.bounds[3])
    private fun area(e: UiElement): Long = (e.bounds[2] - e.bounds[0]).toLong() * (e.bounds[3] - e.bounds[1])

    private fun nested(a: UiElement, b: UiElement): Boolean {
        val p = a.bounds; val q = b.bounds
        return (p[0] <= q[0] && p[1] <= q[1] && p[2] >= q[2] && p[3] >= q[3]) || (q[0] <= p[0] && q[1] <= p[1] && q[2] >= p[2] && q[3] >= p[3])
    }
}
