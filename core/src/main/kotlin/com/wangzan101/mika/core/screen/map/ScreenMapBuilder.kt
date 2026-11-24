package com.wangzan101.mika.core.screen.map

import com.wangzan101.mika.core.run.record.RunEvent

/**
 * Rebuild the screen map deterministically from the run log (design D4). The input is the before/after skeleton anchors of
 * `RunEvent.ActionTarget`: each action visits or creates a screen before and after, and adds one transition edge. Screen
 * identity = the skeleton anchor signature seen first (`ScreenId.of(app, signature)`); seeing the same signature again = the
 * same screen (counts accumulate, provisional→confirmed); a different signature = a new screen (better split than merged
 * wrong). The replay order is fixed → running the same batch of events twice yields byte-identical output.
 */
class ScreenMapBuilder {
    fun apply(runId: String, events: List<RunEvent>): Map<String, ScreenGraph> {
        val graphs = linkedMapOf<String, MutableMap<String, ScreenNode>>()
        for (e in events) {
            val at = e as? RunEvent.ActionTarget ?: continue
            if (at.incomplete) continue
            val beforeApp = at.before.packageName
            val afterApp = at.after.packageName
            val beforeNode = beforeApp?.let { visitOrCreate(graphs, it, at.before.texts) } ?: continue
            val afterNode = afterApp?.let { visitOrCreate(graphs, it, at.after.texts) } ?: continue

            val effect = when {
                beforeApp != afterApp -> TransitionEffect.LEFT_APP
                afterNode.id != beforeNode.id -> TransitionEffect.NAVIGATED
                at.effective -> TransitionEffect.STATE_CHANGED
                else -> TransitionEffect.NONE
            }
            val to = if (effect == TransitionEffect.LEFT_APP) null else afterNode.id
            val edge = Transition(action = at.tool, target = at.target?.name?.takeIf { it.isNotEmpty() }, effect = effect, to = to, count = 1)

            val nodes = graphs.getValue(beforeApp)
            val cur = nodes.getValue(beforeNode.id)
            val key = edge.key()
            val prev = cur.transitions[key]
            nodes[beforeNode.id] = cur.copy(transitions = cur.transitions + (key to (prev?.let { it.copy(count = it.count + 1) } ?: edge)))
        }
        return graphs.mapValues { (app, nodes) -> ScreenGraph(app, nodes.toMap()) }
    }

    private fun visitOrCreate(graphs: MutableMap<String, MutableMap<String, ScreenNode>>, app: String, texts: List<String>): ScreenNode {
        val nodes = graphs.getOrPut(app) { linkedMapOf() }
        val id = ScreenId.of(app, ScreenId.signature(texts))
        val existing = nodes[id]
        return if (existing != null) {
            val updated = existing.copy(
                anchors = increment(existing.anchors, texts),
                visits = existing.visits + 1,
                status = "confirmed",
            )
            nodes[id] = updated
            updated
        } else {
            val node = ScreenNode(id = id, app = app, anchors = texts.associateWith { 1 }, visits = 1, status = "provisional")
            nodes[id] = node
            node
        }
    }

    private fun increment(anchors: Map<String, Int>, texts: List<String>): Map<String, Int> {
        val m = anchors.toMutableMap()
        for (t in texts) m[t] = (m[t] ?: 0) + 1
        return m
    }
}
