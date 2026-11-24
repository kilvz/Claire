package com.wangzan101.mika.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * Whether the browser page uses mobile or desktop web pages:
 * - The user's own browsing defaults to mobile; sites switched to desktop under "More" are recorded in [UserSiteModes] and keep using desktop next time;
 * - During "log in for Claire" (opening a site that is not logged in yet from the frequently used sites) the whole tab follows the identity Claire uses when working ([agentMobile]),
 *   so the cookies left by logging in match the version of the site Claire sees while working, and Claire is logged in there too.
 * Agent tabs of their own ignore this and keep following the per-site record (desktop by default).
 */
object BrowserIdentity {
    /** Returns true to use the mobile version. */
    fun mobile(loginForMika: Boolean, agentMobile: Boolean, userDesktop: Boolean): Boolean =
        if (loginForMika) agentMobile else !userDesktop

    /** Opened from the frequently used sites: one that is not logged in yet goes through "log in for Claire". */
    fun loginFlow(site: FavoriteSite): Boolean = !site.loggedIn
}

/**
 * Sites the user switched to desktop in the browser page (mobile by default). Recorded by host name with a leading www. removed; subdomains follow their parent
 * (recording example.com makes m.example.com use desktop as well). Stored separately from the Agent's copy, so the two do not affect each other.
 * Stored in a JSON file in the app's private directory; if it is corrupted, it restarts from an empty list.
 */
class UserSiteModes(dir: File) {
    private val file = File(dir, FILE)
    private val desktop = LinkedHashSet<String>()

    init {
        runCatching {
            if (file.isFile) {
                (Json.parseToJsonElement(file.readText()) as? JsonArray)?.forEach { e ->
                    (e as? JsonPrimitive)?.contentOrNull?.let(::key)?.let { desktop += it }
                }
            }
        }
    }

    @Synchronized
    fun isDesktop(host: String?): Boolean {
        var h = host?.let(::key) ?: return false
        while (true) {
            if (h in desktop) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    /** Records which version this site uses; when switching back to mobile, the parent domain's record is removed as well, otherwise the site would still follow the parent. */
    @Synchronized
    fun set(host: String, desktop: Boolean) {
        val k = key(host) ?: return
        if (desktop) this.desktop += k
        else this.desktop.removeAll { k == it || k.endsWith(".$it") }
        save()
    }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JsonArray(desktop.map(::JsonPrimitive)).toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private companion object {
        const val FILE = "browser_site_modes.json"

        fun key(host: String): String? =
            host.trim().trimEnd('.').lowercase().removePrefix("www.").takeIf { it.isNotEmpty() && ' ' !in it && '/' !in it }
    }
}
