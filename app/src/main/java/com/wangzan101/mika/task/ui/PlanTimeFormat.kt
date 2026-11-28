package com.wangzan101.mika.task.ui

import java.text.DateFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

data class Time12Hour(val hour12: Int, val minute: Int, val isPm: Boolean)

/** How long until the next one, split into days, hours and minutes (minutes rounded up; minutes are omitted once a full day is reached), assembled into text by the UI per language. */
data class DurationParts(val days: Long, val hours: Long, val minutes: Long)

/**
 * One entry in the time zone picker. [city] is the localized representative city (falls back to the place name in the ID when unavailable), [displayName] is the localized time zone name,
 * [offsetLabel] is calculated at the moment the index is built (during daylight saving time it is the DST offset).
 */
data class ZoneEntry(
    val id: String,
    val city: String,
    val displayName: String,
    val offsetLabel: String,
    val offsetSeconds: Int,
) {
    internal val haystack: String =
        listOf(id, id.replace('_', ' '), city, displayName, offsetLabel)
            .joinToString("\u0000").lowercase(Locale.ROOT)
}

/**
 * Time display shared by the plan editor and the plans page. Display only; the storage format is unchanged (clock time is still `HH:mm`).
 * The 12/24-hour cycle is passed in by the caller from system settings; the clock pattern is the UI language's short time pattern converted to the requested hour cycle, and where AM/PM goes follows the language.
 */
internal object PlanTimeFormat {
    fun to12Hour(time: LocalTime): Time12Hour {
        val raw = time.hour % 12
        return Time12Hour(hour12 = if (raw == 0) 12 else raw, minute = time.minute, isPm = time.hour >= 12)
    }

    fun from12Hour(hour12: Int, minute: Int, isPm: Boolean): LocalTime {
        require(hour12 in 1..12) { "hour12 must be 1..12, got $hour12" }
        require(minute in 0..59) { "minute must be 0..59, got $minute" }
        return LocalTime.of(hour12 % 12 + if (isPm) 12 else 0, minute)
    }

    /** Clock pattern for [locale], converted to the hour cycle required by [use24Hour]; quoted literal text (such as 'h' in Canadian French) is kept as-is. */
    fun timePattern(locale: Locale, use24Hour: Boolean): String {
        val base = DateTimeFormatterBuilder.getLocalizedDateTimePattern(null, FormatStyle.SHORT, IsoChronology.INSTANCE, locale)
        val hasDayPeriod = unquoted(base).any { it in DAY_PERIOD }
        val out = StringBuilder()
        var quoted = false
        var i = 0
        while (i < base.length) {
            val ch = base[i]
            if (ch == '\'') {
                quoted = !quoted
                out.append(ch)
                i++
                continue
            }
            if (quoted) {
                out.append(ch)
                i++
                continue
            }
            var j = i
            while (j < base.length && base[j] == ch) j++
            when (ch) {
                in HOURS -> out.append(
                    when {
                        use24Hour -> "HH"
                        (ch == 'h' || ch == 'K') && j - i >= 2 -> "hh"
                        else -> "h"
                    },
                )
                in DAY_PERIOD -> if (!use24Hour) out.append('a')
                else -> out.append(base, i, j)
            }
            i = j
        }
        val pattern = out.toString().trim { it.isWhitespace() || Character.isSpaceChar(it) }
        return when {
            use24Hour || hasDayPeriod -> pattern
            dayPeriodLeads(locale) -> "a$pattern"
            else -> "$pattern a"
        }
    }

    fun formatTime(time: LocalTime, use24Hour: Boolean, locale: Locale): String =
        DateTimeFormatter.ofPattern(timePattern(locale, use24Hour), locale).format(time)

    /** Under a 12-hour cycle, whether this language writes AM/PM before the hour (the picker orders its wheel accordingly). */
    fun dayPeriodFirst(locale: Locale): Boolean {
        val p = unquoted(timePattern(locale, use24Hour = false))
        val a = p.indexOf('a')
        return a >= 0 && a < p.indexOf('h')
    }

    /** Localized "AM" and "PM"; index 0 is AM. */
    fun amPm(locale: Locale): List<String> = DateFormatSymbols.getInstance(locale).amPmStrings.toList()

    fun formatFriendlyDate(date: LocalDate, today: LocalDate, todayLabel: String, tomorrowLabel: String, locale: Locale): String =
        when (date) {
            today -> todayLabel
            today.plusDays(1) -> tomorrowLabel
            else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)
        }

    fun durationParts(deltaMs: Long): DurationParts {
        val totalMinutes = (deltaMs.coerceAtLeast(0L) + 59_999L) / 60_000L
        val days = totalMinutes / (24 * 60)
        val hours = totalMinutes / 60 % 24
        return DurationParts(days, hours, if (days > 0) 0 else totalMinutes % 60)
    }

    fun zoneOffsetLabel(zoneId: ZoneId, now: Instant = Instant.now()): String {
        val offset = zoneId.rules.getOffset(now)
        return if (offset.totalSeconds == 0) "GMT" else "GMT$offset"
    }

    /**
     * Time zones selectable in the picker: IANA IDs under geographic regions plus UTC, excluding aliases such as Etc/, SystemV/ and US/; sorted by current offset, then by city.
     * City names come from [cityName] (localized ICU city names on Android); the whole index is built once on a background thread, and search only filters within it.
     */
    fun zoneIndex(
        locale: Locale,
        now: Instant = Instant.now(),
        availableIds: Set<String> = ZoneId.getAvailableZoneIds(),
        cityName: (String) -> String? = { null },
    ): List<ZoneEntry> = availableIds.asSequence()
        .filter { id -> id == "UTC" || REGIONS.any { id.startsWith(it) } }
        .mapNotNull { id -> runCatching { ZoneId.of(id) }.getOrNull() }
        .map { zone ->
            ZoneEntry(
                id = zone.id,
                city = cityName(zone.id)?.takeIf { it.isNotBlank() } ?: zone.id.substringAfterLast('/').replace('_', ' '),
                displayName = zone.getDisplayName(TextStyle.FULL, locale),
                offsetLabel = zoneOffsetLabel(zone, now),
                offsetSeconds = zone.rules.getOffset(now).totalSeconds,
            )
        }
        .sortedWith(compareBy<ZoneEntry> { it.offsetSeconds }.thenBy { it.city })
        .toList()

    /**
     * Filters by ID, city and time zone name; when the input is an offset ("+8", "GMT+8", "UTC+5:30", "+08:00") it filters by exact offset,
     * so "+1" does not also bring up +10 and +11. The currently selected one comes first.
     */
    fun searchZones(index: List<ZoneEntry>, query: String, preferredZoneId: String): List<ZoneEntry> {
        val q = query.trim().lowercase(Locale.ROOT)
        val offset = parseOffsetQuery(q)
        val matched = when {
            q.isEmpty() -> index
            offset != null -> index.filter { it.offsetSeconds == offset }
            else -> index.filter { q in it.haystack }
        }
        val preferred = matched.firstOrNull { it.id == preferredZoneId } ?: return matched
        return listOf(preferred) + matched.filter { it !== preferred }
    }

    private val HOURS = setOf('h', 'H', 'k', 'K')
    private val DAY_PERIOD = setOf('a', 'b', 'B')
    private val REGIONS = listOf(
        "Africa/", "America/", "Antarctica/", "Arctic/", "Asia/", "Atlantic/", "Australia/", "Europe/", "Indian/", "Pacific/",
    )

    /** CJK 12-hour formats write AM/PM before the hour ("AM 9:05"). Only used when this language defaults to a 24-hour cycle and AM/PM must be added. */
    private fun dayPeriodLeads(locale: Locale) = locale.language in setOf("zh", "ja", "ko")

    private val OFFSET_QUERY = Regex("""^(?:gmt|utc)?\s*([+-])\s*(\d{1,2})(?::?(\d{2}))?$""")

    /** "+8", "gmt-5", "utc+5:30", "+0545" -> offset in seconds; returns null when it does not look like an offset. */
    internal fun parseOffsetQuery(query: String): Int? {
        val m = OFFSET_QUERY.matchEntire(query.trim().lowercase(Locale.ROOT)) ?: return null
        val hours = m.groupValues[2].toInt()
        val minutes = m.groupValues[3].ifEmpty { "0" }.toInt()
        if (hours > 18 || minutes > 59) return null
        val total = hours * 3600 + minutes * 60
        return if (m.groupValues[1] == "-") -total else total
    }

    private fun unquoted(pattern: String): String =
        pattern.split('\'').filterIndexed { i, _ -> i % 2 == 0 }.joinToString("")
}
