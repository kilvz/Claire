package com.wangzan101.mika.core.screen.map

/**
 * Render the screen map as compact map text for the model (design D8). Screens have no semantic name (D3), so their name is
 * the skeleton anchors describing themselves; when the map is too long only the screen list is truncated, never squeezing out
 * the element lists.
 */
object ScreenMapRender {
    /** Which screen you are on now; an empty string when it cannot be recognized. */
    fun renderPosition(graph: ScreenGraph, currentId: String?): String {
        val node = currentId?.let { graph.nodes[it] } ?: return ""
        return "You are now on the \"${label(node)}\" screen"
    }

    /**
     * The screen map given to the model when entering an app: only when a map exists, and not when the screen cannot be read
     * ([skeletonTexts] is null); when the current screen is recognized it includes "where you are + how to get from here",
     * otherwise only the screen list.
     */
    fun renderMapFor(graph: ScreenGraph?, skeletonTexts: Set<String>?): String? {
        if (graph == null || graph.nodes.isEmpty()) return null
        if (skeletonTexts == null) return null
        val currentId = ScreenRecognizer.identify(skeletonTexts, graph.nodes())?.id
        return renderMap(graph, currentId)
    }

    /**
     * The screen list plus the paths out of the current screen. Beyond [maxScreens] the screen list is truncated, and beyond
     * [maxChars] the whole text is truncated.
     * An empty map returns an empty string.
     */
    fun renderMap(graph: ScreenGraph, currentId: String?, maxScreens: Int = 12, maxChars: Int = 1200): String {
        if (graph.nodes.isEmpty()) return ""
        val lines = mutableListOf<String>()
        val all = graph.nodes.values.sortedBy { label(it) }
        lines.add("Screens (${all.size}): " + all.take(maxScreens).joinToString(" / ") { label(it) })
        val cur = currentId?.let { graph.nodes[it] }
        if (cur != null) {
            lines.add("Currently on the \"${label(cur)}\" screen")
            val outs = cur.transitions.values.filter { it.to != null && it.to != currentId }
            if (outs.isNotEmpty()) {
                lines.add("Reachable from here:")
                outs.forEach { t ->
                    val toLabel = t.to?.let { graph.nodes[it]?.let(::label) } ?: t.to.orEmpty()
                    val what = t.target ?: t.action
                    lines.add("  Tap \"$what\" → $toLabel")
                }
            }
        }
        val out = lines.joinToString("\n")
        return if (out.length <= maxChars) out else out.take(maxChars)
    }

    private fun label(node: ScreenNode): String =
        node.anchors.keys.sorted().joinToString("、").ifEmpty { node.id.take(8) }
}
