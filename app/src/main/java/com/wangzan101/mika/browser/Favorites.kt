package com.wangzan101.mika.browser

import com.wangzan101.mika.cap.web.SiteEntry
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** One tile in "frequently used sites": the host name opened when tapped, and whether it is logged in in Claire (any host name of this site being logged in counts). */
data class FavoriteSite(val host: String, val loggedIn: Boolean)

/** The user's changes to "frequently used sites": the ones added manually ([pinned], in order of addition) and the ones removed ([hidden]). */
data class FavoriteEdits(val pinned: List<String> = emptyList(), val hidden: Set<String> = emptySet())

/**
 * "Frequently used sites" on the browser page: sites added by the user come first, followed by the sites from the site registry (visited by the Agent or the user; logged-in ones first).
 * Each site (each registrable domain) occupies only one tile, and tapping it opens the shortest host name among them.
 *
 * Only the user's changes are recorded and the site registry is left untouched: removing from the frequently used list merely hides the site, while the login state, cookies and the registry used by the Agent stay as they are
 * (logging out is done on the settings page under "site logins"); adding it back shows it again.
 * Stored in a JSON file in the app's private directory; if the file is corrupted, it restarts from an empty list.
 */
class BrowserFavorites(dir: File) {
    private val file = File(dir, FILE)
    private val _edits = MutableStateFlow(load())

    val edits: StateFlow<FavoriteEdits> = _edits.asStateFlow()

    /** Adds to the frequently used list: appended after the manually added ones; a site that was removed before is shown again. */
    @Synchronized
    fun add(host: String) {
        val h = norm(host) ?: return
        val site = siteKey(h)
        val e = _edits.value
        save(FavoriteEdits(e.pinned.filter { siteKey(it) != site } + h, e.hidden - site))
    }

    /** Removes from the frequently used list: manually added ones are dropped, ones coming from the registry are no longer shown. */
    @Synchronized
    fun remove(host: String) {
        val h = norm(host) ?: return
        val site = siteKey(h)
        val e = _edits.value
        save(FavoriteEdits(e.pinned.filter { siteKey(it) != site }, e.hidden + site))
    }

    private fun load(): FavoriteEdits {
        if (!file.isFile) return FavoriteEdits()
        return try {
            val o = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return FavoriteEdits()
            fun list(k: String) = (o[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(::norm) }
            FavoriteEdits(list("pinned").distinct(), list("hidden").toSet())
        } catch (e: SerializationException) {
            FavoriteEdits()
        }
    }

    private fun save(e: FavoriteEdits) {
        _edits.value = e
        file.parentFile?.mkdirs()
        val json = buildJsonObject {
            put("pinned", JsonArray(e.pinned.map(::JsonPrimitive)))
            put("hidden", JsonArray(e.hidden.sorted().map(::JsonPrimitive)))
        }.toString()
        // Write a temporary file first and then rename it, so being killed halfway cannot leave half a file
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            file.writeText(json)
            tmp.delete()
        }
    }

    companion object {
        /** The grid holds at most this many site tiles (plus one "＋" tile, exactly three rows of four). */
        const val LIMIT = 11
        private const val FILE = "browser_favorites.json"

        private fun norm(host: String): String? {
            val s = host.trim()
            if (s.isEmpty()) return null
            return WebUrlPolicy.host(if ("://" in s) s else "https://$s")
        }

        /** The marker for a site: its registrable domain, or the host name itself when that cannot be determined. */
        fun siteKey(host: String): String = WebUrlPolicy.registrable(host) ?: host

        /** Builds the grid: manually added ones first (in order of addition), then in registry order; one tile per site, removed ones are not shown, at most [limit] tiles. */
        fun combine(edits: FavoriteEdits, registry: List<SiteEntry>, limit: Int = LIMIT): List<FavoriteSite> {
            val bySite = registry.groupBy { siteKey(it.host) }
            fun loggedIn(site: String) = bySite[site].orEmpty().any { it.loggedInByUser }
            val out = LinkedHashMap<String, FavoriteSite>()
            for (h in edits.pinned) {
                val site = siteKey(h)
                if (site !in out) out[site] = FavoriteSite(h, loggedIn(site))
            }
            for (e in registry) {
                val site = siteKey(e.host)
                if (site in out || site in edits.hidden) continue
                val host = bySite[site].orEmpty().minBy { it.host.length }.host
                out[site] = FavoriteSite(host, loggedIn(site))
            }
            return out.values.take(limit)
        }

        /** Whether the site that [host] belongs to is logged in in Claire (any host name of this site in the registry is logged in). */
        fun loggedIn(registry: List<SiteEntry>, host: String): Boolean {
            val site = siteKey(host)
            return registry.any { it.loggedInByUser && siteKey(it.host) == site }
        }

        /** Whether the site that [host] belongs to is present in [sites]. */
        fun contains(sites: List<FavoriteSite>, host: String): Boolean {
            val site = siteKey(host)
            return sites.any { siteKey(it.host) == site }
        }
    }
}
