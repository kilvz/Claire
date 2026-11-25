package com.wangzan101.mika.chat.ui

import android.content.Context
import org.json.JSONObject
import java.util.Locale
import kotlin.random.Random

/**
 * The example library for the two scrolling rows at the bottom of the empty chat (`assets/chat/empty_ideas.json`). One set per UI language:
 * Chinese UI uses the set of Chinese apps (Simplified and Traditional variants), other languages use the international-app set, with text in that language.
 * Each entry is bound to a specific app package and only shows when that app is installed, using that app's own icon.
 */
internal object IdeaLibrary {
    private const val ASSET_PATH = "chat/empty_ideas.json"

    /** One example: the scene (food, shopping, …, used only to spread similar ones apart), the packages that can handle it (take the first installed one in order), and the text. */
    data class Entry(val scene: String, val packages: List<String>, val text: String)

    @Volatile private var cached: JSONObject? = null

    fun load(context: Context, locale: Locale): List<Entry> = runCatching {
        val root = cached ?: JSONObject(context.assets.open(ASSET_PATH).use { it.readBytes().toString(Charsets.UTF_8) }).also { cached = it }
        val (set, key) = setFor(locale)
        val list = root.getJSONArray(set)
        (0 until list.length()).mapNotNull { i ->
            val e = list.getJSONObject(i)
            val texts = e.getJSONObject("text")
            val text = texts.optString(key).ifEmpty { texts.optString(if (set == "zh") "zh-Hans" else "en") }
            val pkgs = e.getJSONArray("packages").let { a -> (0 until a.length()).map(a::getString) }
            text.takeIf { it.isNotEmpty() }?.let { Entry(e.getString("scene"), pkgs, it) }
        }
    }.getOrDefault(emptyList())

    /** UI language → (which set, which text variant). Chinese is split into Simplified/Traditional by script or region; the old Indonesian code in is treated as id. */
    internal fun setFor(locale: Locale): Pair<String, String> {
        val lang = locale.language.let { if (it == "in") "id" else it }
        if (lang == "zh") {
            val hant = locale.script == "Hant" || (locale.script.isEmpty() && locale.country in setOf("TW", "HK", "MO"))
            return "zh" to (if (hant) "zh-Hant" else "zh-Hans")
        }
        return "intl" to lang
    }

    /**
     * Arrange into two rows: shuffle within each scene, then take one from each scene in turn (so similar ones are not adjacent),
     * then give one to the first row and one to the second. [random] changes each time a conversation opens, so the order stays fresh.
     */
    internal fun <T> arrange(items: List<T>, scene: (T) -> String, random: Random): List<List<T>> {
        val groups = items.groupBy(scene).values.map { it.shuffled(random).toMutableList() }.shuffled(random)
        val woven = buildList {
            while (groups.any { it.isNotEmpty() }) groups.forEach { g -> if (g.isNotEmpty()) add(g.removeAt(0)) }
        }
        return listOf(woven.filterIndexed { i, _ -> i % 2 == 0 }, woven.filterIndexed { i, _ -> i % 2 == 1 }).filter { it.isNotEmpty() }
    }
}
