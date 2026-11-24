package com.wangzan101.mika.chat.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.capsule.previewFrames
import com.wangzan101.mika.capsule.sameFrame
import com.wangzan101.mika.task.ConfirmationNotifier
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.task.TaskCenterState
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext

/**
 * A task listed on this page together with its virtual screen: [displayId] of -1 means no screen has been created yet (queued, or a pure conversation that has never opened an app).
 * [foreground] is the display name of the app at the top of that screen; null when it has not been looked up yet or when phone operation is not connected. [web] being true means the built-in browser is in use.
 * The screen number is only used for ordering and for fetching frames; it is never shown to the user.
 */
data class ScreenRow(
    val key: String,
    val conversationId: String,
    val goal: String,
    val displayId: Int,
    val state: TaskState,
    val foreground: String? = null,
    val web: Boolean = false,
    /** How many things this task has done so far; when it changes the top app is looked up again. */
    val acted: Int = 0,
)

/** List order: tasks whose screen was created come first (in screen-creation order), tasks without a screen follow (keeping their original order). */
fun orderedScreens(rows: List<ScreenRow>): List<ScreenRow> =
    rows.filter { it.displayId >= 0 }.sortedBy { it.displayId } + rows.filter { it.displayId < 0 }

/**
 * The "background screens" page reached from beside "in progress" in the sidebar: one card per running task, saying what it is doing, which app it is using and its state, and it can expand to show the live frame.
 * Read-only — stopping a task and returning to the conversation still happen in the conversation page and the capsule; tapping a card returns to the conversation the task belongs to.
 *
 * Data comes from each task's own streams (state, screen, frames, things done), not from the service's outward-facing "displayed tasks" copy:
 * screens that are not being displayed while running in parallel are still real. The top app is re-checked only when the task does another thing; there is no polling.
 */
@Composable
fun ScreensScreen(taskState: TaskCenterState, onBack: () -> Unit, onOpenConversation: (String) -> Unit) {
    val context = LocalContext.current
    val runs by taskState.runs.collectAsState()
    var rows by remember { mutableStateOf<List<ScreenRow>>(emptyList()) }
    LaunchedEffect(runs) {
        val list = runs
        if (list.isEmpty()) {
            rows = emptyList()
            return@LaunchedEffect
        }
        // Each task's state, screen, browser and things done each have their own stream: combined, a change in any one of them re-sorts along with it
        combine(list.map { run ->
            combine(run.state, run.displayId, run.webOpen, run.activities) { s, id, web, acts ->
                ScreenRow(run.key, run.conversationId, run.goal, id, s, web = web, acted = acts.size)
            }
        }) { it.toList() }.collect { rows = it }
    }

    // Tasks that have a screen and how many things they have done: only when one of them does another thing are the top apps of the screens looked up again
    val probe = remember(rows) { rows.filter { it.displayId >= 0 }.map { it.displayId to it.acted } }
    var foreground by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    LaunchedEffect(probe) { foreground = foregroundOf(context, probe.map { it.first }) }

    ScreensContent(
        rows = orderedScreens(rows).map { r -> r.copy(foreground = foreground[r.displayId]) },
        onBack = onBack,
        onOpenConversation = onOpenConversation,
        frames = { key -> runs.firstOrNull { it.key == key }?.frames },
    )
}

/**
 * The page without state, for easier testing: [rows] are already sorted and already have the top app filled in.
 * [frames] provides that screen's frames by task key; it is only subscribed while expanded, and only when provided.
 */
@Composable
fun ScreensContent(
    rows: List<ScreenRow>,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    frames: (String) -> StateFlow<Bitmap?>? = { null },
) {
    PageScaffold(ScreensCopy.TITLE, onBack) {
        if (rows.isEmpty()) {
            EmptyNote(ScreensCopy.EMPTY)
            return@PageScaffold
        }
        rows.forEach { row -> ScreenCard(row, frames(row.key), onOpenConversation) }
        FooterNote(ScreensCopy.NOTE)
    }
}

/** One task: what it is doing, which app it is using and its state; tapping this row returns to its conversation, tapping "see screen" expands the live frame. */
@Composable
private fun ScreenCard(row: ScreenRow, frames: StateFlow<Bitmap?>?, onOpenConversation: (String) -> Unit) {
    val c = MikaTheme.colors
    var open by rememberSaveable(row.key) { mutableStateOf(false) }
    GroupCard(Modifier.padding(top = 8.dp)) {
        ListRow(
            MikaIcons.Screens, row.goal.oneLine(), ScreensCopy.place(row),
            first = true, onClick = { onOpenConversation(row.conversationId) },
        ) { RowValue(ScreensCopy.state(row.state)) }
        if (row.displayId >= 0 || row.web) {
            Text(
                if (open) ScreensCopy.HIDE_PREVIEW else ScreensCopy.PREVIEW,
                Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                    .clickable { open = !open }.testTag("screens-preview-${row.key}"),
                style = MikaType.Caption, color = c.accent,
            )
            if (open && frames != null) LiveFrame(frames)
        }
    }
}

/**
 * This screen's live frame: at most 5 per second, and it is not replaced when identical to the previous one (see [previewFrames]). Not subscribed while collapsed.
 * Tap to enlarge to full screen, where you can pinch to zoom and drag — UIs with small text are unreadable in this small patch.
 */
@Composable
private fun LiveFrame(source: StateFlow<Bitmap?>) {
    val frames = remember(source) { source.filterNotNull().previewFrames(same = ::sameFrame) }
    val frame by frames.collectAsState(initial = null)
    val bmp = frame ?: return
    var zoom by remember { mutableStateOf(false) }
    Image(
        bmp.asImageBitmap(), stringResource(R.string.screens_zoom),
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .height(260.dp).clip(MikaShapes.Tile)
            .clickable { zoom = true }.testTag("screens-zoom"),
        contentScale = ContentScale.Fit,
    )
    if (zoom) FrameZoom(bmp) { zoom = false }
}

/** The enlarged screen: full screen, pinch to zoom (1–6×), drag to reach the edges; tap outside or press back to dismiss. */
@Composable
private fun FrameZoom(frame: Bitmap, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(MikaTheme.colors.bg.copy(alpha = 0.96f)).clickable { onDismiss() }
                .testTag("screens-zoom-dialog"),
            contentAlignment = Alignment.Center,
        ) {
            ZoomableFrame(frame, null, Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp))
        }
    }
}

@Composable
internal fun ZoomableFrame(frame: Bitmap, description: String?, modifier: Modifier = Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset = if (scale <= 1f) Offset.Zero else offset + panChange
    }
    Image(
        remember(frame) { frame.asImageBitmap() }, description,
        modifier.transformable(state)
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
        contentScale = ContentScale.Fit,
    )
}

/** Asks the built-in assistant which app is on top of each screen, then maps it to a display name. When the assistant is not connected nothing is looked up (this only affects the foreground line). */
private suspend fun foregroundOf(context: Context, ids: List<Int>): Map<Int, String> {
    if (ids.isEmpty()) return emptyMap()
    val hub = runCatching { PhoneServices.of(context).hub }.getOrNull() ?: return emptyMap()
    return withContext(Dispatchers.IO) {
        ids.mapNotNull { id ->
            val pkg = runCatching {
                val shell = hub.shell.value ?: return@runCatching null
                val task = shell.topTaskId(id)
                if (task < 0) null else shell.packageOfTask(task)
            }.getOrNull()
            pkg?.let { id to ConfirmationNotifier.appLabel(context, it) }
        }.toMap()
    }
}

/** The goal takes up only one line in the list: line breaks and runs of whitespace collapse into a single space. */
private fun String.oneLine(): String = replace(Regex("\\s+"), " ").trim()

/** All text on the page: no screen numbers, pixels, densities or other developer-only details, only what the task is doing, what it is using and how far it has got. */
object ScreensCopy {
    val TITLE: String get() = tr(R.string.screens_title)
    val EMPTY: String get() = tr(R.string.screens_empty)
    val NOTE: String get() = tr(R.string.screens_note)
    val PREVIEW: String get() = tr(R.string.screens_preview)
    val HIDE_PREVIEW: String get() = tr(R.string.screens_hide_preview)

    /** The card's second line: which app (or browser) is in use; there is a separate phrase for not having opened an app yet and for not being able to see it for now. */
    fun place(row: ScreenRow): String = when {
        row.web -> tr(R.string.screens_using_browser)
        row.displayId < 0 -> tr(R.string.screens_no_app)
        row.foreground != null -> tr(R.string.screens_using_app, row.foreground)
        else -> tr(R.string.screens_app_unknown)
    }

    fun state(state: TaskState): String = when (state) {
        TaskState.Idle -> tr(R.string.screens_waiting)
        is TaskState.Running -> tr(R.string.task_running)
        // Waiting only on a condition (lock screen, assistant reconnecting) does not count as needing you; only what requires you to do something on the phone does
        is TaskState.Held -> if (state.reason.needsYou) tr(R.string.screens_needs_you) else tr(R.string.screens_waiting)
        is TaskState.WaitingTakeover, is TaskState.WaitingInput, is TaskState.WaitingConfirmation -> tr(R.string.screens_needs_you)
        is TaskState.Succeeded -> tr(R.string.task_done)
        is TaskState.Failed -> tr(R.string.task_not_done)
        TaskState.Cancelled -> tr(R.string.task_stopped)
    }
}
