package com.wangzan101.mika.browser

import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import com.wangzan101.mika.core.workspace.AtomicFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * How a site looks on the browser page: its name ([name], null when it cannot be read) and where it came from ([rank], see [SiteNames]),
 * plus when the site icon was stored ([iconAtMs], null when there is no icon; the UI uses it to decide whether to reload the icon file).
 */
data class SiteLook(val name: String?, val rank: Int, val iconAtMs: Long?)

/** Everything the UI needs to draw a site: the name, the first character of the name, the background color (used when there is no icon), the icon file and its version. */
data class SiteFace(val host: String, val name: String, val initial: String, val color: Long, val icon: File?, val iconAtMs: Long)

/**
 * Site names and icons: recorded when the user opens a page in the browser page (the name comes from the page, the icon from the favicon received by the WebView), for use by "frequently used sites" and "recently opened".
 * Recorded by host name and also on the registrable domain (when nothing better is stored there yet), so pages of other subdomains of the same site are recognized too.
 * Names are stored in a JSON file in the app's private directory, and icons are PNGs in the neighboring icons directory; if the file is corrupted, it restarts from an empty map.
 */
class SiteLooks(dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val file = File(dir, FILE)
    private val iconDir = File(dir, ICONS)
    private val _entries = MutableStateFlow(load())

    /** Host name → look. */
    val entries: StateFlow<Map<String, SiteLook>> = _entries.asStateFlow()

    /** Records the name given by the page: replaces the existing one only when its provenance is not worse. */
    @Synchronized
    fun named(host: String, name: String, rank: Int) {
        val n = name.trim().takeIf { it.isNotEmpty() } ?: return
        var map = _entries.value
        keys(host).forEachIndexed { i, h ->
            val cur = map[h]
            // For the host name itself: a new name that is equally reliable also replaces it; for the registrable domain: only when more reliable (pages of other subdomains must not steal the home page's name)
            val replace = cur?.name == null || (if (i == 0) rank >= cur.rank else rank > cur.rank)
            if (replace && cur?.name != n) map = map + (h to SiteLook(n, rank, cur?.iconAtMs))
        }
        if (map != _entries.value) save(map)
    }

    /**
     * Asked before an icon is written to [iconFile]: which host names it should be stored under. It is stored when the host name itself has no icon, or when the stored one is older than [ICON_REFRESH_MS];
     * and a copy is also stored for the registrable domain when that has no icon yet. An icon is received on every page open, so this avoids rewriting it every time.
     */
    @Synchronized
    fun iconTargets(host: String): List<String> = keys(host).filterIndexed { i, h ->
        val at = _entries.value[h]?.iconAtMs
        at == null || (i == 0 && clock() - at >= ICON_REFRESH_MS)
    }

    /** The icon file for this host name (it may not exist yet). */
    fun iconFile(host: String): File = File(iconDir, "${host.lowercase()}.png")

    /** The icon has been written to [iconFile]. */
    @Synchronized
    fun iconSaved(host: String) {
        val h = host.lowercase()
        val cur = _entries.value[h]
        save(_entries.value + (h to SiteLook(cur?.name, cur?.rank ?: 0, clock())))
    }

    /** The look needed to draw this site: look up the host name itself first, then the registrable domain; when neither exists, derive the name and background color from the host name. */
    fun face(host: String): SiteFace = faceOf(host, _entries.value, ::iconFile)

    @Synchronized
    fun clear() {
        iconDir.listFiles()?.forEach { it.delete() }
        save(emptyMap())
    }

    private fun load(): Map<String, SiteLook> {
        if (!file.isFile) return emptyMap()
        return try {
            (Json.parseToJsonElement(file.readText()) as? JsonObject)?.mapNotNull { (h, e) ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val rank = (o["rank"] as? JsonPrimitive)?.intOrNull ?: 0
                // Names derived earlier from another page's title may be wrong (see SiteNames): drop them and wait for the home page or a declared site name to name it again
                val name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { rank >= SiteNames.RANK_HOME_TITLE }
                h to SiteLook(name, if (name == null) 0 else rank, (o["iconAt"] as? JsonPrimitive)?.longOrNull)
            }?.toMap().orEmpty()
        } catch (e: SerializationException) {
            emptyMap()
        }
    }

    private fun save(map: Map<String, SiteLook>) {
        _entries.value = map
        file.parentFile?.mkdirs()
        val json = JsonObject(map.mapValues { (_, l) ->
            buildJsonObject {
                l.name?.let { put("name", it) }
                put("rank", l.rank)
                l.iconAtMs?.let { put("iconAt", it) }
            }
        }).toString()
        // Write a temp file first, then rename it, so a kill halfway through does not leave half a file
        AtomicFiles.write(file, json, durable = false, move = AtomicFiles.Move.Rename, fallback = AtomicFiles.Fallback.WriteDirect)
    }

    companion object {
        private const val FILE = "browser_sites.json"
        private const val ICONS = "browser_icons"
        /** An icon for the same host name is re-stored only after this long (so a site's new icon is picked up). */
        const val ICON_REFRESH_MS = 24L * 60 * 60 * 1000

        /** The host name itself plus its registrable domain (omitted when they are the same). */
        fun keys(host: String): List<String> {
            val h = host.trim().lowercase().removePrefix("www.")
            if (h.isEmpty()) return emptyList()
            val site = WebUrlPolicy.registrable(h)
            return if (site == null || site == h) listOf(h) else listOf(h, site)
        }

        fun faceOf(host: String, looks: Map<String, SiteLook>, iconFile: (String) -> File?): SiteFace {
            val found = keys(host).mapNotNull { looks[it] }
            val name = found.firstNotNullOfOrNull { it.name } ?: SiteNames.fallback(host)
            val iconKey = keys(host).firstOrNull { looks[it]?.iconAtMs != null }
            return SiteFace(
                host, name, SiteNames.initial(name), SiteTiles.color(host),
                iconKey?.let(iconFile), iconKey?.let { looks[it]?.iconAtMs } ?: 0L,
            )
        }
    }
}

/**
 * Where a site's name comes from; the more reliable, the higher [rank]: the site name declared by the page (og:site_name, application-name) is the most reliable;
 * the home page's title comes second (it usually starts with the site name). Titles of other pages are not used for naming: which segment holds the site name is uncertain, and on login or error pages the
 * last segment is often a page name such as a unified-login title, which would give the site the wrong name. When neither is available, a name is derived from the host name.
 */
object SiteNames {
    const val RANK_DECLARED = 3
    /** Names with a provenance lower than this (recorded earlier from another page's title) are no longer used and are dropped when loading (see [SiteLooks]). */
    const val RANK_HOME_TITLE = 2
    /** Names longer than this many characters are truncated (a grid tile cannot fit more). */
    const val MAX_LEN = 12

    /** Separators commonly used between the site name and the page name in titles: a dash or colon surrounded by spaces, or a vertical bar, underscore or middle dot. */
    private val SEPARATORS = Regex("""\s+[-–—:：]\s+|\s*[|｜_·•]\s*""")

    /** Picks a name and its provenance from what was read on the page: the declared site name, or the first segment of the home page title; null when nothing can be picked. */
    fun pick(declared: String?, title: String?, homePage: Boolean): Pair<String, Int>? {
        clean(declared)?.let { return it to RANK_DECLARED }
        if (!homePage) return null
        val first = title?.split(SEPARATORS)?.firstNotNullOfOrNull(::clean) ?: return null
        return first to RANK_HOME_TITLE
    }

    /** Read-only script that reads the site name and title in the page's main frame; the result is JSON of {site, title} (wrapped in an extra string layer by evaluateJavascript). */
    val SCRIPT = """(function(){try{function m(s){var e=document.querySelector(s);return e&&e.content?String(e.content):'';}
return JSON.stringify({site:m('meta[property="og:site_name"]')||m('meta[name="application-name"]')||m('meta[name="apple-mobile-web-app-title"]'),title:String(document.title||'')});}catch(x){return null;}})()"""

    /** Parses the result of [SCRIPT]: (declared site name, title); null when it cannot be read. */
    fun parse(raw: String?): Pair<String?, String?>? {
        val inner = runCatching { (Json.parseToJsonElement(raw ?: return null) as? JsonPrimitive)?.takeIf { it.isString }?.content }.getOrNull() ?: return null
        val o = runCatching { Json.parseToJsonElement(inner) as? JsonObject }.getOrNull() ?: return null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        return str("site") to str("title")
    }

    /** Counted as the home page when the path is empty or /. */
    fun isHomePage(url: String): Boolean {
        val path = runCatching { java.net.URI(url.trim()).rawPath }.getOrNull().orEmpty()
        return path.isEmpty() || path == "/"
    }

    /** When no name was read: the registrable domain without its suffix (shop.example.com → example); IP addresses and the like are kept as-is. */
    fun fallback(host: String): String {
        val h = host.trim().lowercase().removePrefix("www.")
        if ('.' !in h || h.startsWith("[") || h.all { it.isDigit() || it == '.' }) return h
        WebUrlPolicy.registrable(h)?.let { return it.substringBefore('.') }
        return h.substringBeforeLast('.')
    }

    /** The first character of the name (uppercased for Latin script). */
    fun initial(name: String): String {
        val cp = name.trim().codePointAt0() ?: return "?"
        return String(Character.toChars(cp)).uppercase()
    }

    private fun String.codePointAt0(): Int? = if (isEmpty()) null else codePointAt(0)

    private fun clean(s: String?): String? {
        val t = s?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (t.length > MAX_LEN) t.take(MAX_LEN).trimEnd() else t
    }
}

/** The background color of a site tile when there is no icon: one is picked deterministically from the host name (ignoring www), so the same site always gets the same one. */
object SiteTiles {
    /** Colors (ARGB) that keep white text readable in both light and dark themes. */
    val PALETTE = listOf(
        0xFFE5533D, 0xFFF08A24, 0xFFC9A100, 0xFF2E9E5B, 0xFF16A0A0,
        0xFF2F7DE1, 0xFF5B5BD6, 0xFF9A4FD0, 0xFFD6457F, 0xFF6B7280,
    )

    fun color(host: String): Long {
        val key = host.trim().lowercase().removePrefix("www.")
        // FNV-1a: even similar host names stay distinguishable, and the result is the same on every run
        var h = 0x811C9DC5.toInt()
        for (ch in key) h = (h xor ch.code) * 0x01000193
        return PALETTE[Math.floorMod(h, PALETTE.size)]
    }
}
