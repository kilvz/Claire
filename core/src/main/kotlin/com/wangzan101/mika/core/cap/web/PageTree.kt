package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.screen.uistate.normalize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.max
import kotlin.math.roundToInt
import com.wangzan101.mika.core.screen.uistate.WebInfo

/**
 * Page script output → ScreenSnapshot. The input looks like
 * `{"els":[{"r":"button","n":"Search","x":120,"y":40,"w":80,"h":32,"id":"go","c":1,"e":0,"s":0}],"vw":412,"vh":915,"dpr":2.625,"trunc":false,"mut":17}`,
 * coordinates in CSS pixels. center is converted to WebView pixels (first by viewportW / vw, otherwise by dpr) and clamped into the viewport; bounds are normalized by the viewport to 0–999, the same coordinate system as on the phone.
 * There are also optional flags: `ph`=name is the input's placeholder rather than its entered content, `f`=it holds input focus; a file picker's `r` is "file".
 * refs are numbered by array position: a broken entry still takes up a number, so they line up with the numbering table in the page script.
 * Broken input yields an empty list and throws no exception: a failed screen read should not crash the whole turn.
 */
object PageTree {
    fun parse(json: String, viewportW: Int, viewportH: Int, snapshotId: String, info: WebInfo): ScreenSnapshot {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
        val cssW = root.num("vw")?.takeIf { it > 0 }
        val scale = cssW?.takeIf { viewportW > 0 }?.let { viewportW / it }
            ?: root.num("dpr")?.takeIf { it > 0 }
            ?: 1.0
        val elements = (root?.get("els") as? JsonArray).orEmpty()
            .mapIndexedNotNull { i, e -> (e as? JsonObject)?.let { element(i + 1, it, scale, viewportW, viewportH) } }
        return ScreenSnapshot(
            snapshotId = snapshotId,
            packageName = null,
            elements = elements,
            width = viewportW,
            height = viewportH,
            web = info.copy(truncated = root.flag("trunc")),
        )
    }

    private fun element(n: Int, o: JsonObject, scale: Double, w: Int, h: Int): UiElement {
        val l = px(o.num("x"), scale)
        val t = px(o.num("y"), scale)
        val r = px((o.num("x") ?: 0.0) + (o.num("w") ?: 0.0), scale)
        val b = px((o.num("y") ?: 0.0) + (o.num("h") ?: 0.0), scale)
        val id = o.str("id")?.takeIf { it.isNotBlank() }
        return UiElement(
            ref = "@e$n",
            role = o.str("r") ?: "text",
            name = o.str("n") ?: "",
            centerX = ((l + r) / 2).coerceIn(0, max(0, w - 1)),
            centerY = ((t + b) / 2).coerceIn(0, max(0, h - 1)),
            bounds = listOf(normalize(l, w), normalize(t, h), normalize(r, w), normalize(b, h)),
            resourceId = id?.let { if (it.startsWith("#")) it else "#$it" },
            clickable = o.flag("c"),
            editable = o.flag("e"),
            scrollable = o.flag("s"),
            hint = o.flag("ph"),
            focused = o.flag("f"),
            secret = o.flag("pw"),
            placeholder = o.str("ph")?.takeIf { it.isNotBlank() },
            searchField = o.flag("sr"),
            keyShortcuts = o.str("k")?.split(' ')?.filter(String::isNotBlank)?.take(MAX_SHORTCUTS).orEmpty(),
        )
    }

    /** Numbers from the page may be huge or infinite: clamp first, then round, so rounding does not overflow and the center does not end up far off the map. */
    private fun px(css: Double?, scale: Double): Int = ((css ?: 0.0) * scale).coerceIn(-PX_LIMIT, PX_LIMIT).roundToInt()

    private const val PX_LIMIT = 1e6
    private const val MAX_SHORTCUTS = 8

    private fun JsonObject?.num(key: String): Double? =
        (this?.get(key) as? JsonPrimitive)?.doubleOrNull?.let { if (it.isFinite()) it.coerceIn(-PX_LIMIT, PX_LIMIT) else 0.0 }

    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Both 0/1 and true/false are accepted. */
    private fun JsonObject?.flag(key: String): Boolean {
        val p = this?.get(key) as? JsonPrimitive ?: return false
        return p.booleanOrNull ?: ((p.intOrNull ?: 0) != 0)
    }
}
