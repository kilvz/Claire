package com.wangzan101.mika.core.model.usage

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Which time span to show. [Last24Hours] is the rolling last 24 hours (including the hour containing now), bucketed by hour;
 * [LastDays] is the last few local calendar days including today, [Custom] is the local date range [from]..[to] (both inclusive), both bucketed by day,
 * and bucketed by week when the span exceeds [UsageQuery.MAX_DAILY_BARS] days.
 */
sealed interface UsageRange {
    data object Last24Hours : UsageRange
    data class LastDays(val days: Int) : UsageRange
    data class Custom(val from: LocalDate, val to: LocalDate) : UsageRange
}

/** How long one bar of the trend chart is. */
enum class UsageStep { Hour, Day, Week }

/** Totals over a time span. [tokens] is input plus output (cache hits count as input and are not added again). */
data class UsageTotals(
    val input: Long = 0,
    val output: Long = 0,
    val cached: Long = 0,
    val calls: Long = 0,
    val callsWithoutUsage: Long = 0,
) {
    val tokens: Long get() = input + output

    operator fun plus(n: UsageCounts) =
        UsageTotals(input + n.input, output + n.output, cached + n.cached, calls + n.calls, callsWithoutUsage + n.callsWithoutUsage)
}

/**
 * One bar of the trend chart: [start] is the local time this bucket begins (the top of the hour when bucketing by hour, midnight of the first day when bucketing by day or week),
 * [lastDay] is the last day of this bucket when bucketing by week (inclusive; the final bucket may be shorter than 7 days), and null otherwise.
 * [byProvider] is each provider's tokens in this bucket (providers without usage are omitted).
 */
data class UsageBucket(
    val start: LocalDateTime,
    val tokens: Long,
    val byProvider: Map<String, Long> = emptyMap(),
    val lastDay: LocalDate? = null,
)

/** Usage of one purpose within a model; [share] is the proportion of this model (0..1). Purpose is only a dimension of the statistics and is not broken out in the UI. */
data class PurposeUsage(val purpose: UsagePurpose, val totals: UsageTotals, val share: Double)

/** Usage of one model under a provider; [share] is the proportion of this provider. */
data class ModelUsage(val model: String, val totals: UsageTotals, val share: Double, val purposes: List<PurposeUsage>)

/**
 * Usage of one provider; [share] is the proportion of the total. [name] is the current name, or the name left in the ledger when the provider has been deleted ([deleted]).
 * [order] is this provider's fixed ordering (connected ones follow connection order, deleted ones come later, ordered by the earliest ledger entry); the color scheme is derived from it
 * and does not change when the time range changes.
 */
data class ProviderUsage(
    val id: String,
    val name: String,
    val deleted: Boolean,
    val totals: UsageTotals,
    val share: Double,
    val models: List<ModelUsage>,
    val order: Int = 0,
)

/** Statistics for a time span: totals, the trend chart ([step] is the bucket size), and the breakdown by provider › model › purpose, each level sorted by token count descending. */
data class UsageReport(
    val range: UsageRange,
    val step: UsageStep,
    val totals: UsageTotals,
    val series: List<UsageBucket>,
    val providers: List<ProviderUsage>,
) {
    val hourly: Boolean get() = step == UsageStep.Hour

    fun provider(id: String): ProviderUsage? = providers.firstOrNull { it.id == id }
}

/**
 * Computes statistics from the ledger contents. The ledger is stored in UTC whole hours; conversion to local time happens here: each hourly bucket lands on the local day
 * of the moment it starts (day boundaries follow the rules of [LocalDate.atStartOfDay]; a 23- or 25-hour day under daylight saving time still works out correctly);
 * in zones that differ from UTC by half an hour, the hour straddling local midnight counts entirely toward the day it starts on.
 */
object UsageQuery {
    private const val HOUR_MS = UsageLedger.HOUR_MS

    /** Maximum number of bars when bucketing by day; longer spans are bucketed by week (one bar per 7 days starting from the first day). */
    const val MAX_DAILY_BARS = 45

    /**
     * [live] maps the currently connected providers (id → name), ordered by connection order (the iteration order is the ordering);
     * anything not in it counts as deleted, taking its name from the ledger.
     */
    fun report(snapshot: UsageSnapshot, range: UsageRange, now: Instant, zone: ZoneId, live: Map<String, String>): UsageReport {
        val nowHour = Math.floorDiv(now.toEpochMilli(), HOUR_MS)
        val today = now.atZone(zone).toLocalDate()
        // which bar each row falls into; null when outside the range
        val slot: (Long) -> Int?
        val starts: List<LocalDateTime>
        val lastDays: List<LocalDate?>
        val step: UsageStep
        when (range) {
            UsageRange.Last24Hours -> {
                val first = nowHour - 23
                step = UsageStep.Hour
                starts = (0 until 24).map { i -> LocalDateTime.ofInstant(Instant.ofEpochMilli((first + i) * HOUR_MS), zone) }
                lastDays = starts.map { null }
                slot = { h -> (h - first).takeIf { it in 0..23 }?.toInt() }
            }
            is UsageRange.LastDays, is UsageRange.Custom -> {
                val (from, to) = days(range, today)
                val count = ChronoUnit.DAYS.between(from, to).toInt() + 1
                val days = if (count > MAX_DAILY_BARS) 7 else 1
                step = if (days == 7) UsageStep.Week else UsageStep.Day
                val bars = (count + days - 1) / days
                starts = (0 until bars).map { from.plusDays(it.toLong() * days).atStartOfDay() }
                lastDays = (0 until bars).map { i -> if (days == 1) null else minOf(from.plusDays(i * 7L + 6), to) }
                slot = { h ->
                    val d = Instant.ofEpochMilli(h * HOUR_MS).atZone(zone).toLocalDate()
                    if (d < from || d > to) null else ChronoUnit.DAYS.between(from, d).toInt() / days
                }
            }
        }

        val bars = LongArray(starts.size)
        val perBar = List(starts.size) { HashMap<String, Long>() }
        val byKey = HashMap<UsageKey, UsageTotals>()
        val firstSeen = HashMap<String, Long>()
        var total = UsageTotals()
        for (row in snapshot.rows) {
            firstSeen.merge(row.key.providerId, row.hour, ::minOf)
            val i = slot(row.hour) ?: continue
            val t = row.counts.input + row.counts.output
            bars[i] += t
            if (t > 0) perBar[i].merge(row.key.providerId, t, Long::plus)
            byKey[row.key] = (byKey[row.key] ?: UsageTotals()) + row.counts
            total += row.counts
        }

        // fixed ordering: connected ones follow connection order, deleted ones come later, ordered by the earliest ledger time
        val liveOrder = live.keys.withIndex().associate { (i, id) -> id to i }
        val goneOrder = firstSeen.keys.filter { it !in live }.sortedWith(compareBy<String> { firstSeen.getValue(it) }.thenBy { it })
            .withIndex().associate { (i, id) -> id to live.size + i }

        val providers = byKey.entries.groupBy { it.key.providerId }.map { (pid, entries) ->
            val models = entries.groupBy { it.key.model }.map { (model, es) ->
                val mTotal = es.fold(UsageTotals()) { a, e -> a.add(e.value) }
                val purposes = es.groupBy { it.key.purpose }.map { (p, ps) -> p to ps.fold(UsageTotals()) { a, e -> a.add(e.value) } }
                    .sortedWith(compareByDescending<Pair<UsagePurpose, UsageTotals>> { it.second.tokens }
                        .thenByDescending { it.second.calls }.thenBy { it.first.ordinal })
                    .map { (p, t) -> PurposeUsage(p, t, share(t, mTotal)) }
                Triple(model, mTotal, purposes)
            }
            val pTotal = models.fold(UsageTotals()) { a, m -> a.add(m.second) }
            val sortedModels = models.sortedWith(compareByDescending<Triple<String, UsageTotals, List<PurposeUsage>>> { it.second.tokens }
                .thenByDescending { it.second.calls }.thenBy { it.first })
                .map { (m, t, ps) -> ModelUsage(m, t, share(t, pTotal), ps) }
            val name = live[pid] ?: snapshot.providerNames[pid] ?: pid
            ProviderUsage(
                pid, name, deleted = pid !in live, totals = pTotal, share = share(pTotal, total), models = sortedModels,
                order = liveOrder[pid] ?: goneOrder.getValue(pid),
            )
        }.sortedWith(compareByDescending<ProviderUsage> { it.totals.tokens }.thenByDescending { it.totals.calls }.thenBy { it.order })

        return UsageReport(
            range, step, totals = total,
            series = starts.indices.map { i -> UsageBucket(starts[i], bars[i], perBar[i], lastDays[i]) },
            providers = providers,
        )
    }

    /** Which days a daily range covers (both ends inclusive); for a custom range whose ends are reversed, they are swapped. */
    fun days(range: UsageRange, today: LocalDate): Pair<LocalDate, LocalDate> = when (range) {
        is UsageRange.LastDays -> today.minusDays(range.days.coerceAtLeast(1) - 1L) to today
        is UsageRange.Custom -> if (range.from <= range.to) range.from to range.to else range.to to range.from
        UsageRange.Last24Hours -> today.minusDays(1) to today
    }

    private fun UsageTotals.add(o: UsageTotals) =
        UsageTotals(input + o.input, output + o.output, cached + o.cached, calls + o.calls, callsWithoutUsage + o.callsWithoutUsage)

    private fun share(part: UsageTotals, whole: UsageTotals): Double =
        if (whole.tokens <= 0) 0.0 else part.tokens.toDouble() / whole.tokens
}
