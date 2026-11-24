package com.wangzan101.mika.core.eval

/** Grouping for parallel evaluation: tasks in the same group run at the same time. */
object EvalPairing {
    /**
     * Greedy in task order: each group starts from the first task not yet assigned and looks ahead for tasks whose apps are disjoint from everyone already in the group, until it holds [n] tasks.
     * The same app cannot be on two screens at once, so tasks with overlapping apps go in different groups; a task without `apps` has no known apps, so it gets a group of its own.
     */
    fun groups(tasks: List<EvalTask>, n: Int): List<List<EvalTask>> {
        val left = tasks.toMutableList()
        val out = mutableListOf<List<EvalTask>>()
        while (left.isNotEmpty()) {
            val first = left.removeAt(0)
            val group = mutableListOf(first)
            if (first.apps.isNotEmpty()) {
                val used = first.apps.toMutableSet()
                val it = left.iterator()
                while (group.size < n && it.hasNext()) {
                    val t = it.next()
                    if (t.apps.isEmpty() || t.apps.any { a -> a in used }) continue
                    group += t
                    used += t.apps
                    it.remove()
                }
            }
            out += group
        }
        return out
    }
}
