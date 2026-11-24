package com.wangzan101.mika.core.screen.map

import com.wangzan101.mika.core.run.record.RunEvent

/**
 * merge gate self-check (design D7): two gates that must be zero, for `screen-map` self-checks/tests, not runtime metrics.
 * They block the "merged wrong" direction (two different pages treated as one, or a screen attributed to the wrong app); the
 * "split" direction has no threshold (D9).
 */
object ScreenMapMergeGate {
    /**
     * Screens labeled with different semantic names that [ScreenRecognizer.identify] nonetheless recognizes as the same screen
     * → suspected wrong merge.
     * Returns pairs of (the misrecognized screen id, what it was recognized as); when [labeled] has no name (empty/not labeled)
     * it is not judged (this version does not label screens, so it is naturally empty).
     */
    fun wrongMerge(nodes: List<ScreenNode>, labeled: Map<String, String>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (a in nodes) {
            val labelA = labeled[a.id] ?: continue
            val rec = ScreenRecognizer.identify(a.anchors.keys.toSet(), nodes) ?: continue
            if (rec.id == a.id) continue
            val labelB = labeled[rec.id] ?: continue
            if (labelA != labelB) out += a.id to rec.id
        }
        return out
    }

    /**
     * A screen in the events was recorded under the wrong app: the same screen signature appears under two different non-empty
     * package names (and both packages already have a map).
     * Attribution to system / unknown (null package name) or to a package with no map does not count (D7 "allowed, just not
     * recorded"). Returns the screen signatures attributed to the wrong app.
     */
    fun wrongOwner(events: List<RunEvent>, graphs: Map<String, ScreenGraph>): List<String> {
        val owners = mutableMapOf<String, MutableSet<String>>()
        for (e in events) {
            val at = e as? RunEvent.ActionTarget ?: continue
            if (at.incomplete) continue
            for (a in listOf(at.before, at.after)) {
                val pkg = a.packageName ?: continue
                if (pkg !in graphs) continue
                val sig = ScreenId.signature(a.texts)
                owners.getOrPut(sig) { mutableSetOf() }.add(pkg)
            }
        }
        return owners.filterValues { it.size > 1 }.keys.sorted()
    }
}
