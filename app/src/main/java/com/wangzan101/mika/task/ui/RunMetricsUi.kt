package com.wangzan101.mika.task.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.core.run.record.RunMetrics
import com.wangzan101.mika.core.run.record.RunMetricsStore
import com.wangzan101.mika.core.run.record.RunStats
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowActions
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToLong
import com.wangzan101.mika.settings.ui.UsageCopy

/** Wording for the run summary and run stats. Token counts use the same notation as the Token usage page ([UsageCopy.tokens]). */
object RunMetricsCopy {
    val MODEL: String get() = tr(R.string.metrics_model)
    val OBSERVE: String get() = tr(R.string.metrics_observe)
    val ACT: String get() = tr(R.string.metrics_act)
    val OTHER: String get() = tr(R.string.metrics_other)
    val STATS_TITLE: String get() = tr(R.string.metrics_title)
    val STATS_SUBTITLE: String get() = tr(R.string.metrics_sub)
    val STATS_EMPTY: String get() = tr(R.string.metrics_empty)
    val STATS_NOTE: String get() = tr(R.string.metrics_note, RunMetricsStore.DEFAULT_KEEP)
    val COPY: String get() = tr(R.string.metrics_copy)
    val SHARE: String get() = tr(R.string.metrics_share)

    /** Statistics range: the last 10 / 30 / 100 runs, or everything stored (null). */
    val RANGES: List<Int?> = listOf(10, 30, 100, null)
    val RANGE_LABELS get() = listOf(tr(R.string.metrics_last_10), trPlural(R.plurals.metrics_runs, 30), trPlural(R.plurals.metrics_runs, 100), tr(R.string.usage_all))

    /** Duration: within a minute with 0.1-second precision ("3.2 seconds", "45 seconds"), above that "2 min 13 s", and from an hour up "1 h 5 min". */
    fun duration(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        if (t < 59_950) {
            val tenths = Math.round(t / 100.0)
            return tr(R.string.duration_seconds_decimal, if (tenths % 10 == 0L) "${tenths / 10}" else String.format(Locale.ROOT, "%.1f", tenths / 10.0))
        }
        val s = Math.round(t / 1000.0)
        if (s < 3600) return tr(R.string.duration_m_s, s / 60, s % 60)
        return tr(R.string.duration_h_m, s / 3600, s % 3600 / 60)
    }

    /** The line at the top of the timeline: "2 min 13 s total · 14 round trips with the model · 86k Token · first feedback at 3.2 s". */
    fun summaryLine(m: RunMetrics): String = listOfNotNull(
        tr(R.string.metrics_total, duration(m.totalMs)),
        trPlural(R.plurals.metrics_round_trips, m.modelCalls),
        "${UsageCopy.tokens(m.tokens)} ${UsageCopy.UNIT}",
        m.firstFeedbackMs?.let { tr(R.string.metrics_first_feedback_at, duration(it)) },
    ).joinToString(" · ")

    /** Duration and its share of the total duration. */
    fun share(ms: Long, totalMs: Long): String =
        if (totalMs <= 0) duration(ms) else "${duration(ms)} · ${UsageCopy.percent(ms.toDouble() / totalMs)}"

    /** The breakdown after expanding: where the time went, tokens, steps and counts. */
    fun breakdown(m: RunMetrics): List<Pair<String, String>> = buildList {
        add(MODEL to share(m.modelMs, m.totalMs))
        add(OBSERVE to share(m.observeMs, m.totalMs))
        add(ACT to share(m.actMs, m.totalMs))
        add(OTHER to share(m.otherMs, m.totalMs))
        if (m.retryWaitMs > 0) add(tr(R.string.metrics_retry_wait) to duration(m.retryWaitMs))
        add(tr(R.string.metrics_input_tokens) to UsageCopy.tokens(m.inputTokens))
        add(tr(R.string.metrics_output_tokens) to UsageCopy.tokens(m.outputTokens))
        add(tr(R.string.usage_cached) to UsageCopy.tokens(m.cachedTokens))
        if (m.callsWithoutUsage > 0) add(tr(R.string.metrics_no_usage) to trPlural(R.plurals.times, m.callsWithoutUsage))
        m.allCalls?.let { add(tr(R.string.metrics_all_calls) to trPlural(R.plurals.metrics_all_calls_value, it)) }
        m.allHttpCalls?.let { add(tr(R.string.metrics_all_http_calls) to trPlural(R.plurals.times, it)) }
        add(tr(R.string.metrics_steps) to trPlural(R.plurals.chat_steps_n, m.steps))
        add(tr(R.string.metrics_observe_act) to tr(R.string.metrics_observe_act_value, m.observeCalls, m.actCalls))
    }

    /** The rows of the run statistics. */
    fun statsRows(s: RunStats): List<Pair<String, String>> = listOf(
        tr(R.string.metrics_counted) to trPlural(R.plurals.metrics_n_runs, s.runs),
        tr(R.string.metrics_success_rate) to tr(R.string.metrics_success_value, UsageCopy.percent(s.successRate), s.succeeded, s.runs),
        tr(R.string.metrics_median_time) to duration(s.medianTotalMs),
        tr(R.string.metrics_median_feedback) to (s.medianFirstFeedbackMs?.let(::duration) ?: "—"),
        tr(R.string.metrics_avg_round_trips) to tr(R.string.metrics_avg_round_trips_value, String.format(Locale.ROOT, "%.1f", s.avgModelCalls)),
        tr(R.string.metrics_avg_tokens) to UsageCopy.tokens(s.avgTokens.roundToLong()),
    )

    /** Where the time went: each item's sum as a share of the total duration sum. */
    fun splitRows(s: RunStats): List<Pair<String, String>> = listOf(
        MODEL to UsageCopy.percent(ratio(s.modelMs, s.totalMs)),
        OBSERVE to UsageCopy.percent(ratio(s.observeMs, s.totalMs)),
        ACT to UsageCopy.percent(ratio(s.actMs, s.totalMs)),
        OTHER to UsageCopy.percent(ratio(s.otherMs, s.totalMs)),
    )

    fun copied(n: Int) = trPlural(R.plurals.metrics_copied, n)
    val EXPORT_FAILED: String get() = tr(R.string.metrics_export_failed)

    private fun ratio(a: Long, b: Long) = if (b <= 0) 0.0 else a.toDouble() / b
}

/** The one-line summary at the top of "View process"; tapping expands where the time went, tokens and steps. */
@Composable
fun RunMetricsSummary(m: RunMetrics) {
    val c = MikaTheme.colors
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.testTag("run-metrics-summary")
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Text(RunMetricsCopy.summaryLine(m), style = MikaType.Caption, color = c.ink2)
        Text(if (open) tr(R.string.collapse) else tr(R.string.metrics_where_time), Modifier.padding(top = 2.dp), style = MikaType.Hint, color = c.accent)
    }
    if (open) RunMetricsCopy.breakdown(m).forEach { (k, v) -> ListRow(null, k) { RowValue(v) } }
}

/** Developer options › Run stats: reads the latest few summaries from the summary store and computes the average and median; these runs' summaries can be copied or shared as JSON. */
@Composable
fun RunStatsScreen(store: RunMetricsStore, onBack: () -> Unit) {
    val context = LocalContext.current
    var range by rememberSaveable { mutableIntStateOf(1) }
    val all by produceState<List<RunMetrics>?>(null, store) { value = withContext(Dispatchers.IO) { store.all() } }
    val picked = remember(all, range) { all?.let { list -> RunMetricsCopy.RANGES[range]?.let { list.takeLast(it) } ?: list } }
    val stats = remember(picked) { picked?.takeIf { it.isNotEmpty() }?.let(RunStats::of) }
    RunStatsContent(
        stats, range, { range = it },
        onCopy = { picked?.let { copyJson(context, it) } },
        onShare = { picked?.let { shareJson(context, it) } },
        onBack = onBack,
        loaded = all != null,
    )
}

@Composable
fun RunStatsContent(
    stats: RunStats?,
    range: Int,
    onRange: (Int) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit,
    loaded: Boolean = true,
) {
    PageScaffold(RunMetricsCopy.STATS_TITLE, onBack) {
        SegmentedControl(RunMetricsCopy.RANGE_LABELS, range, onRange, tags = RunMetricsCopy.RANGES.map { "run-stats-range-${it ?: "all"}" })
        if (!loaded) return@PageScaffold
        if (stats == null) {
            EmptyNote(RunMetricsCopy.STATS_EMPTY, Modifier.padding(top = 48.dp))
            return@PageScaffold
        }
        GroupLabel(tr(R.string.metrics_overview))
        GroupCard { RunMetricsCopy.statsRows(stats).forEachIndexed { i, (k, v) -> ListRow(null, k, first = i == 0) { RowValue(v) } } }
        GroupLabel(tr(R.string.metrics_time_split))
        GroupCard { RunMetricsCopy.splitRows(stats).forEachIndexed { i, (k, v) -> ListRow(null, k, first = i == 0) { RowValue(v) } } }
        GroupLabel(tr(R.string.metrics_export))
        GroupCard {
            ListRow(null, trPlural(R.plurals.metrics_summary_of, stats.runs), tr(R.string.metrics_summary_sub), first = true)
            RowActions {
                MikaButton(RunMetricsCopy.COPY, onCopy, Modifier.testTag("run-stats-copy"), size = ButtonSize.Small)
                MikaButton(RunMetricsCopy.SHARE, onShare, Modifier.testTag("run-stats-share"), kind = ButtonKind.Tonal, size = ButtonSize.Small)
            }
        }
        FooterNote(RunMetricsCopy.STATS_NOTE)
    }
}

private fun copyJson(context: Context, list: List<RunMetrics>) {
    val ok = runCatching {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(RunMetricsCopy.STATS_TITLE, RunMetricsStore.export(list)))
    }.isSuccess
    Toast.makeText(context, if (ok) RunMetricsCopy.copied(list.size) else RunMetricsCopy.EXPORT_FAILED, Toast.LENGTH_SHORT).show()
}

private fun shareJson(context: Context, list: List<RunMetrics>) {
    runCatching {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, RunMetricsStore.export(list))
        context.startActivity(Intent.createChooser(send, RunMetricsCopy.STATS_TITLE))
    }.onFailure { Toast.makeText(context, RunMetricsCopy.EXPORT_FAILED, Toast.LENGTH_SHORT).show() }
}
