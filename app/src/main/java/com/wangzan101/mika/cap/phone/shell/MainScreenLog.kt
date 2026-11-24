package com.wangzan101.mika.cap.phone.shell

/**
 * Record of the tasks that "surface to the top" on the main screen (display 0), fed once per task list scan by
 * the built-in helper ([scan]).
 * It records facts only and does not judge whether an Agent action caused them (that is on Claire's side, see
 * MainScreenSpill): an entry is recorded when the top task of the main screen changes, and another when the
 * same task stays but its top activity changes ([Arrival.sameTask]).
 * Each entry carries the display where the task was last seen ([Arrival.from], [NEW] if never seen before),
 * which is how a task moved over from another display (a takeover) is recognized.
 * Pure logic; the clock is supplied by the caller.
 */
internal class MainScreenLog(private val keepMs: Long = KEEP_MS) {
    /** One observed task: [topActivity] is the fully qualified class name of its top activity, [standard] means it is an ordinary app activity (not the launcher, recents, assistant and the like). */
    data class Task(
        val taskId: Int,
        val displayId: Int,
        val packageName: String?,
        val userId: Int,
        val topActivity: String?,
        val standard: Boolean = true,
    )

    data class Arrival(
        val taskId: Int,
        val packageName: String?,
        val userId: Int,
        val at: Long,
        val from: Int,
        val sameTask: Boolean,
        val standard: Boolean,
        val topActivity: String? = null,
    )

    private var top: Task? = null
    private var displays: Map<Int, Int> = emptyMap()
    private val arrivals = ArrayDeque<Arrival>()

    /**
     * Feeds the task list once ([tasks]: tasks on all displays, ordered by most recent use, the same order as
     * the system's getTasks). When [record] is false, only the current state is stored as a baseline and nothing
     * that surfaced is recorded (changes made while not watching cannot be attributed to a moment).
     */
    fun scan(tasks: List<Task>, now: Long, record: Boolean) {
        val mainTop = tasks.firstOrNull { it.displayId == MAIN }
        val last = top
        if (record && mainTop != null && last != null) {
            when {
                mainTop.taskId != last.taskId -> add(mainTop, now, displays[mainTop.taskId] ?: NEW, sameTask = false)
                mainTop.topActivity != last.topActivity -> add(mainTop, now, MAIN, sameTask = true)
            }
        }
        top = mainTop
        displays = tasks.associate { it.taskId to it.displayId }
        while (arrivals.isNotEmpty() && now - arrivals.first().at > keepMs) arrivals.removeFirst()
    }

    private fun add(t: Task, now: Long, from: Int, sameTask: Boolean) {
        arrivals.addLast(Arrival(t.taskId, t.packageName, t.userId, now, from, sameTask, t.standard, t.topActivity))
    }

    /** The task currently at the top of the main screen; -1 when nothing has been seen yet or the main screen has no task. */
    val topTaskId: Int get() = top?.taskId ?: -1

    /** Records at or after [since] (inclusive), in chronological order. */
    fun since(since: Long): List<Arrival> = arrivals.filter { it.at >= since }

    /** The display where this task was last seen; -1 when it is gone. */
    fun displayOf(taskId: Int): Int = displays[taskId] ?: -1

    companion object {
        const val MAIN = 0
        const val NEW = -1
        const val KEEP_MS = 60_000L
    }
}
