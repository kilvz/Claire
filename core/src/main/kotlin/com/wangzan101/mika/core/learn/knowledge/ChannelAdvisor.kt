package com.wangzan101.mika.core.learn.knowledge

import com.wangzan101.mika.core.net.UrlHost

/**
 * Facts used to pick a channel: installed package names, host names of sites signed in within Claire, whether the built-in browser is available,
 * and host names of sites that recently hit a verification challenge or access block within Claire.
 */
data class ChannelFacts(
    val installed: Set<String>,
    val loggedInSites: Set<String>,
    val webAvailable: Boolean,
    val challengedSites: Set<String> = emptySet(),
)

/** The one-line "available channels" given at the start of a task: the code supplies facts only; which one to choose is decided by the model per the web rules. */
object ChannelAdvisor {
    private const val HEADER = "Available channels: "
    /** Lists only the first few, comparable to the maximum number of references injected per task. */
    private const val MAX_CHANNELS = 3

    /**
     * Example: "Available channels: Xiaohongshu — app: installed (com.xingin.xhs), known way to do it in the app; web: xiaohongshu.com, known way to do it on the web, signed in within Claire (m.xiaohongshu.com)".
     * [entries] in match order, taking only the first [MAX_CHANNELS] channels. Null when there is nothing to say.
     */
    fun line(entries: List<KnowledgeEntry>, skills: GuideCatalog?, facts: ChannelFacts): String? {
        val parts = entries.groupBy { it.service }.mapNotNull { (name, same) -> channel(name, same, skills, facts) }.take(MAX_CHANNELS)
        if (parts.isEmpty()) return null
        return HEADER + parts.joinToString(" | ")
    }

    private fun channel(name: String, same: List<KnowledgeEntry>, skills: GuideCatalog?, facts: ChannelFacts): String? {
        val packages = same.flatMap { it.packages }.distinct()
        // The site names associated with an app entry are the site's main domains
        val domains = same.flatMap { it.domains + if (it.surface == Surface.Android) it.related else emptyList() }.map { it.trim().lowercase().removePrefix("www.") }.filter { it.isNotEmpty() }.distinct()
        val app = if (packages.isEmpty()) null else {
            val state = packages.firstOrNull { it in facts.installed }?.let { "app: installed ($it)" } ?: "app: not installed"
            if (skills?.hasRoute(name, "android") == true) "$state, known way to do it in the app" else state
        }
        val web = domains.firstOrNull()?.takeIf { facts.webAvailable }?.let { d ->
            fun ofSite(hosts: Set<String>) = hosts.firstOrNull { s -> domains.any { dd -> UrlHost.sameSite("https://$dd", "https://$s") } }
            val loggedIn = ofSite(facts.loggedInSites)
            val challenged = ofSite(facts.challengedSites)
            val notes = listOfNotNull(
                "known way to do it on the web".takeIf { skills?.hasRoute(name, "browser") == true },
                loggedIn?.let { "signed in within Claire ($it)" },
                challenged?.let { "recently hit a verification or block here ($it); the user can clear this site's data in settings" },
            )
            (listOf("web: $d") + notes).joinToString(", ")
        }
        if (app == null && web == null) return null
        return "${same.first().display} — " + listOfNotNull(app, web).joinToString("; ")
    }
}
