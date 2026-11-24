package com.wangzan101.mika.core.screen.user

import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.TreeAdequacy
import com.wangzan101.mika.core.screen.uistate.TreeQuality
import com.wangzan101.mika.core.screen.uistate.VisibleText
import com.wangzan101.mika.core.screen.uistate.VisibleTextLine
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.screen.uistate.normalize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Where the text on the user's screen comes from: first the foreground app's accessibility tree; when the tree is inadequate (the same judgment as task screen reads, [TreeAdequacy]) the on-frame text is then recognized (OCR),
 * filling in only what the tree lacks and dropping misread lines. Each line carries its center point, so even a model that cannot see the image can tell left from right (the other party's bubbles vs. your own in a chat).
 */
object UserScreenText {
    /** At most this many lines per screen, and this many characters per line: enough to make out the screen without inflating the context. */
    const val MAX_LINES = 120
    const val MAX_CHARS = 200

    /** The tree is not clearly adequate: the on-frame text must be recognized as well. Protected pages have a black frame, so nothing is recognized there. */
    fun needsOcr(s: ScreenSnapshot): Boolean = TreeAdequacy.ofTree(s).quality.let { it != TreeQuality.Adequate && it != TreeQuality.Protected }

    /**
     * The on-screen text, top to bottom and left to right within a row. Named tree elements (password fields excluded) are merged with lines from [ocr] that the tree lacks;
     * outside [top]/[bottom] (0–999) are the status bar and navigation bar strips, and text recognized there (time, battery) is not wanted. The second value says whether recognized text was used.
     */
    fun lines(s: ScreenSnapshot, ocr: List<VisibleTextLine>?, top: Int = 0, bottom: Int = 999): Pair<List<ScreenLine>, Boolean> {
        val named = s.elements.filter { !it.secret && it.name.isNotBlank() }
        // Element centers are in pixels while bounds and recognized text are 0–999: convert everything to 0–999 before merging and sorting
        val tree = named.filterNot { joinsChildren(it, named) }
            .map { ScreenLine(clip(it.name), normalize(it.centerX, s.width), normalize(it.centerY, s.height)) }
        val extra = ocr?.let { VisibleText.missing(s, VisibleText.legible(it)) }.orEmpty()
            .map { ScreenLine(clip(it.text), (it.bounds[0] + it.bounds[2]) / 2, (it.bounds[1] + it.bounds[3]) / 2) }
            .filter { it.y in top..bottom }
        val all = (tree + extra).distinctBy { Triple(it.text, it.x / 20, it.y / 20) }
            .sortedWith(compareBy({ it.y / ROW }, { it.x }))
            .take(MAX_LINES)
        return all to extra.any { it in all }
    }

    /**
     * [e] merely concatenates the text segments boxed inside it (a settings row, the whole block of one message): after removing those segments from its name, no characters remain.
     * In that case the inner segments are kept, each with its own position; when its name has text of its own it is kept as well.
     */
    private fun joinsChildren(e: UiElement, all: List<UiElement>): Boolean {
        val inner = all.filter { it !== e && inside(it.bounds, e.bounds) && area(it.bounds) < area(e.bounds) }
        if (inner.isEmpty()) return false
        var rest = e.name
        inner.map { it.name.trim() }.sortedByDescending { it.length }.forEach { rest = rest.replace(it, "") }
        return rest.none { it.isLetterOrDigit() }
    }

    private fun inside(a: List<Int>, b: List<Int>) = a.size == 4 && b.size == 4 && a[0] >= b[0] && a[1] >= b[1] && a[2] <= b[2] && a[3] <= b[3]
    private fun area(b: List<Int>) = if (b.size == 4) (b[2] - b[0]).toLong() * (b[3] - b[1]) else 0L

    /** Same-row tolerance (0–999): centers whose vertical offset is under this count as the same row and are ordered left to right. */
    private const val ROW = 12

    private fun clip(t: String): String {
        val one = t.replace('\n', ' ').trim()
        return if (one.length <= MAX_CHARS) one else one.take(MAX_CHARS) + "…"
    }
}

/** The screen description stored as JSON (kept together with the screenshot); returns null when it cannot be parsed. */
object UserScreenJson {
    fun encode(s: UserScreen): String = buildJsonObject {
        put("id", s.id); put("takenAt", s.takenAt)
        s.packageName?.let { put("package", it) }; s.appLabel?.let { put("label", it) }
        if (s.blocked) put("blocked", true)
        if (s.fromOcr) put("ocr", true)
        put("lines", JsonArray(s.lines.map { l -> buildJsonObject { put("t", l.text); put("x", l.x); put("y", l.y) } }))
    }.toString()

    fun decode(text: String): UserScreen? = runCatching {
        val o = Json.parseToJsonElement(text) as JsonObject
        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        UserScreen(
            id = str("id") ?: return null,
            takenAt = (o["takenAt"] as? JsonPrimitive)?.longOrNull ?: return null,
            packageName = str("package"), appLabel = str("label"),
            blocked = (o["blocked"] as? JsonPrimitive)?.booleanOrNull == true,
            lines = (o["lines"] as? JsonArray).orEmpty().mapNotNull { e ->
                val l = e as? JsonObject ?: return@mapNotNull null
                ScreenLine((l["t"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                    (l["x"] as? JsonPrimitive)?.intOrNull ?: 0, (l["y"] as? JsonPrimitive)?.intOrNull ?: 0)
            },
            fromOcr = (o["ocr"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }.getOrNull()
}
