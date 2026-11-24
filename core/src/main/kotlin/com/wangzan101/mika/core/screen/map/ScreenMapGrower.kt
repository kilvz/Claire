package com.wangzan101.mika.core.screen.map

/**
 * Grow the map at the end: merge the screen/edge fragments grown in this run (the output of [ScreenMapBuilder.apply]) into the
 * stored map by screen id. For each app: load the old map → merge with [ScreenGraph.mergedWith] → write back; read/write
 * failures are thrown upward and guarded by the caller (design D4: a failure does not affect the task).
 */
object ScreenMapGrower {
    fun grow(store: ScreenMapStore, fragments: Map<String, ScreenGraph>) {
        for ((app, fragment) in fragments) {
            if (fragment.nodes.isEmpty()) continue
            val existing = store.load(app) ?: ScreenGraph(app, emptyMap())
            store.save(app, existing.mergedWith(fragment))
        }
    }
}
