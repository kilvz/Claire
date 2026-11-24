package com.wangzan101.mika.core.screen.map

/** Recognition result: which screen [id] it was recognized as, the confidence [confidence], and which skeleton anchors matched [matched]. */
data class Recognition(val id: String, val confidence: Double, val matched: Set<String>)

/**
 * Screen recognition: a pure function with zero model calls. Given the skeleton text set [texts] of the current screen (reusing
 * the same definition as `RouteAnchors.texts`: digits, list content, input fields and user-typed text removed), it finds the
 * most similar screen among [nodes].
 *
 * Ranking uses a score folded from `(overlap count, coverage, rarity)`: `confidence = coverage × mean rarity`.
 * coverage = |S ∩ anchors| / |anchors|; rarity = Σ 1/max(1, how many screens this text appears in).
 * "OK/Back" appears everywhere, so rarity pushes the confidence below the threshold → when it does not know it returns null and
 * does not guess (design D2).
 */
object ScreenRecognizer {
    fun identify(texts: Set<String>, nodes: List<ScreenNode>, confidenceMin: Double = 0.5): Recognition? {
        if (nodes.isEmpty() || texts.isEmpty()) return null
        // How many screens' anchors each skeleton text appears in (document frequency)
        val df = nodes.flatMap { it.anchors.keys }.groupingBy { it }.eachCount()

        var best: ScreenNode? = null
        var bestScore = -1.0
        var bestMatched: Set<String> = emptySet()
        var bestOverlap = 0

        for (node in nodes) {
            if (node.anchors.isEmpty()) continue
            val matched = node.anchors.keys.filterTo(linkedSetOf()) { it in texts }
            if (matched.isEmpty()) continue
            val coverage = matched.size.toDouble() / node.anchors.size
            val rarityMean = matched.sumOf { 1.0 / maxOf(1, df[it] ?: 1) } / matched.size
            val score = coverage * rarityMean
            // On equal scores, prefer the larger overlap
            if (score > bestScore || (score == bestScore && matched.size > bestOverlap)) {
                best = node
                bestScore = score
                bestMatched = matched
                bestOverlap = matched.size
            }
        }

        val node = best ?: return null
        if (bestScore < confidenceMin) return null
        return Recognition(node.id, bestScore, bestMatched)
    }
}
