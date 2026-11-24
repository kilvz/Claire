package com.wangzan101.mika.core.learn.memory

import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.PlaceKind
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.tool.PACKAGE_NAME_RE

/**
 * Compresses the events of one run into a cross-session summary. A pure function that does not call a model: [label]
 * turns tool names into the wording used by the UI, [appLabel] turns package names into app names.
 * Fragmentary tasks that only read the screen a few times and performed no action are not recorded ([record] returns
 * null). Apps are stored as package names and websites as hostnames — stable facts to filter and trace by.
 */
object TaskMemoryRecorder {

    fun record(
        goal: String,
        events: List<RunEvent>,
        now: Long,
        id: String,
        label: (String) -> String = { it },
        appLabel: (String) -> String = { it },
    ): TaskMemory? {
        val acts = events.filterIsInstance<RunEvent.Activity>()
        // no action was performed (control-type actions are already excluded when recording), or it was just a quick
        // glance: do not record
        if (acts.none { it.kind != "observe" } && acts.size < 2) return null

        val reads = events.filterIsInstance<RunEvent.Observed>().filter { it.kind == ObsKind.Read.wire && it.text.isNotBlank() }
        val readByStep: Map<Int, String> = reads.groupBy { it.step }.mapValues { (_, xs) -> xs.joinToString("\n") { it.text } }

        val steps = mutableListOf<TaskMemoryStep>()
        var lastObserve: String? = null
        for (a in acts.sortedBy { it.step }) {
            val what = listOfNotNull(label(a.label), where(a.target, appLabel), a.title?.takeIf(String::isNotBlank)).joinToString(" · ")
            val tags = listOfNotNull("outward action".takeIf { a.outward && !a.failed }, "failed".takeIf { a.failed })
            val line = "Step ${a.step} $what" + if (tags.isEmpty()) "" else "(${tags.joinToString(", ")})"
            // consecutive reads of the same place are recorded as a single line
            if (a.kind == "observe" && line == lastObserve) continue
            lastObserve = if (a.kind == "observe") line else null
            steps += TaskMemoryStep(a.atMs, line.clip(LABEL_MAX), readByStep[a.step].orEmpty().clip(DETAIL_MAX))
        }
        if (steps.isEmpty()) return null

        return TaskMemory(
            id = id,
            goal = goal.clip(GOAL_MAX),
            conclusion = conclusion(events).clip(CONCLUSION_MAX),
            apps = apps(events, acts),
            sites = sites(events, acts),
            createdAt = now,
            steps = steps.take(STEP_MAX),
        )
    }

    /**
     * Only the outcome word ("done", "partially done", "failed", …), without the answer or reason text. Used for tasks
     * whose answer mostly paraphrases something someone else wrote (those that read notifications, see
     * [com.wangzan101.mika.core.proactive.NoticeRuns]): the summary will be found by later tasks, so that text is not
     * kept.
     */
    fun outcome(events: List<RunEvent>): String = conclusion(events, text = false)

    /**
     * The one-sentence conclusion: first look at the outcome judged after the answer was handed over; if there is
     * none, fall back to the task's own outcome. When [text] is false, only the outcome word is kept.
     */
    private fun conclusion(events: List<RunEvent>, text: Boolean = true): String {
        events.filterIsInstance<RunEvent.OutcomeJudged>().lastOrNull()?.let { j ->
            val word = when (j.outcome) {
                "done" -> "done"
                "partial" -> "partially done"
                "not_possible" -> "not done"
                else -> ""
            }
            return listOf(word, j.reason.takeIf { text }.orEmpty()).filter { it.isNotBlank() }.joinToString(": ")
        }
        events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()?.let { e ->
            val word = when (e.outcome) {
                "succeeded" -> "succeeded"
                "unverified" -> "succeeded (unverified)"
                "failed" -> "failed"
                "cancelled" -> "cancelled"
                "error" -> "error"
                else -> ""
            }
            return listOf(word, e.answerOrReason.takeIf { text }.orEmpty()).filter { it.isNotBlank() }.joinToString(": ")
        }
        return ""
    }

    private fun apps(events: List<RunEvent>, acts: List<RunEvent.Activity>): List<String> =
        (acts.mapNotNull { it.target }.filter { PACKAGE_NAME_RE.matches(it) } +
            events.filterIsInstance<RunEvent.Observed>().mapNotNull { it.place?.takeIf { p -> p.kind == PlaceKind.App }?.name })
            .distinct().take(APP_MAX)

    private fun sites(events: List<RunEvent>, acts: List<RunEvent.Activity>): List<String> =
        (acts.mapNotNull { it.target }.filter { it.startsWith(SITE_PREFIX) }.map { it.removePrefix(SITE_PREFIX).trim() } +
            events.filterIsInstance<RunEvent.Observed>().mapNotNull { it.place?.takeIf { p -> p.kind == PlaceKind.Site }?.name })
            .distinct().take(SITE_MAX)

    /**
     * Human-readable form of an activity target: for "[SITE_PREFIX]<domain>" only the domain is kept, package names
     * are replaced by app names, anything else (URLs, query terms) stays as is; null when blank.
     */
    private fun where(target: String?, appLabel: (String) -> String): String? = target?.let {
        when {
            it.isBlank() -> null
            it.startsWith(SITE_PREFIX) -> it.removePrefix(SITE_PREFIX).trim()
            PACKAGE_NAME_RE.matches(it) -> appLabel(it)
            else -> it
        }
    }

    /** In the run record, the target of a web-page screen is written as [SITE_PREFIX] followed by "<domain>". */
    private const val SITE_PREFIX = "网站 "

    private const val GOAL_MAX = 120
    private const val CONCLUSION_MAX = 200
    private const val LABEL_MAX = 200
    private const val DETAIL_MAX = 800
    private const val STEP_MAX = 60
    private const val APP_MAX = 8
    private const val SITE_MAX = 8

    private fun String.clip(max: Int): String = if (length <= max) this else take(max) + "…"
}
