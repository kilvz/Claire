package com.wangzan101.mika.ui

/** In-page find counts at most this many hits at once, and highlights at most that many: any more couldn't be looked at one by one anyway. */
internal const val FIND_LIMIT = 1000

/**
 * In-page find: finds every case-insensitive occurrence of [query] in [text], at most [limit] of them.
 * The returned positions are ordered by appearance; two adjacent occurrences are not merged (e.g. searching "aa" in "aaaa" yields two).
 * An empty query finds nothing — otherwise there would be one hit before every character, which is meaningless.
 */
internal fun findRanges(text: CharSequence, query: String, limit: Int = Int.MAX_VALUE): List<IntRange> {
    if (query.isEmpty() || limit <= 0) return emptyList()
    val found = ArrayList<IntRange>()
    var from = 0
    while (found.size < limit) {
        val at = text.indexOf(query, from, ignoreCase = true)
        if (at < 0) break
        found += at until at + query.length
        from = at + query.length
    }
    return found
}
