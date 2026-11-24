package com.wangzan101.mika.cap.web

import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * One entry in the site registry: host name, last visit time, whether it is signed in inside Claire (set when the login is verified or when the page shows it is signed in,
 * cleared when the page shows it is signed out). [challengedAtMs] / [challenge]: the time and kind of the most recent CAPTCHA or access block hit on this site
 * (captcha / access_blocked), meaning the session on this site may already have been flagged by the site; it stops counting after [SiteRegistry.CHALLENGE_TTL_MS] or once that site's data is cleared.
 */
data class SiteEntry(
    val host: String,
    val lastVisitMs: Long,
    val loggedInByUser: Boolean,
    val challengedAtMs: Long? = null,
    val challenge: String? = null,
) {
    /** Still counts as "hit a block" at this moment ([now]). */
    fun challenged(now: Long): Boolean = challengedAtMs?.let { now - it < SiteRegistry.CHALLENGE_TTL_MS } == true
}

/**
 * Site registry: records the sites the Agent and the user visited in the built-in browser. The system APIs cannot list every site that has Cookies stored,
 * so this is the only source the settings screen's "site logins" can show. Stored in a JSON file in the app's private directory;
 * when the file is corrupted it is moved aside and kept (never overwritten with new content), and the table restarts from empty.
 */
class SiteRegistry(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val file = File(dir, FILE)
    private val sites = LinkedHashMap<String, SiteEntry>()
    private var corrupt = false
    private val _entries = MutableStateFlow<List<SiteEntry>>(emptyList())

    /** The same content as [list], updated on change, for the UI. */
    val entries: StateFlow<List<SiteEntry>> = _entries.asStateFlow()

    init {
        load()
        publish()
    }

    @Synchronized
    fun visited(host: String) {
        val h = normalize(host) ?: return
        sites[h] = sites[h]?.copy(lastVisitMs = clock()) ?: SiteEntry(h, clock(), false)
        save()
    }

    /** Login state observed on the page: record it when signed in, drop it when signed out (session expired), leave it alone when undetermined. Returns whether the record changed. */
    @Synchronized
    fun loginObserved(host: String, state: LoginState): Boolean {
        val h = normalize(host) ?: return false
        val want = when (state) { LoginState.SignedIn -> true; LoginState.SignedOut -> false; LoginState.Unknown -> return false }
        val cur = sites[h]
        if ((cur?.loggedInByUser ?: false) == want) return false
        sites[h] = (cur ?: SiteEntry(h, clock(), false)).copy(loggedInByUser = want)
        save()
        return true
    }

    /**
     * A CAPTCHA or access block stopped the task ([kind]: captcha / access_blocked): record the time and kind.
     * If the same kind was recorded within [CHALLENGE_REFRESH_MS], it is not rewritten.
     */
    @Synchronized
    fun challenged(host: String, kind: String) {
        val h = normalize(host) ?: return
        val cur = sites[h]
        if (cur?.challenge == kind && cur.challengedAtMs?.let { clock() - it < CHALLENGE_REFRESH_MS } == true) return
        sites[h] = (cur ?: SiteEntry(h, clock(), false)).copy(challengedAtMs = clock(), challenge = kind)
        save()
    }

    /** Sites that still count as having hit a block right now. */
    @Synchronized
    fun challengedSites(): Set<String> {
        val now = clock()
        return sites.values.filter { it.challenged(now) }.map { it.host }.toSet()
    }

    @Synchronized
    fun markLoggedIn(host: String) {
        val h = normalize(host) ?: return
        sites[h] = (sites[h] ?: SiteEntry(h, clock(), false)).copy(loggedInByUser = true)
        save()
    }

    /** Signed-in ones first, then by most recent visit. */
    @Synchronized
    fun list(): List<SiteEntry> = sorted()

    @Synchronized
    fun remove(host: String) {
        val h = normalize(host) ?: return
        if (sites.remove(h) != null) save()
    }

    @Synchronized
    fun clear() {
        sites.clear()
        save()
    }

    private fun sorted() = sites.values.sortedWith(compareByDescending<SiteEntry> { it.loggedInByUser }.thenByDescending { it.lastVisitMs })

    private fun publish() {
        _entries.value = sorted()
    }

    private fun load() {
        if (!file.isFile) return
        val parsed = try {
            (Json.parseToJsonElement(file.readText()) as? JsonArray)?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val host = (o["host"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::normalize) ?: return@mapNotNull null
                SiteEntry(
                    host, (o["lastVisitMs"] as? JsonPrimitive)?.longOrNull ?: 0L, (o["loggedIn"] as? JsonPrimitive)?.booleanOrNull == true,
                    (o["challengedAt"] as? JsonPrimitive)?.longOrNull, (o["challenge"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                )
            }
        } catch (e: SerializationException) {
            null
        }
        if (parsed == null) {
            corrupt = true
            return
        }
        parsed.forEach { sites[it.host] = it }
    }

    private fun save() {
        dir.mkdirs()
        if (corrupt) {
            // Move the corrupted file aside and keep it, never overwrite it with new content
            file.renameTo(File(dir, "$FILE.corrupt-${clock()}"))
            corrupt = false
        }
        val json = JsonArray(sites.values.map {
            buildJsonObject {
                put("host", it.host); put("lastVisitMs", it.lastVisitMs); put("loggedIn", it.loggedInByUser)
                it.challengedAtMs?.let { t -> put("challengedAt", t) }
                it.challenge?.let { k -> put("challenge", k) }
            }
        }).toString()
        // Write a temp file first and then rename it, so being killed mid-write does not leave half a file
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            file.writeText(json)
            tmp.delete()
        }
        publish()
    }

    companion object {
        /** The block marker is kept this long (by then the site has most likely stopped watching this session). */
        const val CHALLENGE_TTL_MS = 7L * 24 * 60 * 60 * 1000
        /** Hitting the same kind of block again within this time does not rewrite the record. */
        const val CHALLENGE_REFRESH_MS = 10L * 60 * 1000
        /** Takeover reasons that count as "the site has flagged this session". */
        val CHALLENGE_KINDS = setOf("captcha", "access_blocked")
        private const val FILE = "sites.json"

        /** Normalize host names to lowercase with www. removed; a full URL passed in is also accepted. Anything that does not look like a host name is not recorded. */
        private fun normalize(raw: String): String? {
            val s = raw.trim()
            if (s.isEmpty()) return null
            return WebUrlPolicy.host(if (s.contains("://")) s else "https://$s")?.takeIf { h -> h.none { it.isWhitespace() } }
        }
    }
}
