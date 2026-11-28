package com.wangzan101.mika.settings.ui

import androidx.annotation.StringRes
import java.text.Normalizer

/** A model subpage that search can jump to. */
enum class SettingsModelRoute { Usage, Speech, Voice, CallMode, VoiceServices, Image, Video }

/** Where a search result goes when opened: a settings subpage (optionally focused on one item), or a page or dialog opened outside settings. */
internal sealed interface SettingsDestination {
    data class Page(val page: SettingsPage, val item: String? = null) : SettingsDestination
    data object Models : SettingsDestination
    data class ModelRoute(val route: SettingsModelRoute) : SettingsDestination
    data object Plans : SettingsDestination
    data object Files : SettingsDestination
    data object Personal : SettingsDestination
    data object Personas : SettingsDestination
    data object Memory : SettingsDestination
    data object AutoArchive : SettingsDestination
    data object Proxy : SettingsDestination
    data object WebSearch : SettingsDestination
    data object Quit : SettingsDestination
    data object About : SettingsDestination
}

/** An entry that exists only when some capability or switch is present; it matches the visibility condition of the corresponding settings row one-to-one. */
internal enum class SettingsRequirement {
    /** The system version supports the built-in assistant ("Recovery and repair", "Compatibility self-check"). */
    Recovery,
    /** This phone supports the files and compute environment. */
    Sandbox,
    /** External MCP server settings are wired in. */
    McpServers,
    /** su is available on the phone. */
    Root,
    /** Developer options present only in debug and performance-test builds. */
    Developer,
    /** "Personal assistant as home screen" is on. */
    PersonalStart,
    Personas,
    Memory,
    /** Subpage routes under Models are wired in. */
    ModelSubPages,
    /** The "concurrent tasks" experiment is on. */
    ParallelTasks,
    /** Live task notifications on Android 16+. */
    LiveTaskUpdates,
}

/**
 * One search entry: a stable [id], the [destination] it opens, string resources for its title and its location (group name, parent page),
 * plus synonym resources for better matching ([aliases]). Only resource ids are stored; text is resolved in the current UI language when showing or matching, so no rebuild is needed on a language change.
 */
internal data class SettingsSearchEntry(
    val id: String,
    val destination: SettingsDestination,
    @StringRes val title: Int,
    val path: List<Int> = emptyList(),
    val requires: SettingsRequirement? = null,
    val aliases: List<Int> = emptyList(),
)

/** One entry rendered in the current language: [path] is the "group › parent page" line. */
internal data class SettingsSearchItem(
    val entry: SettingsSearchEntry,
    val title: String,
    val path: String,
    val aliases: List<String> = emptyList(),
)

/** One match: the ranges to highlight in the title and location (indices into the source text). */
internal data class SettingsSearchHit(
    val item: SettingsSearchItem,
    val titleMatches: List<IntRange>,
    val pathMatches: List<IntRange>,
)

/**
 * Matching and ranking: the query is split on whitespace and every word must appear in the title, location or synonyms.
 * Ranking looks at where each word lands: start of the title, start of a word in the title, middle of the title, only in the location, only in synonyms, progressively later;
 * ties go to the shorter title, then to registration order.
 * Before comparing, case and Latin diacritics are stripped (Vietnamese, Turkish, Spanish and Portuguese are found without tone marks); other scripts compare as-is.
 */
internal object SettingsSearch {
    fun search(items: List<SettingsSearchItem>, query: String): List<SettingsSearchHit> {
        val words = fold(query).split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return items.mapNotNull { item -> score(item, words) }
            .sortedWith(compareBy<Pair<SettingsSearchHit, Int>> { it.second }.thenBy { it.first.item.title.length })
            .map { it.first }
    }

    private fun score(item: SettingsSearchItem, words: List<String>): Pair<SettingsSearchHit, Int>? {
        val title = fold(item.title)
        val path = fold(item.path)
        val aliases = item.aliases.map(::fold)
        var total = 0
        for (w in words) {
            val at = title.indexOf(w)
            total += when {
                at == 0 -> 0
                at > 0 && wordStartIn(title, w) -> 1
                at > 0 -> 2
                path.contains(w) -> 3
                aliases.any { it.contains(w) } -> 4
                else -> return null
            }
        }
        return SettingsSearchHit(item, ranges(title, words), ranges(path, words)) to total
    }

    private fun wordStartIn(text: String, word: String): Boolean {
        var at = text.indexOf(word)
        while (at >= 0) {
            if (at == 0 || !text[at - 1].isLetterOrDigit()) return true
            at = text.indexOf(word, at + 1)
        }
        return false
    }

    /** Every occurrence of [words] in [text], merging overlapping or adjacent ones into one range. */
    private fun ranges(text: String, words: List<String>): List<IntRange> {
        val found = buildList {
            for (w in words) {
                var at = text.indexOf(w)
                while (at >= 0) {
                    add(at until at + w.length)
                    at = text.indexOf(w, at + 1)
                }
            }
        }.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        for (r in found) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + 1) merged[merged.lastIndex] = last.first..maxOf(last.last, r.last)
            else merged += r
        }
        return merged
    }

    /** Folds character by character to lowercase and strips Latin diacritics; the length is unchanged so match indices apply directly to the original text. */
    fun fold(text: String): String = buildString(text.length) { for (c in text) append(foldChar(c)) }

    private fun foldChar(c: Char): Char {
        if (c == 'đ' || c == 'Đ') return 'd'
        if (c == 'ı') return 'i'
        val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
        val stripped = if (base.length > 1 && base[0].code < LATIN_END && base.drop(1).all { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }) base[0] else c
        return stripped.lowercaseChar()
    }

    private const val LATIN_END = 0x0250
    private val WHITESPACE = Regex("\\s+")
}
