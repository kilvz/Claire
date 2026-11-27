package com.wangzan101.mika.core.trigger

import com.wangzan101.mika.core.run.PauseFlag
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Who created the plan: the user manually, the model (schedule_task), or an external script (broadcast); affects display only, so incidents can be traced. */
@Serializable
enum class TriggerSource { User, Model, External }

@Serializable
data class TriggerDefinition(
    val id: String,
    val threadId: String,
    val goal: String,
    val nextAtMs: Long,
    /** null means one-shot; interval triggers merge backlogged slots and catch up only once. */
    val intervalMs: Long? = null,
    /** Check in the sandbox/web page first; run [goal] only when the check holds. */
    val conditionGoal: String? = null,
    val enabled: Boolean = true,
    /** Once due, run only when there is no foreground task; if busy, the watchdog re-checks later. */
    val idleOnly: Boolean = false,
    /** Whether the last valid check met the condition (the one skipped during cooldown does not count); used to act only on state changes. */
    val lastConditionMet: Boolean? = null,
    /** Fixed reminder text; when non-empty it is delivered directly when due, without starting the model. */
    val reminderText: String? = null,
    /** Repeat at local clock times in the given time zone; empty means use a fixed millisecond interval or run only once. */
    val calendarTime: String? = null,
    val calendarZone: String? = null,
    val calendarDays: List<Int> = emptyList(),
    /** With a condition: act only when the condition has just been met (default), or on every check that finds it met. */
    val notifyWhen: ConditionNotify = ConditionNotify.BecomesMet,
    /** At least this long between two dispatches of the action; a match during the cooldown is deferred until the cooldown ends. */
    val cooldownMs: Long? = null,
    /** Key value recorded by the most recent valid check. */
    val lastValue: String? = null,
    /** Comparison baseline: the value when the action was last dispatched; when it has never been dispatched, the value from the first check. */
    val baselineValue: String? = null,
    val baselineAtMs: Long? = null,
    /** The scheduled time of the check that last dispatched the action because the condition was met; the cooldown counts from here. */
    val lastFiredAtMs: Long? = null,
    /** Multi-clock plans; a single clock still uses only calendarTime, the main file stays compatible with older versions, and the full list is stored by the host in the extension state. */
    val calendarTimes: List<String> = emptyList(),
    /** Who created the plan: the user manually / the model / an external script; affects display and traceability only. */
    val source: TriggerSource = TriggerSource.Model,
    /** 模型任务允许执行的当地窗口；空表示随时执行。固定提醒和手动立即执行不受此限制。 */
    val executionWindowZone: String? = null,
    val executionWindows: List<ExecutionWindow> = emptyList(),
) {
    init {
        require(id.isNotBlank() && threadId.isNotBlank() && goal.isNotBlank())
        require(nextAtMs >= 0 && (intervalMs == null || intervalMs > 0))
        require(calendarTime == null || (intervalMs == null && calendarZone != null && calendarDays.all { it in 1..7 }))
        require(cooldownMs == null || cooldownMs > 0)
        require(calendarTimes.isEmpty() || (calendarTime == calendarTimes.first() &&
            calendarTimes == normalizedCalendarTimes(calendarTimes)))
        require(executionWindows.isEmpty() || (executionWindowZone != null &&
            runCatching { ZoneId.of(executionWindowZone) }.isSuccess && executionWindows.all(::isValidExecutionWindow)))
        require(executionWindows.isNotEmpty() || executionWindowZone == null)
    }

    fun calendarClocks(): List<String> = calendarTimes.ifEmpty { listOfNotNull(calendarTime) }

    /** 到点后的实际可执行时间；提醒不消耗模型，不被低价窗口推迟。 */
    fun executionAtOrAfter(atMs: Long): Long =
        if (executionWindows.isEmpty() || reminderText != null) atMs
        else nextExecutionWindowAt(executionWindows, executionWindowZone!!, atMs)
}

@Serializable
enum class TriggerWindowState { Claimed, Dispatched, ActionDispatched, Delivered, Skipped, Failed, Unknown }

@Serializable
data class TriggerWindow(
    val triggerId: String,
    val dueAtMs: Long,
    val claimedAtMs: Long,
    val missedIntervals: Long = 0,
    val state: TriggerWindowState = TriggerWindowState.Claimed,
    val reason: String? = null,
) {
    val key get() = "$triggerId:$dueAtMs"
}

/**
 * How many times in a row this plan already failed before the [currentKey] occurrence: count failures backwards from the immediately preceding one, stopping at a delivered or unknown result;
 * occurrences skipped because the condition was unmet and ones still without a result do not count and do not break the streak. 0 when [currentKey] is not recognized.
 */
fun failureStreakBefore(windows: List<TriggerWindow>, currentKey: String): Int {
    val current = windows.firstOrNull { it.key == currentKey } ?: return 0
    var streak = 0
    for (w in windows.filter { it.triggerId == current.triggerId && it.dueAtMs < current.dueAtMs }.sortedByDescending { it.dueAtMs }) {
        when (w.state) {
            TriggerWindowState.Failed -> streak++
            TriggerWindowState.Delivered, TriggerWindowState.Unknown -> return streak
            else -> Unit
        }
    }
    return streak
}

/** claimDue must write the next time and the pending ledger together; dispatch may start only after it returns. */
interface TriggerStore {
    fun upsert(trigger: TriggerDefinition)
    fun get(id: String): TriggerDefinition?
    fun all(): List<TriggerDefinition>
    fun windows(): List<TriggerWindow>
    fun claimDue(nowMs: Long, eligible: (TriggerDefinition) -> Boolean = { true }): List<TriggerWindow>
    /** Run once manually, without changing the original next scheduled time. */
    fun claimNow(id: String, nowMs: Long): TriggerWindow?
    fun mark(key: String, state: TriggerWindowState, reason: String? = null): Boolean
    /** One-shot tasks are deleted only after delivery is confirmed. */
    fun retireDeliveredOneShots()
    fun remove(id: String)
}

@Serializable
data class TriggerSnapshot(val triggers: List<TriggerDefinition>, val windows: List<TriggerWindow>) {
    fun recentWindows(triggerId: String, limit: Int): List<TriggerWindow> = windows.asSequence()
        .filter { it.triggerId == triggerId }
        .sortedWith(compareByDescending<TriggerWindow> { it.claimedAtMs }.thenByDescending { it.dueAtMs })
        .take(limit)
        .toList()
}

/** Pure in-memory implementation; the file implementation follows the same state transitions. */
class MemoryTriggerStore(initial: TriggerSnapshot = TriggerSnapshot(emptyList(), emptyList())) : TriggerStore {
    private val triggers = linkedMapOf<String, TriggerDefinition>()
    private val ledger = linkedMapOf<String, TriggerWindow>()

    init {
        initial.triggers.forEach { triggers[it.id] = it }
        initial.windows.forEach { ledger[it.key] = it }
    }

    @Synchronized fun snapshot() = TriggerSnapshot(triggers.values.toList(), ledger.values.toList())

    @Synchronized override fun upsert(trigger: TriggerDefinition) { triggers[trigger.id] = trigger }

    /** Save the edit and revoke the old condition check's right to dispatch; actions already started still finish. */
    @Synchronized fun edit(trigger: TriggerDefinition): Boolean {
        val previous = triggers[trigger.id] ?: return false
        if (previous.conditionGoal != null) {
            ledger.values.filter { it.triggerId == trigger.id &&
                (it.state == TriggerWindowState.Claimed || it.state == TriggerWindowState.Dispatched) }
                .forEach { mark(it.key, TriggerWindowState.Skipped, CONDITION_PLAN_EDITED) }
        }
        triggers[trigger.id] = trigger.withConditionHistoryFrom(previous)
        return true
    }

    @Synchronized override fun remove(id: String) {
        triggers.remove(id)
        ledger.entries.removeAll { it.value.triggerId == id }
    }
    @Synchronized override fun get(id: String) = triggers[id]
    @Synchronized override fun all() = triggers.values.toList()
    @Synchronized override fun windows() = ledger.values.toList()

    @Synchronized override fun claimDue(nowMs: Long, eligible: (TriggerDefinition) -> Boolean): List<TriggerWindow> {
        val active = ledger.values.filter { it.state == TriggerWindowState.Claimed ||
            it.state == TriggerWindowState.Dispatched || it.state == TriggerWindowState.ActionDispatched }
            .map { it.triggerId }.toSet()
        val due = triggers.values.filter { it.enabled && it.id !in active && it.nextAtMs <= nowMs &&
            ledger["${it.id}:${it.nextAtMs}"] == null && eligible(it) } // consistent with TriggerDefinition.slotTaken
        return due.map { t ->
            val interval = t.intervalMs
            val missed = if (interval == null) 0 else (nowMs - t.nextAtMs) / interval
            val window = TriggerWindow(t.id, t.nextAtMs, nowMs, missed)
            ledger[window.key] = window
            triggers[t.id] = when {
                t.calendarTime != null -> t.copy(nextAtMs = nextCalendarAt(t.calendarClocks(), t.calendarZone!!,
                    t.calendarDays, nowMs))
                interval != null -> t.copy(nextAtMs = t.nextAtMs + (missed + 1) * interval)
                else -> t.copy(enabled = false)
            }
            window
        }
    }

    @Synchronized override fun claimNow(id: String, nowMs: Long): TriggerWindow? {
        if (triggers[id] == null || ledger.values.any { it.triggerId == id && it.state in setOf(
                TriggerWindowState.Claimed, TriggerWindowState.Dispatched, TriggerWindowState.ActionDispatched) }) return null
        val at = generateSequence(nowMs) { it + 1 }.first { "$id:$it" !in ledger }
        return TriggerWindow(id, at, nowMs).also { ledger[it.key] = it }
    }

    @Synchronized override fun mark(key: String, state: TriggerWindowState, reason: String?): Boolean {
        val old = ledger[key] ?: return false
        if (old.state == TriggerWindowState.Delivered || old.state == TriggerWindowState.Skipped || old.state == TriggerWindowState.Failed) return false
        ledger[key] = old.copy(state = state, reason = reason)
        if (state == TriggerWindowState.Delivered || state == TriggerWindowState.Skipped ||
            state == TriggerWindowState.Failed || state == TriggerWindowState.Unknown)
            pruneCompleted(old.triggerId)
        return true
    }

    private fun pruneCompleted(id: String) {
        ledger.values.filter { it.triggerId == id && it.state in setOf(
            TriggerWindowState.Delivered, TriggerWindowState.Skipped, TriggerWindowState.Failed,
            TriggerWindowState.Unknown) }
            .sortedByDescending { it.dueAtMs }.drop(100).forEach { ledger.remove(it.key) }
    }

    @Synchronized override fun retireDeliveredOneShots() {
        val retired = triggers.values.filter { it.intervalMs == null && it.calendarTime == null && ledger.values.any { w ->
            w.triggerId == it.id && w.dueAtMs == it.nextAtMs && w.state == TriggerWindowState.Delivered } }
            .map { it.id }
        retired.forEach { id -> triggers.remove(id); ledger.entries.removeAll { it.value.triggerId == id } }
    }
}

/**
 * This plan's current slot is already on the ledger (claimed, ran, or running): [TriggerStore.claimDue] will not claim it again, so both sides must agree.
 * A one-shot plan's next time does not move after being claimed, which is this case; a recurring plan moves to the next slot when claimed.
 */
fun TriggerDefinition.slotTaken(windows: List<TriggerWindow>): Boolean =
    windows.any { it.triggerId == id && it.dueAtMs == nextAtMs }

/**
 * Whether the plan page's toggle can be flipped: an enabled plan can always be paused; for a disabled one it depends on whether this slot has already run, matching [TriggerEngine.setEnabled].
 * A one-shot plan cannot be re-enabled once its current slot has run (to run again, change the time or run now); a manual run is recorded in a different slot and does not count as occupying this one.
 */
fun TriggerDefinition.canToggle(windows: List<TriggerWindow>): Boolean = enabled || !slotTaken(windows)

/** Advance along the local calendar, keeping the same clock time across DST; a nonexistent clock time follows the system's forward-adjustment rule. */
fun nextCalendarAt(time: String, zone: String, days: List<Int>, afterMs: Long): Long =
    nextCalendarAt(listOf(time), zone, days, afterMs)

/** Several clock times on the same day are unified into the next occurrence; when a DST jump makes two clock times land on the same instant, dispatch only once. */
fun nextCalendarAt(times: List<String>, zone: String, days: List<Int>, afterMs: Long): Long {
    require(times.isNotEmpty())
    val localTimes = times.map(LocalTime::parse)
    val zoneId = ZoneId.of(zone)
    var date = Instant.ofEpochMilli(afterMs).atZone(zoneId).toLocalDate()
    repeat(9) {
        if (days.isEmpty() || date.dayOfWeek.value in days) {
            val candidate = localTimes.map { date.atTime(it).atZone(zoneId).toInstant().toEpochMilli() }
                .filter { it > afterMs }.minOrNull()
            if (candidate != null) return candidate
        }
        date = date.plusDays(1)
    }
    error("calendar schedule has no matching day")
}

private val CALENDAR_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

/** Minute-level clock validation, de-duplication and sorting shared by the UI and the model. */
fun normalizedCalendarTimes(times: List<String>): List<String> {
    require(times.isNotEmpty())
    return times.map { raw ->
        val time = LocalTime.parse(raw.trim())
        require(time.second == 0 && time.nano == 0)
        CALENDAR_CLOCK.format(time)
    }.distinct().sorted()
}

/** When the next time is changed, calendar plans repeat at the new clock time too; save and preview must share this conversion. */
fun TriggerDefinition.rescheduledTo(nextAtMs: Long): TriggerDefinition {
    val local = calendarZone?.let { zone ->
        CALENDAR_CLOCK.format(Instant.ofEpochMilli(nextAtMs).atZone(ZoneId.of(zone)))
    }
    val times = if (calendarTime == null) emptyList()
        else normalizedCalendarTimes(listOfNotNull(local) + calendarTimes.drop(1))
    return copy(nextAtMs = nextAtMs, enabled = true, calendarTime = times.firstOrNull(), calendarTimes = times)
}

/** Preview while editing/creating: the first occurrence not earlier than [afterMs] is included, then advance by the plan's own zone and repeat rules. */
fun upcoming(def: TriggerDefinition, afterMs: Long, count: Int): List<Long> {
    if (count <= 0) return emptyList()
    val out = ArrayList<Long>(count)
    val constrained = def.executionWindows.isNotEmpty() && def.reminderText == null
    when {
        def.calendarTime != null -> {
            var at = if (!constrained && def.nextAtMs < afterMs)
                nextCalendarAt(def.calendarClocks(), def.calendarZone!!, def.calendarDays, afterMs)
            else def.nextAtMs
            repeat(count) {
                val earliest = if (out.isEmpty()) maxOf(at, afterMs) else at
                val runAt = def.executionAtOrAfter(earliest)
                out += runAt
                at = nextCalendarAt(def.calendarClocks(), def.calendarZone!!, def.calendarDays, runAt)
            }
        }
        def.intervalMs != null && def.intervalMs > 0 -> {
            val interval = def.intervalMs
            var scheduled = def.nextAtMs
            if (!constrained && scheduled < afterMs) scheduled += ((afterMs - scheduled - 1) / interval + 1) * interval
            repeat(count) {
                val earliest = if (out.isEmpty()) maxOf(scheduled, afterMs) else scheduled
                val runAt = def.executionAtOrAfter(earliest)
                out += runAt
                scheduled += ((runAt - scheduled) / interval + 1) * interval
            }
        }
        else -> out += def.executionAtOrAfter(if (constrained) maxOf(def.nextAtMs, afterMs) else def.nextAtMs)
    }
    return out
}

/** Scheduling is implemented by the host; the core records to the ledger first and then dispatches, so a claimed slot is never executed twice after an abnormal restart. */
class TriggerEngine(private val store: TriggerStore, private val scheduleAlarm: (Long?) -> Unit,
    private val isIdle: () -> Boolean = { true }, private val idleRetryMs: Long = 60_000L,
    /** 用户暂停了 Mika:到点的时段照样认领、记成「因暂停跳过」,不派发;恢复后不补跑。 */
    private val paused: PauseFlag = PauseFlag.Never,
    private val dispatch: (TriggerDefinition, TriggerWindow) -> Unit) {
    fun rearm(nowMs: Long) {
        store.windows().filter { it.state == TriggerWindowState.Claimed }
            .forEach { store.mark(it.key, TriggerWindowState.Unknown, "process restarted before dispatch confirmation") }
        val idle = isIdle()
        val windows = store.windows()
        val active = windows.filter { it.state == TriggerWindowState.Claimed ||
            it.state == TriggerWindowState.Dispatched || it.state == TriggerWindowState.ActionDispatched }
            .map { it.triggerId }.toSet()
        // claimDue will not claim plans whose slot is already on the ledger, so no alarm is set for them: scheduling by due time would mean "ring immediately", and ringing again cannot claim them, spinning forever
        scheduleAlarm(store.all().filter { it.enabled && (it.id in active || !it.slotTaken(windows)) }.minOfOrNull {
            if (it.id in active) nowMs + idleRetryMs
            else {
                val earliest = maxOf(if (it.idleOnly && !idle) nowMs + idleRetryMs else nowMs, it.nextAtMs)
                it.executionAtOrAfter(earliest)
            }
        })
    }

    /**
     * Pause or resume [id]; when there is no such plan, or the plan to resume already has its current slot on the ledger ([slotTaken], a claimed one-shot plan), nothing changes and false is returned:
     * even resumed it would not run again, so running again needs a time change or a run now (the plan page also offers no toggle for such plans).
     */
    fun setEnabled(id: String, enabled: Boolean, nowMs: Long): Boolean {
        val trigger = store.get(id) ?: return false
        if (enabled && trigger.slotTaken(store.windows())) return false
        store.upsert(trigger.copy(enabled = enabled))
        rearm(nowMs)
        return true
    }

    /** 时区变化后,跟随设备旧时区的当地时间与自定义窗口重算到新时区;显式钉了其他时区(如 UTC 价格窗口)的不动。 */
    fun rezone(nowMs: Long, fromZone: String, toZone: ZoneId) {
        if (fromZone == toZone.id) return
        store.all().filter { (it.enabled && it.calendarTime != null && it.calendarZone == fromZone) ||
            it.executionWindowZone == fromZone }
            .forEach { t ->
                val moveCalendar = t.enabled && t.calendarTime != null && t.calendarZone == fromZone
                val moveExecution = t.executionWindowZone == fromZone &&
                    !(t.executionWindowZone == DEEPSEEK_OFF_PEAK_ZONE && t.executionWindows == DEEPSEEK_OFF_PEAK_WINDOWS)
                store.upsert(
                    t.copy(
                        calendarZone = if (moveCalendar) toZone.id else t.calendarZone,
                        nextAtMs = if (moveCalendar) nextCalendarAt(t.calendarClocks(), toZone.id, t.calendarDays, nowMs) else t.nextAtMs,
                        executionWindowZone = if (moveExecution) toZone.id else t.executionWindowZone,
                    )
                )
            }
        rearm(nowMs)
    }

    fun fire(nowMs: Long) {
        if (paused.paused()) {
            // 暂停时不等空闲:等着的时段若留到恢复以后,就成了恢复时自动补跑的暗队列
            store.claimDue(nowMs) { it.executionAtOrAfter(nowMs) == nowMs }
                .forEach { store.mark(it.key, TriggerWindowState.Skipped, PauseFlag.REASON) }
            rearm(nowMs)
            return
        }
        val idle = isIdle()
        store.claimDue(nowMs) { (!it.idleOnly || idle) && it.executionAtOrAfter(nowMs) == nowMs }.forEach { window ->
            val trigger = store.get(window.triggerId) ?: return@forEach
            try {
                dispatch(trigger, window)
                store.mark(window.key, TriggerWindowState.Dispatched)
            } catch (e: Exception) {
                store.mark(window.key, TriggerWindowState.Unknown, e::class.simpleName)
            }
        }
        rearm(nowMs)
    }
}
