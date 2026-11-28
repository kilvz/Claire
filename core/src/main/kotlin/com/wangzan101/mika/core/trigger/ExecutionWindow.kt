package com.wangzan101.mika.core.trigger

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Local time window in which model tasks may run, with minutes from 0 to 1440; when [endMinute] is less than [startMinute], it crosses midnight.
 * [days] are the window's start days, 1 to 7 for Monday to Sunday, and empty means every day. 00:00–24:00 can represent all day.
 */
@Serializable
data class ExecutionWindow(
    val startMinute: Int,
    val endMinute: Int,
    val days: List<Int> = emptyList(),
)

fun isValidExecutionWindow(window: ExecutionWindow): Boolean =
    window.startMinute in 0..1439 && window.endMinute in 0..1440 &&
        window.startMinute != window.endMinute && window.days.all { it in 1..7 } &&
        window.days == window.days.distinct().sorted()

private val WEEKDAYS = (1..5).toList()
private val WEEKEND = listOf(6, 7)

/**
 * DeepSeek publishes weekday peak periods of 01:00–04:00 and 06:00–10:00 UTC; other times and weekends are half price.
 * Public holidays are also half price all day, but no holiday table that gets updated is built in; conservatively, this still avoids peak periods as on ordinary weekdays.
 */
val DEEPSEEK_OFF_PEAK_WINDOWS = listOf(
    ExecutionWindow(0, 60, WEEKDAYS),
    ExecutionWindow(4 * 60, 6 * 60, WEEKDAYS),
    ExecutionWindow(10 * 60, 24 * 60, WEEKDAYS),
    ExecutionWindow(0, 24 * 60, WEEKEND),
)
const val DEEPSEEK_OFF_PEAK_ZONE = "UTC"

private fun localMinuteAt(date: LocalDate, minute: Int, zone: ZoneId): Long = when (minute) {
    0 -> date.atStartOfDay(zone).toInstant().toEpochMilli()
    24 * 60 -> date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    else -> date.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant().toEpochMilli()
}

/** Returns [atMs] as-is when it is already inside a window, otherwise returns the next opening; windows whose start day is the previous day are also counted as crossing midnight. */
fun nextExecutionWindowAt(windows: List<ExecutionWindow>, zone: String, atMs: Long): Long {
    require(windows.isNotEmpty() && windows.all(::isValidExecutionWindow))
    val zoneId = ZoneId.of(zone)
    val firstDate = Instant.ofEpochMilli(atMs).atZone(zoneId).toLocalDate().minusDays(1)
    var best: Long? = null
    repeat(11) { offset ->
        val openingDate = firstDate.plusDays(offset.toLong())
        for (window in windows) {
            if (window.days.isNotEmpty() && openingDate.dayOfWeek.value !in window.days) continue
            val opens = localMinuteAt(openingDate, window.startMinute, zoneId)
            val endDate = if (window.endMinute > window.startMinute) openingDate else openingDate.plusDays(1)
            val closes = localMinuteAt(endDate, window.endMinute, zoneId)
            if (opens >= closes) continue
            val candidate = when {
                atMs < opens -> opens
                atMs < closes -> atMs
                else -> null
            }
            if (candidate != null && (best == null || candidate < best!!)) best = candidate
        }
    }
    return checkNotNull(best) { "execution window has no future opening" }
}

fun isWithinExecutionWindow(windows: List<ExecutionWindow>, zone: String, atMs: Long): Boolean =
    windows.isNotEmpty() && nextExecutionWindowAt(windows, zone, atMs) == atMs

/** Custom HH:mm window shared by the UI and schedule_task; the end may be 24:00, and identical start and end are rejected. */
fun customExecutionWindow(start: String, end: String): ExecutionWindow {
    fun minute(raw: String, allowEndOfDay: Boolean): Int {
        val text = raw.trim()
        if (allowEndOfDay && text == "24:00") return 24 * 60
        val clock = normalizedCalendarTimes(listOf(text)).single()
        return LocalTime.parse(clock).let { it.hour * 60 + it.minute }
    }
    val startMinute = minute(start, allowEndOfDay = false)
    val endMinute = minute(end, allowEndOfDay = true)
    require(startMinute != endMinute) { "execution window must have a positive duration" }
    return ExecutionWindow(startMinute, endMinute)
}

fun formatExecutionWindowMinute(minute: Int): String =
    if (minute == 24 * 60) "24:00" else "%02d:%02d".format(java.util.Locale.ROOT, minute / 60, minute % 60)
