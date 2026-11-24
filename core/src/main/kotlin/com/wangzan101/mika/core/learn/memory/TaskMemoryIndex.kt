package com.wangzan101.mika.core.learn.memory

/** Retrieval over cross-session task summaries: filtered by keyword, app package name and time range, returning the most relevant few (newest first). */
class TaskMemoryIndex(private val items: List<TaskMemory>) {

    /** One entry in the lightweight index: title, time, apps/sites used, a one-line conclusion and the step count; the step texts themselves are not included. */
    data class Hit(
        val id: String,
        val goal: String,
        val conclusion: String,
        val apps: List<String>,
        val sites: List<String>,
        val createdAt: Long,
        val steps: Int,
    )

    /** [query] is looked for in the goal, the conclusion and the step labels (empty = no filtering); [app] filters by package name; [since] keeps only those at or after that time. */
    fun search(query: String, app: String?, since: Long?, limit: Int): List<TaskMemory> {
        val q = query.trim()
        val appKey = app?.trim()?.takeIf { it.isNotEmpty() }
        return items.asSequence()
            .filter { since == null || it.createdAt >= since }
            .filter { appKey == null || it.apps.contains(appKey) }
            .filter {
                q.isEmpty() || it.goal.contains(q, ignoreCase = true) ||
                    it.conclusion.contains(q, ignoreCase = true) ||
                    it.steps.any { s -> s.label.contains(q, ignoreCase = true) }
            }
            .sortedByDescending { it.createdAt }
            .take(limit)
            .toList()
    }

    companion object {
        fun hit(m: TaskMemory): Hit =
            Hit(m.id, m.goal, m.conclusion, m.apps, m.sites, m.createdAt, m.steps.size)
    }
}
