package com.wangzan101.mika.core.frontdesk

/** The front desk's task board: lists the tasks dispatched in this conversation as a few lines and puts them in the front desk's context each turn, so it can talk from them without looking anything up. */
object DeskBoard {
    /** One line per task: id, title, state (with queue position when queued) and progress; null when there are no tasks. */
    fun render(views: List<DeskTaskView>): String? = views.takeIf { it.isNotEmpty() }?.joinToString("\n") { view ->
        val state = when (view.state) {
            DeskTaskPhase.Queued -> view.position?.let { "queued (position $it)" } ?: "queued"
            else -> word(view.state)
        }
        listOfNotNull("- task_id ${view.taskId}", view.title, state, progress(view)).joinToString(" · ")
    }

    /** Running and needs-you tasks list their latest progress lines (oldest first); other states list only the current line. */
    private fun progress(view: DeskTaskView): String? = when (view.state) {
        DeskTaskPhase.Running, DeskTaskPhase.NeedsYou -> view.recent.takeIf { it.isNotEmpty() }?.joinToString(" → ") ?: view.line
        else -> view.line
    }

    private fun word(phase: DeskTaskPhase): String = when (phase) {
        DeskTaskPhase.Running -> "running"
        DeskTaskPhase.Queued -> "queued"
        DeskTaskPhase.NeedsYou -> "needs_you"
        DeskTaskPhase.Done -> "done"
        DeskTaskPhase.Failed -> "failed"
        DeskTaskPhase.Cancelled -> "cancelled"
    }
}
