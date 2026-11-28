package com.wangzan101.mika.task.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.trPlural
import androidx.compose.ui.res.stringResource
import android.content.ActivityNotFoundException
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.StepItem
import com.wangzan101.mika.ui.components.StepState
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wangzan101.mika.chat.ui.ChatCopy
import com.wangzan101.mika.chat.ui.ChatTokensCopy
import com.wangzan101.mika.settings.ui.UsageCopy
import com.wangzan101.mika.settings.ui.models.use
import com.wangzan101.mika.ui.MarkdownModel
import com.wangzan101.mika.ui.MarkdownText

/**
 * The timeline of one run (read-only): at the top are the goal, the criteria, a one-line summary (tap to see where the time went) and the outcome (details such as
 * model call counts are folded into "More details"); below is the vertical step list, one row per step, and tapping a step with a picture shows the picture from then.
 */
/** How often to re-read the run record while the task is still running. */
private const val LIVE_REFRESH_MS = 1_000L

@Composable
fun RunTimelineScreen(vm: RunTimelineViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    // While still running, re-read the run record once a second (no auto-scroll to the bottom: it stays wherever the user scrolled)
    val ui by produceState<TimelineUi>(TimelineUi.Loading, vm) {
        while (true) {
            val next = withContext(Dispatchers.IO) { vm.load() }
            value = next
            if ((next as? TimelineUi.Ready)?.header?.running != true) break
            delay(LIVE_REFRESH_MS)
        }
    }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saveLog = rememberLauncherForActivityResult(CreateDocument("application/x-ndjson")) { uri ->
        if (uri != null) scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val log = vm.usageLog()
                    checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(log) }
                }.isSuccess
            }
            Toast.makeText(context, if (saved) R.string.timeline_exported else R.string.timeline_export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    PageBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar(stringResource(R.string.timeline_title), left = MikaIcons.Back, leftLabel = stringResource(R.string.back), onLeft = onBack)
            when (val u = ui) {
                TimelineUi.Loading -> Unit
                TimelineUi.Expired -> EmptyNote(TimelineUi.EXPIRED_TEXT, Modifier.padding(top = 80.dp))
                is TimelineUi.Ready -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottom + 16.dp),
                ) {
                    item("header") {
                        HeaderGroup(u.header, u.steps.size) {
                            try {
                                saveLog.launch("mika-usage-${vm.runId}.jsonl")
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, R.string.timeline_export_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    item("trace") { RunTraceSection(vm.runId) }
                    if (u.steps.isNotEmpty()) {
                        item("label") { GroupLabel(trPlural(R.plurals.timeline_total_steps, u.steps.size)) }
                        item("steps") {
                            GroupCard {
                                Column(Modifier.padding(start = 16.dp)) {
                                    u.steps.forEachIndexed { i, s ->
                                        TimelineRow(s, first = i == 0, last = i == u.steps.lastIndex, vm)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderGroup(h: TimelineHeader, stepCount: Int, onExport: () -> Unit) {
    val c = MikaTheme.colors
    var more by rememberSaveable { mutableStateOf(false) }
    // 1. Result overview: see at a glance whether it got done + the goal + duration/steps/Token
    ResultSummaryCard(h, stepCount)
    // 2. Completion criteria (shown only when there are any)
    if (h.criteria.isNotEmpty()) {
        GroupCard {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 13.dp)) {
                val ids = h.confirmedIds
                Text(if (ids == null) stringResource(R.string.timeline_criteria) else
                    stringResource(R.string.chat_confirmed_goals, ids.size, h.criteria.size), style = MikaType.Label, color = c.ink3)
                h.criteria.forEachIndexed { i, text ->
                    Text(
                        if (ids != null) "${if (i + 1 in ids) "✓" else "○"}  $text" else if (h.criteria.size > 1) "${i + 1}. $text" else text, Modifier.padding(top = 4.dp),
                        style = MikaType.Caption, color = c.ink2,
                    )
                }
            }
        }
    }
    // 3. Technical details (collapsed): details such as model round trips, Token and step limit are folded away
    GroupCard {
        ListRow(null, stringResource(R.string.timeline_more), onClick = { more = !more }) {
            RowValue(if (more) stringResource(R.string.collapse) else stringResource(R.string.expand))
            RowChevron()
        }
        if (more) {
            h.metrics?.let { RunMetricsSummary(it) }
            h.metrics?.deepSeekCost?.let { cost ->
                ListRow(null, stringResource(R.string.timeline_cost)) { RowValue(ChatTokensCopy.cost(cost)) }
            }
            h.stepLimit?.let { limit ->
                ListRow(null, stringResource(R.string.timeline_step_limit)) {
                    RowValue(stringResource(R.string.timeline_step_limit_value, h.stepsUsed, limit))
                }
            }
            h.persona?.let { persona ->
                ListRow(null, stringResource(R.string.persona_title)) { RowValue(persona) }
            }
            h.tokenBudget?.let { limit ->
                ListRow(null, stringResource(R.string.timeline_token_budget)) {
                    RowValue(stringResource(R.string.timeline_token_budget_value, UsageCopy.tokens(h.tokensUsed), UsageCopy.tokens(limit)))
                }
            }
            if (h.verdict != stringResource(R.string.verify_none)) ListRow(null, stringResource(R.string.timeline_check)) { RowValue(h.verdict) }
            ListRow(null, stringResource(R.string.timeline_model_calls)) { RowValue(trPlural(R.plurals.times, h.modelCalls)) }
            if (h.usedMemory.isNotEmpty()) {
                ListRow(null, stringResource(R.string.timeline_memory_used)) { RowValue(trPlural(R.plurals.items, h.usedMemory.size)) }
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    h.usedMemory.forEach { memory ->
                        Text("· $memory", Modifier.padding(top = 3.dp), style = MikaType.Hint, color = c.ink2)
                    }
                }
            }
            ListRow(null, stringResource(R.string.timeline_export), note = stringResource(R.string.timeline_export_hint), onClick = onExport) { RowChevron() }
            Text(
                stringResource(R.string.timeline_timing_note),
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                style = MikaType.Hint, color = c.ink3,
            )
        }
    }
}

/** Result overview card: a round status icon on the left + one line of outcome + the goal, then a divider and three columns for duration/steps/Token, so it is clear at a glance whether it got done. */
@Composable
private fun ResultSummaryCard(h: TimelineHeader, stepCount: Int) {
    val c = MikaTheme.colors
    val icon = when (h.outcomeTone) {
        TileTone.Ok -> MikaIcons.Check
        TileTone.Bad -> MikaIcons.Close
        else -> MikaIcons.Info
    }
    val title = if (h.running) stringResource(R.string.timeline_running) else h.outcome
    GroupCard {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val (bg, fg) = h.outcomeTone.colors(c)
            Box(Modifier.size(36.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = fg)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MikaType.BodyStrong, color = c.ink)
                if (h.goal.isNotBlank()) Text(
                    MarkdownModel.toPlainText(h.goal), Modifier.padding(top = 2.dp),
                    style = MikaType.Caption, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.hair))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            SummaryStat(stringResource(R.string.timeline_duration), h.duration ?: "—")
            SummaryStat(stringResource(R.string.metrics_steps), stepCount.toString())
            SummaryStat(UsageCopy.UNIT, UsageCopy.tokens(h.tokensUsed))
        }
    }
}

@Composable
private fun RowScope.SummaryStat(label: String, value: String) {
    val c = MikaTheme.colors
    Column(Modifier.weight(1f)) {
        Text(label, style = MikaType.Hint, color = c.ink3)
        Text(value, Modifier.padding(top = 2.dp), style = MikaType.BodyStrong, color = c.ink, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimelineRow(s: TimelineStep, first: Boolean, last: Boolean, vm: RunTimelineViewModel) {
    val c = MikaTheme.colors
    var open by rememberSaveable(s.step) { mutableStateOf(false) }
    val shot = s.screenshotId
    val failed = s.result?.startsWith(stringResource(R.string.result_error_prefix)) == true
    val hasMore = s.said.isNotEmpty() || s.activities.isNotEmpty() || s.tags.isNotEmpty() || shot != null ||
        s.command != null || s.output != null
    // A step via MCP: the title gives the connector name and does not expose the raw MCP tool name
    val mcpLabels = s.activities.filter { it.kind == "mcp" }.map { it.label }.distinct()
    val title = if (mcpLabels.isNotEmpty()) mcpLabels.joinToString(" · ")
        else listOfNotNull(s.tool, s.detail).joinToString(" · ")
            .ifEmpty { s.activities.firstOrNull()?.let { listOfNotNull(it.label, it.target).joinToString(" · ") }.orEmpty() }
    StepItem(
        if (failed) StepState.Failed else StepState.Done, first, last,
        Modifier.then(if (hasMore) Modifier.clickable { open = !open } else Modifier),
        dotY = 20.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(end = 16.dp, top = 11.dp, bottom = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.step_number, s.step), style = MikaType.Label, color = c.ink3)
                Text(
                    title, Modifier.weight(1f).padding(start = 8.dp),
                    style = MikaType.Body, color = c.ink,
                    // Collapsed, the row stays one line; expanded, the full title is shown instead of staying clipped
                    maxLines = if (open) Int.MAX_VALUE else 1,
                    overflow = if (open) TextOverflow.Visible else TextOverflow.Ellipsis,
                )
                s.result?.let { result ->
                    val (bg, fg) = when {
                        failed -> c.badSoft to c.bad
                        result == stringResource(R.string.result_effective) -> c.okSoft to c.ok
                        else -> c.neutralSoft to c.ink2
                    }
                    Text(
                        result, Modifier.padding(start = 8.dp).background(bg, MikaShapes.Pill).padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MikaType.Hint, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1,
                    )
                }
                if (hasMore) Icon(MikaIcons.Chevron, null, Modifier.size(16.dp).padding(start = 2.dp), tint = c.ink3)
            }
            Text(RunTimelineViewModel.timingLabel(s.timing), Modifier.padding(top = 2.dp), style = MikaType.Hint, color = c.ink3)
            if (open && hasMore) {
                s.command?.let { TextBlock(stringResource(R.string.timeline_command), it) }
                s.output?.let { TextBlock(stringResource(R.string.usage_output), it) }
                if (s.said.isNotEmpty()) {
                    MarkdownText(s.said, MikaType.Caption.copy(color = c.ink2), Modifier.padding(top = 9.dp))
                }
                s.activities.forEach { a ->
                    val action = if (a.count > 1) trPlural(R.plurals.timeline_viewed_times, a.count) else a.label
                    val effect = ChatCopy.effectLabel(a.effect)
                    Text(
                        listOfNotNull(action, a.target?.takeIf(String::isNotBlank), effect.takeIf(String::isNotBlank)).joinToString(" · "),
                        Modifier.padding(top = 3.dp), style = MikaType.Caption, color = c.ink2,
                    )
                }
                if (s.tags.isNotEmpty()) {
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        s.tags.forEach { TagChip(it) }
                    }
                }
                if (shot != null) Shot(vm, shot, s.step)
            }
        }
    }
}

@Composable
private fun TextBlock(label: String, text: String) {
    val c = MikaTheme.colors
    Text(label, Modifier.padding(top = 10.dp), style = MikaType.Label, color = c.ink3)
    SelectionContainer {
        Text(
            text,
            Modifier.padding(top = 3.dp).fillMaxWidth().background(c.fill, MikaShapes.Chip).padding(horizontal = 10.dp, vertical = 8.dp),
            style = MikaType.Hint.copy(fontFamily = FontFamily.Monospace), color = c.ink2,
        )
    }
}

@Composable
private fun TagChip(t: Tag) {
    val c = MikaTheme.colors
    val (bg, fg) = when (t.tone) {
        TagTone.Ok -> c.okSoft to c.ok
        TagTone.Warn -> c.warnSoft to c.warn
        TagTone.Bad -> c.badSoft to c.bad
    }
    Text(
        t.text, Modifier.background(bg, MikaShapes.Pill).padding(horizontal = 8.dp, vertical = 2.dp),
        style = MikaType.Hint, fontWeight = FontWeight.SemiBold, color = fg,
    )
}

/** The picture from then: decoded on a background thread; when the file has already been cleaned up it just says so. */
@Composable
private fun Shot(vm: RunTimelineViewModel, id: Int, step: Int) {
    val image by produceState<ImageBitmap?>(null, id) {
        value = withContext(Dispatchers.IO) { vm.screen(id)?.let { BitmapFactory.decodeFile(it.path) }?.asImageBitmap() }
    }
    val shape = MikaShapes.Chip
    image?.let {
        Image(
            it, stringResource(R.string.timeline_step_screen, step),
            Modifier.padding(top = 8.dp).widthIn(max = 220.dp).clip(shape).background(MikaTheme.colors.fill, shape),
            contentScale = ContentScale.FillWidth,
        )
    }
}
