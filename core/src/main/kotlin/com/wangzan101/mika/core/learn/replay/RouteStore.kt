package com.wangzan101.mika.core.learn.replay

/** A practice that has already been learned. */
interface RouteStore {
    fun all(): List<Route>
    fun put(r: Route)
    fun remove(id: String)
    fun clear()
    fun count(): Int

    /** Read the entry with [id], rewrite it and write it back; if [change] returns null nothing is changed. Implementations used concurrently must lock the whole operation, so no other change can slip in between the read and the write. */
    fun update(id: String, change: (Route) -> Route?) {
        all().firstOrNull { it.id == id }?.let(change)?.let(::put)
    }

    /** When a route for the same template already exists, replace the old one with the new route (see [replacing]). Implementations used concurrently must lock the whole operation. */
    fun putReplacing(r: Route) {
        val old = all().filter { it.template == r.template }
        put(replacing(r, old))
        old.filter { it.id != r.id }.forEach { remove(it.id) }
    }
}

/** The same path: every step has the same tool, arguments and target (the anchor differs slightly on each screen read, which does not count). */
fun samePath(a: Route, b: Route): Boolean =
    a.steps.size == b.steps.size && a.steps.zip(b.steps).all { (x, y) -> x.tool == y.tool && x.argsJson == y.argsJson && x.target == y.target }

/**
 * What the new route carries over when it replaces an old route of the same template: the success count is the maximum of the two; if the path is unchanged, the miss count and the disabled state carry over
 * (where the same path previously failed to match should not be wiped out just because the record is replaced); if the path changed it is a new approach, so the miss count starts from zero.
 */
fun replacing(r: Route, old: List<Route>): Route {
    val successes = maxOf(r.successes, old.maxOfOrNull { it.successes } ?: 0)
    val same = old.firstOrNull { samePath(it, r) } ?: return r.copy(successes = successes)
    return r.copy(successes = successes, consecutiveMisses = same.consecutiveMisses, missStep = same.missStep, disabled = same.disabled)
}
