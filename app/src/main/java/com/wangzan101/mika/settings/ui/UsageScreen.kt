package com.wangzan101.mika.settings.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.core.model.usage.ModelUsage
import com.wangzan101.mika.core.model.usage.ProviderUsage
import com.wangzan101.mika.core.model.usage.UsageBucket
import com.wangzan101.mika.core.model.usage.UsageLedger
import com.wangzan101.mika.core.model.usage.UsageQuery
import com.wangzan101.mika.core.model.usage.UsageRange
import com.wangzan101.mika.core.model.usage.UsageReport
import com.wangzan101.mika.core.model.usage.UsageStep
import com.wangzan101.mika.core.model.usage.UsageTotals
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.IconTile
import com.wangzan101.mika.ui.components.MikaCard
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.theme.MikaColors
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import com.wangzan101.mika.i18n.uiLocale
import kotlin.math.roundToInt
import com.wangzan101.mika.settings.ui.models.pick

/** The five segments at the top of the usage page: the first four are fixed ranges, [Custom] opens the date picker. */
enum class UsagePreset(private val labelRes: Int) {
    Day(R.string.usage_24h), Week(R.string.usage_7d), Month(R.string.usage_30d), Quarter(R.string.usage_90d), Custom(R.string.usage_custom);

    val label: String get() = tr(labelRes)

    /** The range this segment maps to; [Custom] needs [from]..[to] (local dates). */
    fun range(from: LocalDate?, to: LocalDate?): UsageRange = when (this) {
        Day -> UsageRange.Last24Hours
        Week -> UsageRange.LastDays(7)
        Month -> UsageRange.LastDays(30)
        Quarter -> UsageRange.LastDays(90)
        Custom -> if (from != null && to != null) UsageRange.Custom(from, to) else UsageRange.LastDays(7)
    }
}

/** A row in the ranking by model: [share] is the proportion of this ranking set (all providers, or the selected one). */
data class ModelRank(val provider: ProviderUsage, val model: ModelUsage, val share: Double)

/** Text for token usage, how numbers are written, and the small rules the charts use. */
object UsageCopy {
    val TITLE: String get() = tr(R.string.usage_title)
    val NONE: String get() = tr(R.string.usage_none)
    val EMPTY_TITLE: String get() = tr(R.string.usage_empty_title)
    val EMPTY_BODY: String get() = tr(R.string.usage_empty_body)
    val NOTE: String get() = tr(R.string.usage_note)
    val TOTAL: String get() = tr(R.string.usage_total)
    val TREND: String get() = tr(R.string.usage_trend)
    val BY_PROVIDER: String get() = tr(R.string.usage_by_provider)
    val BY_PROVIDER_HINT: String get() = tr(R.string.usage_by_provider_hint)
    val BY_MODEL: String get() = tr(R.string.usage_by_model)
    val ALL: String get() = tr(R.string.usage_all)
    val ALL_PROVIDERS: String get() = tr(R.string.usage_all_providers)
    val OTHERS: String get() = tr(R.string.usage_others)
    val DELETED: String get() = tr(R.string.usage_deleted)
    val CHANGE_DATES: String get() = tr(R.string.usage_change_dates)
    const val UNIT = "Token"

    /** At most how many providers get their own color in charts (the number of [MikaColors.series] entries in the theme); the rest are grouped as "Other" in the trend chart. */
    const val COLORED = 3

    /**
     * Token counts: below ten thousand they are written as-is; above that they use the wan and yi units with three significant digits (rounded half up, trailing zeros dropped),
     * for example 8765, 1.23 wan, 12.3 wan, 123 wan, 1.5 yi; Traditional Chinese uses the traditional forms of the wan and yi characters.
     */
    fun tokens(n: Long): String {
        if (n < 10_000) return n.toString()
        val wan = round3(BigDecimal(n).divide(BigDecimal(10_000)))
        val locale = uiLocale()
        if (locale.language == "zh") {
            val hant = locale.script == "Hant"
            if (wan < BigDecimal(10_000)) return "${wan.stripTrailingZeros().toPlainString()} ${if (hant) "萬" else "万"}"
            val yi = round3(BigDecimal(n).divide(BigDecimal(100_000_000)))
            return "${yi.stripTrailingZeros().toPlainString()} ${if (hant) "億" else "亿"}"
        }
        return formatMetricTokens(n, ::round3)
    }

    /** Chat usage shares the same K/M/B units in every language. Round only for display. */
    fun compactTokens(n: Long): String =
        if (n < 1_000) n.toString() else formatMetricTokens(n) { it.setScale(1, RoundingMode.HALF_UP) }

    private fun formatMetricTokens(n: Long, round: (BigDecimal) -> BigDecimal): String {
        for ((unit, size) in TOKEN_UNITS) {
            val v = round(BigDecimal.valueOf(n).divide(size))
            if (v < BigDecimal.valueOf(1_000) || unit == "B") return "${v.stripTrailingZeros().toPlainString()}$unit"
        }
        return n.toString()
    }

    private val TOKEN_UNITS = listOf("K" to BigDecimal.valueOf(1_000),
        "M" to BigDecimal.valueOf(1_000_000), "B" to BigDecimal.valueOf(1_000_000_000))

    private fun round3(v: BigDecimal): BigDecimal = v.setScale(
        when {
            v < BigDecimal.TEN -> 2
            v < BigDecimal(100) -> 1
            else -> 0
        },
        RoundingMode.HALF_UP,
    )

    /** Value of the settings home page row: the total for the last 7 days, "None yet" when there is nothing. */
    fun settingsValue(weekTokens: Long): String = if (weekTokens <= 0) NONE else tr(R.string.usage_last_7d, tokens(weekTokens))

    fun calls(n: Long) = trPlural(R.plurals.times, n.toInt())

    /** Share: "<1%" when it is under 1% but not zero. */
    fun percent(share: Double): String {
        if (share > 0 && share < 0.01) return "<1%"
        return "${(share * 100).roundToInt()}%"
    }

    fun missing(n: Long) = trPlural(R.plurals.usage_missing, n.toInt())

    fun providerName(p: ProviderUsage) = if (p.deleted) p.name + DELETED else p.name

    fun stepLabel(step: UsageStep) = when (step) {
        UsageStep.Hour -> tr(R.string.usage_hourly)
        UsageStep.Day -> tr(R.string.usage_daily)
        UsageStep.Week -> tr(R.string.usage_weekly)
    }

    /** Local date: the year is omitted for dates in the current year. */
    fun date(d: LocalDate, today: LocalDate): String =
        if (d.year == today.year) tr(R.string.date_month_day, d.monthValue, d.month.getDisplayName(java.time.format.TextStyle.SHORT, uiLocale()), d.dayOfMonth)
        else tr(R.string.date_year_month_day, d.year, d.monthValue, d.month.getDisplayName(java.time.format.TextStyle.SHORT, uiLocale()), d.dayOfMonth)

    /** The line below the range selector, spelling out which days it actually covers. */
    fun rangeText(range: UsageRange, today: LocalDate): String = when (range) {
        UsageRange.Last24Hours -> tr(R.string.usage_past_24h)
        is UsageRange.LastDays -> tr(R.string.usage_until_today, date(UsageQuery.days(range, today).first, today))
        is UsageRange.Custom -> UsageQuery.days(range, today).let { (from, to) ->
            if (from == to) date(from, today) else "${date(from, today)} – ${date(to, today)}"
        }
    }

    /** Ticks under the trend chart: hourly writes "18:00" (the current bucket writes "Now"), daily and weekly write "10/27" (today writes "Today"). */
    fun axis(b: UsageBucket, step: UsageStep, today: LocalDate, last: Boolean = false): String = when {
        step == UsageStep.Hour && last -> tr(R.string.usage_now)
        step == UsageStep.Hour -> "%d:%02d".format(b.start.hour, b.start.minute)
        b.start.toLocalDate() == today -> tr(R.string.usage_today)
        else -> "${b.start.monthValue}/${b.start.dayOfMonth}"
    }

    /** Which period is described when a bar is tapped. */
    fun period(b: UsageBucket, step: UsageStep, today: LocalDate): String = when (step) {
        UsageStep.Hour -> "%d:%02d".format(b.start.hour, b.start.minute)
        UsageStep.Day -> date(b.start.toLocalDate(), today)
        UsageStep.Week -> {
            val first = b.start.toLocalDate()
            val last = b.lastDay ?: first
            if (first == last) date(first, today) else "${date(first, today)} – ${date(last, today)}"
        }
    }

    /** Which color index this provider uses in charts; null for those after the first [COLORED] providers (gray, grouped as "Other" in the trend chart). */
    fun colorSlot(p: ProviderUsage): Int? = p.order.takeIf { it < COLORED }

    /** Top of the grid: the smallest power-of-ten multiple of 1, 2, 4, 6 or 8 that is not below [max], so the tick at half height is an integer too. */
    fun niceMax(max: Long): Long {
        if (max <= 0) return 1
        var scale = 1L
        while (scale * 10 < max) scale *= 10
        return listOf(1L, 2L, 4L, 6L, 8L, 10L).map { it * scale }.first { it >= max }
    }

    /** Ranking by model: with a provider selected only that provider's are listed, otherwise all providers'; sorted by tokens descending, with shares based on this set's total. */
    fun modelRanking(report: UsageReport, providerId: String?): List<ModelRank> {
        val from = providerId?.let { id -> listOfNotNull(report.provider(id)) } ?: report.providers
        val rows = from.flatMap { p -> p.models.map { p to it } }
            .sortedWith(compareByDescending<Pair<ProviderUsage, ModelUsage>> { it.second.totals.tokens }.thenByDescending { it.second.totals.calls })
        val total = rows.sumOf { it.second.totals.tokens }
        return rows.map { (p, m) -> ModelRank(p, m, if (total <= 0) 0.0 else m.totals.tokens.toDouble() / total) }
    }
}

/**
 * Settings › Token usage. Choose the time range and view total usage plus three charts: usage trend (stacked by provider),
 * ranking by provider (tap one to select it, tap again to clear) and ranking by model (the provider can be switched). The selected provider only affects which series lights up in the chart and whose models are listed;
 * the total is always everything. Statistics are recomputed on every ledger entry ([UsageLedger.version]); the local time zone is converted during the computation.
 * A provider's current quota is shown on the provider page ([UsageAllowanceCard]).
 */
@Composable
fun UsageScreen(ledger: UsageLedger, store: ProviderStore, onBack: () -> Unit) {
    var preset by rememberSaveable { mutableStateOf(UsagePreset.Week) }
    // The two ends of the custom range, stored as epoch days (a type rememberSaveable can hold)
    var customFrom by rememberSaveable { mutableStateOf<Long?>(null) }
    var customTo by rememberSaveable { mutableStateOf<Long?>(null) }
    var picking by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val version by ledger.version.collectAsState()
    val live by store.providers.collectAsState()
    val range = preset.range(customFrom?.let(LocalDate::ofEpochDay), customTo?.let(LocalDate::ofEpochDay))
    val zone = ZoneId.systemDefault()
    val report by produceState<UsageReport?>(null, version, range, live) {
        value = withContext(Dispatchers.Default) {
            // Connected providers are passed in the order they were connected, and color assignment is fixed to that order
            UsageQuery.report(ledger.snapshot(), range, Instant.now(), zone, live.associate { it.id to it.name })
        }
    }
    // After the time range changes, fall back to all providers when the selected one has no usage in this period
    LaunchedEffect(report) {
        val r = report ?: return@LaunchedEffect
        if (selected?.let(r::provider) == null) selected = null
    }

    UsageContent(
        report = report, preset = preset, today = LocalDate.now(zone), selected = selected,
        onPreset = { p -> if (p == UsagePreset.Custom) picking = true else preset = p },
        onPickCustom = { picking = true },
        onSelect = { selected = it },
        onBack = onBack,
    )

    if (picking) {
        UsageRangePicker(
            today = LocalDate.now(zone),
            from = customFrom?.let(LocalDate::ofEpochDay), to = customTo?.let(LocalDate::ofEpochDay),
            onPicked = { from, to ->
                customFrom = from.toEpochDay(); customTo = to.toEpochDay()
                preset = UsagePreset.Custom
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * Stateless usage page, easy to test. A null [report] means historical usage is still being computed.
 * When [onPreset] receives [UsagePreset.Custom] the caller opens the date picker; tapping "Change dates" while already on custom opens it as well ([onPickCustom]).
 * [selected] is the selected provider (null means all), changed by tapping a ranking row or a chip through [onSelect].
 */
@Composable
fun UsageContent(
    report: UsageReport?,
    preset: UsagePreset,
    today: LocalDate,
    selected: String?,
    onPreset: (UsagePreset) -> Unit,
    onPickCustom: () -> Unit,
    onSelect: (String?) -> Unit,
    onBack: () -> Unit,
) {
    val c = MikaTheme.colors
    PageScaffold(UsageCopy.TITLE, onBack) {
        SegmentedControl(
            UsagePreset.entries.map { it.label }, preset.ordinal, { onPreset(UsagePreset.entries[it]) },
            tags = UsagePreset.entries.map { "usage-range-${it.name}" },
        )
        if (report == null) return@PageScaffold
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.Center) {
            Text(UsageCopy.rangeText(report.range, today), style = MikaType.Hint, color = c.ink3)
            if (preset == UsagePreset.Custom) {
                Text(" · ", style = MikaType.Hint, color = c.ink3)
                Text(
                    UsageCopy.CHANGE_DATES, Modifier.clickable(role = Role.Button, onClick = onPickCustom).testTag("usage-change-dates"),
                    style = MikaType.Hint, color = c.accent, fontWeight = FontWeight.Medium,
                )
            }
        }
        if (report.providers.isEmpty()) {
            EmptyCard()
            return@PageScaffold
        }
        val pick = selected?.takeIf { report.provider(it) != null }
        TotalsCard(report.totals)

        ChartCard(UsageCopy.TREND, UsageCopy.stepLabel(report.step)) {
            TrendChart(report, pick, today)
        }

        ChartCard(UsageCopy.BY_PROVIDER, UsageCopy.BY_PROVIDER_HINT) {
            val max = report.providers.maxOf { it.totals.tokens }.coerceAtLeast(1)
            Column(Modifier.padding(top = 6.dp)) {
                report.providers.forEach { p ->
                    RankRow(
                        UsageCopy.providerName(p), mono = false, muted = p.deleted, color = seriesColor(c, p),
                        fraction = p.totals.tokens.toFloat() / max, tokens = p.totals.tokens, share = p.share,
                        dim = pick != null && pick != p.id, bold = pick == p.id,
                        onClick = { onSelect(if (pick == p.id) null else p.id) }, tag = "usage-rank-provider-${p.id}",
                    )
                }
            }
        }

        ChartCard(UsageCopy.BY_MODEL, pick?.let(report::provider)?.let(UsageCopy::providerName) ?: UsageCopy.ALL_PROVIDERS) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UsageChip(UsageCopy.ALL, pick == null, { onSelect(null) }, tag = "usage-chip-all")
                report.providers.sortedBy { it.order }.forEach { p ->
                    UsageChip(p.name, pick == p.id, { onSelect(p.id) }, deleted = p.deleted, tag = "usage-chip-${p.id}")
                }
            }
            val models = UsageCopy.modelRanking(report, pick)
            val max = models.maxOfOrNull { it.model.totals.tokens }?.coerceAtLeast(1) ?: 1
            models.forEach { m ->
                RankRow(
                    m.model.model, mono = true, muted = false, color = seriesColor(c, m.provider),
                    fraction = m.model.totals.tokens.toFloat() / max, tokens = m.model.totals.tokens, share = m.share,
                    tag = "usage-rank-model-${m.provider.id}-${m.model.model}",
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        if (report.totals.callsWithoutUsage > 0) {
            FooterNote(UsageCopy.missing(report.totals.callsWithoutUsage), Modifier.testTag("usage-missing"))
        }
        FooterNote(UsageCopy.NOTE)
    }
}

private fun seriesColor(c: MikaColors, p: ProviderUsage): Color = UsageCopy.colorSlot(p)?.let { c.series.getOrNull(it) } ?: c.seriesOther

/** Total usage card: the large number is input plus output, with one row below for input, output, cached hits and call count. */
@Composable
private fun TotalsCard(t: UsageTotals) {
    val c = MikaTheme.colors
    MikaCard {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp)) {
            Text(UsageCopy.TOTAL, style = MikaType.Caption, color = c.ink3)
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.Bottom) {
                Text(UsageCopy.tokens(t.tokens), Modifier.testTag("usage-total"), style = TotalStyle, color = c.ink)
                Text(
                    UsageCopy.UNIT, Modifier.padding(start = 4.dp, bottom = 6.dp),
                    style = MikaType.Value, fontWeight = FontWeight.Medium, color = c.ink2,
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Stat(tr(R.string.usage_input), UsageCopy.tokens(t.input), Modifier.weight(1f))
                Stat(tr(R.string.usage_output), UsageCopy.tokens(t.output), Modifier.weight(1f))
                Stat(tr(R.string.usage_cached), UsageCopy.tokens(t.cached), Modifier.weight(1f))
                Stat(tr(R.string.usage_calls), UsageCopy.calls(t.calls), Modifier.weight(1f))
            }
        }
    }
}

private val TotalStyle = MikaType.Display

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MikaType.Hint, color = MikaTheme.colors.ink3, maxLines = 1)
        Text(
            value, Modifier.padding(top = 2.dp), style = MikaType.Value, fontWeight = FontWeight.SemiBold,
            color = MikaTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A chart card: title on the left, one small line of text on the right. */
@Composable
private fun ChartCard(title: String, hint: String, content: @Composable () -> Unit) {
    val c = MikaTheme.colors
    MikaCard(Modifier.padding(top = 12.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = CardTitle, color = c.ink)
                Text(hint, style = MikaType.Hint, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            content()
        }
    }
}

private val CardTitle = MikaType.Value.copy(fontWeight = FontWeight.SemiBold)

/** When there is no usage: a centered explanation card. */
@Composable
private fun EmptyCard() {
    val c = MikaTheme.colors
    MikaCard(Modifier.testTag("usage-empty")) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconTile(MikaIcons.Chart)
            Text(UsageCopy.EMPTY_TITLE, Modifier.padding(top = 12.dp), style = MikaType.BodyStrong, color = c.ink)
            Text(UsageCopy.EMPTY_BODY, Modifier.padding(top = 6.dp), style = MikaType.Caption, color = c.ink3, textAlign = TextAlign.Center)
        }
    }
}

/**
 * A ranking row: name, horizontal bar (colored by provider), usage and share. [dim] fades the whole row (another provider is selected), [bold] makes the name bold (the selected one);
 * a [muted] name uses the secondary text color (deleted provider).
 */
@Composable
private fun RankRow(
    name: String, mono: Boolean, muted: Boolean, color: Color, fraction: Float, tokens: Long, share: Double,
    dim: Boolean = false, bold: Boolean = false, onClick: (() -> Unit)? = null, tag: String,
) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clip(RowShape).clickable(role = Role.Button, onClick = onClick) else Modifier)
            .alpha(if (dim) 0.4f else 1f)
            .padding(vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            name, Modifier.width(118.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = (if (mono) RankMono else RankText).let { if (bold) it.copy(fontWeight = FontWeight.SemiBold) else it },
            color = if (muted) c.ink3 else c.ink,
        )
        Box(Modifier.weight(1f).height(14.dp)) {
            Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
                val w = (size.width * fraction.coerceIn(0f, 1f)).coerceAtLeast(2.dp.toPx())
                val r = 4.dp.toPx().coerceAtMost(w / 2)
                val path = Path().apply {
                    addRoundRect(RoundRect(0f, 0f, w, size.height, topRightCornerRadius = CornerRadius(r), bottomRightCornerRadius = CornerRadius(r)))
                }
                drawPath(path, color)
            }
        }
        Column(Modifier.width(74.dp), horizontalAlignment = Alignment.End) {
            Text(UsageCopy.tokens(tokens), style = RankValue, color = c.ink, maxLines = 1)
            Text(UsageCopy.percent(share), style = RankShare, color = c.ink3, maxLines = 1)
        }
    }
}

private val RowShape = MikaShapes.Tile
private val RankText = MikaType.Caption
private val RankMono = MikaType.Hint.copy(fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
private val RankValue = MikaType.Caption
private val RankShare = MikaType.Micro

/** Provider switch chip: solid accent fill when selected; a [deleted] name is dimmed and followed by "(deleted)". */
@Composable
private fun UsageChip(text: String, selected: Boolean, onClick: () -> Unit, deleted: Boolean = false, tag: String) {
    val c = MikaTheme.colors
    val fg = when {
        selected -> c.onAccent
        deleted -> c.ink3
        else -> c.ink
    }
    Box(
        Modifier.height(34.dp)
            .then(if (selected) Modifier.background(c.accent, MikaShapes.Pill) else Modifier.cardSurface(c, MikaShapes.Pill))
            .clip(MikaShapes.Pill)
            .selectable(selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (deleted) text + UsageCopy.DELETED else text, maxLines = 1, color = fg, style = ChipText)
    }
}

private val ChipText = MikaType.Body.copy(fontWeight = FontWeight.Medium)

/** One segment in the trend chart: each provider with its own color gets a segment ([slot] is the color index), and the rest are merged into "Other" ([slot] is null). */
private data class StackPart(val slot: Int?, val tokens: Long)

/**
 * Usage trend: one bar per bucket, stacked by provider (colored ones at the bottom, "Other" on top), with 2dp between segments and between bars, and 4dp rounded corners on top of each bar.
 * A column on the right holds the ticks for the two dashed grid lines (the integer top and half). Tapping a bar writes that bucket's total and each provider's usage above it, tapping again collapses it;
 * when a provider is selected, the other providers' segments fade to a quarter opacity.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrendChart(report: UsageReport, selected: String?, today: LocalDate) {
    val c = MikaTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val series = report.series
    if (series.isEmpty()) return
    val byId = report.providers.associateBy { it.id }
    val colored = report.providers.filter { UsageCopy.colorSlot(it) != null }.sortedBy { it.order }
    val hasOthers = report.providers.any { UsageCopy.colorSlot(it) == null && it.totals.tokens > 0 }
    val selectedSlot = selected?.let(byId::get)?.let { UsageCopy.colorSlot(it) ?: -1 }
    // Bottom to top: colored providers in order, "Other" on the very top
    val slots: List<Int?> = colored.map(UsageCopy::colorSlot) + if (hasOthers) listOf(null) else emptyList()
    val stacks = series.map { b ->
        val bySlot = b.byProvider.entries.groupBy { byId[it.key]?.let(UsageCopy::colorSlot) }
        slots.map { s -> StackPart(s, bySlot[s].orEmpty().sumOf { it.value }) }.filter { it.tokens > 0 }
    }
    val nice = UsageCopy.niceMax(series.maxOf { it.tokens })
    var tapped by remember(series) { mutableStateOf<Int?>(null) }
    val gutter = 38.dp

    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        colored.forEach { p -> LegendItem(c.series[UsageCopy.colorSlot(p)!!], UsageCopy.providerName(p)) }
        if (hasOthers) LegendItem(c.seriesOther, UsageCopy.OTHERS)
    }
    Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(132.dp)) {
        Canvas(
            Modifier.fillMaxSize().testTag("usage-chart")
                .semantics { contentDescription = "${UsageCopy.TREND}，${UsageCopy.stepLabel(report.step)}" }
                .pointerInput(series, rtl) {
                    detectTapGestures { o ->
                        val plot = size.width - gutter.toPx()
                        val logicalX = if (rtl) size.width - o.x else o.x
                        if (logicalX > plot) return@detectTapGestures
                        val i = (logicalX / (plot / series.size)).toInt().coerceIn(0, series.lastIndex)
                        tapped = if (tapped == i) null else i
                    }
                },
        ) {
            val plot = size.width - gutter.toPx()
            val plotStart = if (rtl) gutter.toPx() else 0f
            val h = size.height
            val gap = 2.dp.toPx()
            val n = series.size
            val colW = ((plot - gap * (n - 1)) / n).coerceAtLeast(1f)
            val step = if (n > 1) (plot - colW) / (n - 1) else 0f
            val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
            listOf(0f, 0.5f).forEach { y -> drawLine(c.grid, Offset(plotStart, h * y), Offset(plotStart + plot, h * y), 1.dp.toPx(), pathEffect = dash) }
            drawLine(c.grid, Offset(plotStart, h), Offset(plotStart + plot, h), 1.dp.toPx())
            stacks.forEachIndexed { i, segs ->
                val x = plotStart + if (rtl) plot - colW - i * step else i * step
                var bottom = h
                segs.forEachIndexed { k, s ->
                    val top = k == segs.lastIndex
                    val segH = h * s.tokens / nice
                    val drawn = if (top) segH else (segH - gap).coerceAtLeast(0.5f)
                    val base = if (s.slot != null) c.series.getOrNull(s.slot) ?: c.seriesOther else c.seriesOther
                    val color = if (selectedSlot != null && selectedSlot != (s.slot ?: -1)) base.copy(alpha = base.alpha * 0.25f) else base
                    if (top) {
                        val r = 4.dp.toPx().coerceAtMost(colW / 2).coerceAtMost(drawn)
                        drawPath(
                            Path().apply {
                                addRoundRect(RoundRect(x, bottom - drawn, x + colW, bottom, topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r)))
                            },
                            color,
                        )
                    } else {
                        drawRect(color, Offset(x, bottom - drawn), Size(colW, drawn))
                    }
                    bottom -= segH
                }
            }
        }
        // Grid ticks are written into the column on the right
        GridLabel(UsageCopy.tokens(nice), Modifier.align(Alignment.TopEnd).offset(y = (-7).dp))
        GridLabel(UsageCopy.tokens(nice / 2), Modifier.align(Alignment.CenterEnd).offset(y = (-7).dp))
        tapped?.let { i -> TrendTip(series[i], report, i, gutter, today) }
    }
    Box(Modifier.fillMaxWidth().padding(top = 6.dp, end = gutter)) {
        val style = MikaType.Hint
        Text(UsageCopy.axis(series.first(), report.step, today), Modifier.align(Alignment.CenterStart), style = style, color = c.ink3)
        if (series.size > 2) {
            Text(UsageCopy.axis(series[series.size / 2], report.step, today), Modifier.align(Alignment.Center), style = style, color = c.ink3)
        }
        if (series.size > 1) {
            Text(UsageCopy.axis(series.last(), report.step, today, last = true), Modifier.align(Alignment.CenterEnd), style = style, color = c.ink3)
        }
    }
}

@Composable
private fun LegendItem(color: Color, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(9.dp).background(color, LegendShape))
        Text(name, style = MikaType.Hint, color = MikaTheme.colors.ink2, maxLines = 1)
    }
}

private val LegendShape = MikaShapes.Micro

@Composable
private fun GridLabel(text: String, modifier: Modifier) {
    Text(text, modifier.width(34.dp), style = GridText, color = MikaTheme.colors.ink3, textAlign = TextAlign.End, maxLines = 1)
}

private val GridText = MikaType.Micro

/** Tooltip when a bar is tapped: the period, the total, and one line per provider below (by usage, highest first); it lines up horizontally with that bar and never leaves the chart's left or right edge. */
@Composable
private fun TrendTip(b: UsageBucket, report: UsageReport, index: Int, gutter: Dp, today: LocalDate) {
    val c = MikaTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val rows = b.byProvider.entries.sortedByDescending { it.value }
    Column(
        Modifier.layout { m, cons ->
            val p = m.measure(cons.copy(minWidth = 0, minHeight = 0))
            layout(cons.maxWidth, p.height) {
                val plot = cons.maxWidth - gutter.roundToPx()
                val logicalCenter = plot / report.series.size.toFloat() * (index + 0.5f)
                val center = if (rtl) cons.maxWidth - logicalCenter else logicalCenter
                p.place((center - p.width / 2f).toInt().coerceIn(0, (cons.maxWidth - p.width).coerceAtLeast(0)), 0)
            }
        }.background(c.ink, TipShape).padding(horizontal = 9.dp, vertical = 6.dp).testTag("usage-chart-tip"),
    ) {
        Text(
            "${UsageCopy.period(b, report.step, today)} · ${UsageCopy.tokens(b.tokens)}",
            style = TipText, fontWeight = FontWeight.SemiBold, color = c.bg, maxLines = 1,
        )
        rows.forEach { (id, v) ->
            val name = report.provider(id)?.let(UsageCopy::providerName) ?: id
            Text("$name　${UsageCopy.tokens(v)}", style = TipText, color = c.bg, maxLines = 1)
        }
    }
}

private val TipShape = MikaShapes.Tile
private val TipText = MikaType.Micro

/** Date picker for a custom range: only days the ledger still keeps (the last [UsageLedger.KEEP_DAYS] days) up to today can be picked, and it can only be viewed once both ends are selected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsageRangePicker(today: LocalDate, from: LocalDate?, to: LocalDate?, onPicked: (LocalDate, LocalDate) -> Unit, onDismiss: () -> Unit) {
    // Date picker dates are milliseconds at UTC midnight
    fun millis(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
    val earliest = today.minusDays(UsageLedger.KEEP_DAYS.toLong())
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = (from ?: today.minusDays(6)).let(::millis),
        initialSelectedEndDateMillis = (to ?: today).let(::millis),
        yearRange = earliest.year..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = date(utcTimeMillis).let { it >= earliest && it <= today }
            override fun isSelectableYear(year: Int) = year in earliest.year..today.year
        },
    )
    val start = state.selectedStartDateMillis
    val end = state.selectedEndDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton({ if (start != null && end != null) onPicked(date(start), date(end)) }, enabled = start != null && end != null) {
                Text(tr(R.string.view))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(tr(R.string.cancel)) } },
    ) {
        DateRangePicker(state, Modifier.weight(1f), showModeToggle = false)
    }
}
