package com.wangzan101.mika.core.screen.map

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The screen map of one app: nodes = structural pages, edges = click navigation. Rebuilt deterministically from the run log;
 * keys of `nodes` are screen ids.
 */
@Serializable
data class ScreenGraph(val app: String, val nodes: Map<String, ScreenNode>) {
    fun nodes(): List<ScreenNode> = nodes.values.toList()

    /**
     * Shortest navigation sequence from screen [from] to screen [to] (BFS, only following NAVIGATED edges that change screens).
     * Returns null when unreachable.
     * Returns an empty sequence when already at [to]. design D6: relative navigation, so segments can be stitched together even
     * for a target never fully traversed.
     */
    fun path(from: String, to: String): List<Transition>? {
        if (from == to) return emptyList()
        if (nodes[from] == null || nodes[to] == null) return null
        data class Step(val node: String, val path: List<Transition>)
        val visited = mutableSetOf(from)
        val queue = ArrayDeque<Step>()
        queue.addLast(Step(from, emptyList()))
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val node = nodes[cur.node] ?: continue
            for (t in node.transitions.values) {
                val next = t.to ?: continue // LEFT_APP does not reach any screen in this map
                if (next == cur.node) continue // state_changed / none edges stay in place
                val path = cur.path + t
                if (next == to) return path
                if (visited.add(next)) queue.addLast(Step(next, path))
            }
        }
        return null
    }

    /**
     * Merge [fragment] in by screen id: for the same id the anchors/levels/visits/transitions counts are summed and status goes
     * provisional→confirmed; new ids are appended (keeping their status). Fragments of a different app are not merged (the
     * original graph is returned, to avoid attributing screens to the wrong app).
     */
    fun mergedWith(fragment: ScreenGraph): ScreenGraph {
        if (fragment.app != app) return this
        val merged = nodes.toMutableMap()
        for ((id, node) in fragment.nodes) {
            merged[id] = merged[id]?.let { mergeNode(it, node) } ?: node
        }
        return copy(nodes = merged)
    }

    companion object {
        /** Length of the common prefix of two paths (compared edge by edge by transition key). Used when stitching maps during replay to cut the already-traversed common part (design D6). */
        fun commonPrefix(a: List<Transition>, b: List<Transition>): Int {
            var i = 0
            while (i < a.size && i < b.size && a[i].key() == b[i].key()) i++
            return i
        }

        private fun mergeNode(a: ScreenNode, b: ScreenNode): ScreenNode = a.copy(
            anchors = sumCounts(a.anchors, b.anchors),
            levels = sumCounts(a.levels, b.levels),
            visits = a.visits + b.visits,
            transitions = mergeTransitions(a.transitions, b.transitions),
            status = "confirmed",
        )

        private fun sumCounts(a: Map<String, Int>, b: Map<String, Int>): Map<String, Int> {
            if (b.isEmpty()) return a
            val m = a.toMutableMap()
            for ((k, v) in b) m[k] = (m[k] ?: 0) + v
            return m
        }

        private fun mergeTransitions(a: Map<String, Transition>, b: Map<String, Transition>): Map<String, Transition> {
            if (b.isEmpty()) return a
            val m = a.toMutableMap()
            for ((k, t) in b) {
                val prev = m[k]
                m[k] = prev?.let { it.copy(count = it.count + t.count, offTrack = it.offTrack + t.offTrack) } ?: t
            }
            return m
        }
    }
}

object ScreenGraphCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(g: ScreenGraph): String = json.encodeToString(ScreenGraph.serializer(), g)
    fun decode(s: String): ScreenGraph = json.decodeFromString(ScreenGraph.serializer(), s)
}
