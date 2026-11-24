package com.wangzan101.mika.core.learn.knowledge

/**
 * Which site knowledge a hostname belongs to: based on the entry's domains, a hostname matches when it equals a domain
 * or is a subdomain of it; when several entries match, take the one whose matched domain is longest (the most specific,
 * e.g. shop.example.com beats example.com).
 * Both injecting site knowledge (KnowledgeStore.byDomain) and looking up site commands (GuideCatalog.siteCommands) use
 * it, so both sides agree on the same entry.
 */
object SiteMatch {
    /** The length of the longest domain in [domains] that [host] matches; null if it matches none. */
    fun score(host: String, domains: List<String>): Int? {
        val h = host.lowercase().removePrefix("www.")
        return domains.map { it.lowercase() }.filter { h == it || h.endsWith(".$it") }.maxOfOrNull { it.length }
    }

    /** The most specific items in [items] that match [host] (all ties are kept, order preserved); empty if none match. */
    fun <T> best(host: String, items: List<T>, domains: (T) -> List<String>): List<T> {
        val scored = items.mapNotNull { i -> score(host, domains(i))?.let { i to it } }
        val top = scored.maxOfOrNull { it.second } ?: return emptyList()
        return scored.filter { it.second == top }.map { it.first }
    }
}
