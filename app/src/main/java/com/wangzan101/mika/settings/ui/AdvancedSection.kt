package com.wangzan101.mika.settings.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.cap.sandbox.RootfsInstaller
import com.wangzan101.mika.cap.sandbox.RuntimePreparer
import com.wangzan101.mika.cap.sandbox.SandboxState
import kotlinx.coroutines.launch
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.SandboxUsage
import com.wangzan101.mika.cap.sandbox.SandboxUsageReader
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.cap.sandbox.WorkspaceUsage
import com.wangzan101.mika.chat.WorkspaceResolver
import com.wangzan101.mika.ui.terminal.TerminalLauncher
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import com.wangzan101.mika.chat.ui.ChatCopy
import com.wangzan101.mika.memory.ui.Link

/**
 * Copy for Settings › Files and compute environment (pure functions, so they are easy to test). The runtime is an implementation detail invisible to users (a product decision):
 * the wording only says "scripts", "processing files" and "runtime", and never technical terms such as sandbox, Linux, PRoot or Alpine.
 */
object SandboxCopy {
    val NETWORK_TITLE: String get() = tr(R.string.sandbox_network)
    val NETWORK_SUBTITLE: String get() = tr(R.string.sandbox_network_sub)
    val NETWORK_EXPLAIN: String get() = tr(R.string.sandbox_network_explain)
    val USAGE_TITLE: String get() = tr(R.string.sandbox_usage)
    val USAGE_NOTE: String get() = tr(R.string.sandbox_usage_note)
    val CLEAR: String get() = tr(R.string.sandbox_usage_clear)
    val CLEAR_TITLE: String get() = tr(R.string.sandbox_usage_clear_title)
    val CLEAR_ALL: String get() = tr(R.string.sandbox_usage_clear_all)
    val CLEAR_ALL_TITLE: String get() = tr(R.string.sandbox_usage_clear_all_title)
    val CLEAR_ALL_BODY: String get() = tr(R.string.sandbox_usage_clear_all_body)

    fun clearBody(w: WorkspaceUsage): String = tr(R.string.sandbox_usage_clear_body, workspaceTitle(w))

    /** The name shown for a workspace row: project workspaces state that the project shares it; ordinary conversations use the title, and deleted ones say so. */
    fun workspaceTitle(w: WorkspaceUsage): String {
        val name = w.title?.let(ChatCopy::plainTitle)?.takeIf(String::isNotBlank) ?: tr(R.string.sandbox_usage_deleted_chat)
        return if (w.projectId != null) "${tr(R.string.chat_project_shared)} · $name" else name
    }

    fun usageRows(u: SandboxUsage): List<Pair<String, Long>> = listOf(
        tr(R.string.sandbox_usage_total) to u.total,
        tr(R.string.sandbox_usage_runtime) to u.runtime,
        tr(R.string.sandbox_usage_skills) to u.skills,
        tr(R.string.sandbox_usage_workspaces) to u.workspacesTotal,
    )

    /** Write a byte count in units people can read. */
    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(Locale.ROOT, bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.1f MB".format(Locale.ROOT, bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> "%.0f KB".format(Locale.ROOT, bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }
    val ISOLATION_UNAVAILABLE: String get() = tr(R.string.sandbox_isolation_unavailable)
    val RESET_TITLE: String get() = tr(R.string.sandbox_reset_title)
    val RESET_BODY: String get() = tr(R.string.sandbox_reset_body)

    /** The line under "Reset runtime": normally explains what will happen, and states things truthfully while preparing or after preparation failed. */
    fun resetSubtitle(s: SandboxStatus): String = when (s) {
        is SandboxStatus.Installing -> tr(R.string.runtime_preparing)
        is SandboxStatus.Broken -> tr(R.string.sandbox_broken, s.reason)
        else -> tr(R.string.sandbox_reset_sub)
    }
}

/**
 * Content of the "Files and compute environment" page wired to real state: the network switch reads and writes [SandboxState]; the proxy is changed uniformly on the advanced and other pages, so it is not duplicated here;
 * used space is read once in the background when the page opens, clearing goes through [SandboxUsageReader] (conversations with a running task are not cleared); after a reset the runtime is prepared again in the background right away.
 */
@Composable
fun AdvancedSettings() {
    val context = LocalContext.current
    val state = remember { SandboxState.get(context) }
    val status by state.status.collectAsState()
    var network by remember { mutableStateOf(state.networkAllowed) }
    val app = context.applicationContext as MikaApp
    val usageReader = remember {
        SandboxUsageReader(
            SandboxDirs(context),
            { app.conversations.list(includeArchived = true) },
            { app.taskState.activeConversations.value },
            { app.projects.projects.value },
            WorkspaceResolver.resolver(context),
        )
    }
    var usage by remember { mutableStateOf<SandboxUsage?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        usage = withContext(Dispatchers.IO) { runCatching { usageReader.read() }.getOrNull() }
    }
    val scope = rememberCoroutineScope()
    fun clearing(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }
            reload++
        }
    }
    AdvancedSection(
        status = status, networkAllowed = network, isolationIssue = state.isolationIssue != null,
        onNetworkChange = { state.networkAllowed = it; network = it },
        usage = usage,
        onOpenTerminal = { w ->
            val pid = w.projectId
            val cid = w.conversationId
            if (pid != null) TerminalLauncher.openWorkspace(context, WorkspaceKey.Project(pid), w.title)
            else if (cid != null) TerminalLauncher.open(context, cid, w.title)
        },
        onClearWorkspace = { w -> clearing { usageReader.clear(w.dirName) } },
        onClearAll = { clearing { usageReader.clearAll() } },
        onReset = {
            scope.launch {
                RootfsInstaller.create(context).reset()
                RuntimePreparer.ensure(context, retryFailed = true)
                reload++
            }
        },
    )
}

/** The stateless "Files and compute environment" page content, for testing. On unsupported devices the whole group is hidden. */
@Composable
fun AdvancedSection(
    status: SandboxStatus,
    networkAllowed: Boolean,
    isolationIssue: Boolean,
    onNetworkChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    /** Used space; null while it has not been read yet (the group is hidden then). */
    usage: SandboxUsage? = null,
    onOpenTerminal: (WorkspaceUsage) -> Unit = {},
    onClearWorkspace: (WorkspaceUsage) -> Unit = {},
    onClearAll: () -> Unit = {},
) {
    if (status is SandboxStatus.Unsupported) return
    var confirming by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf<WorkspaceUsage?>(null) }
    var clearingAll by remember { mutableStateOf(false) }

    GroupCard {
        ListRow(MikaIcons.Link, SandboxCopy.NETWORK_TITLE, SandboxCopy.NETWORK_SUBTITLE, first = true) {
            MikaSwitch(networkAllowed, onNetworkChange, Modifier.testTag("sandbox-network"))
        }
        Note(SandboxCopy.NETWORK_EXPLAIN, if (networkAllowed) MikaTheme.colors.warn else MikaTheme.colors.ink3)
        if (isolationIssue) Note(SandboxCopy.ISOLATION_UNAVAILABLE, MikaTheme.colors.bad)
        ListRow(MikaIcons.Close, tr(R.string.sandbox_reset), SandboxCopy.resetSubtitle(status), titleColor = MikaTheme.colors.bad, tone = TileTone.Bad,
            onClick = if (status is SandboxStatus.Installing) null else ({ confirming = true }))
    }

    usage?.let { u -> UsageGroup(u, onOpenTerminal, onClear = { clearing = it }, onClearAll = { clearingAll = true }) }

    clearing?.let { w ->
        MikaDialog(
            onDismissRequest = { clearing = null },
            title = { Text(SandboxCopy.CLEAR_TITLE) },
            text = { Text(SandboxCopy.clearBody(w)) },
            confirmButton = { TextButton({ clearing = null; onClearWorkspace(w) }) { Text(SandboxCopy.CLEAR, color = MikaTheme.colors.bad) } },
            dismissButton = { TextButton({ clearing = null }) { Text(tr(R.string.cancel)) } },
        )
    }
    if (clearingAll) {
        MikaDialog(
            onDismissRequest = { clearingAll = false },
            title = { Text(SandboxCopy.CLEAR_ALL_TITLE) },
            text = { Text(SandboxCopy.CLEAR_ALL_BODY) },
            confirmButton = { TextButton({ clearingAll = false; onClearAll() }) { Text(SandboxCopy.CLEAR, color = MikaTheme.colors.bad) } },
            dismissButton = { TextButton({ clearingAll = false }) { Text(tr(R.string.cancel)) } },
        )
    }

    if (confirming) {
        MikaDialog(
            onDismissRequest = { confirming = false },
            title = { Text(SandboxCopy.RESET_TITLE) },
            text = { Text(SandboxCopy.RESET_BODY) },
            confirmButton = { TextButton({ confirming = false; onReset() }) { Text(tr(R.string.reset), color = MikaTheme.colors.bad) } },
            dismissButton = { TextButton({ confirming = false }) { Text(tr(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Note(text: String, color: Color) {
    Text(text, Modifier.fillMaxWidth().padding(start = 60.dp, end = 16.dp, bottom = 10.dp), style = MikaType.Hint, color = color)
}

/**
 * Used space: how much the total, the runtime, skills and conversation workspaces each take; below, workspaces are listed per conversation (largest first), tapping a row clears it (after confirmation),
 * conversations with a running task say they cannot be cleared yet; the last row clears every workspace.
 */
@Composable
private fun UsageGroup(
    u: SandboxUsage,
    onOpenTerminal: (WorkspaceUsage) -> Unit,
    onClear: (WorkspaceUsage) -> Unit,
    onClearAll: () -> Unit,
) {
    val c = MikaTheme.colors
    GroupLabel(SandboxCopy.USAGE_TITLE)
    GroupCard {
        SandboxCopy.usageRows(u).forEachIndexed { i, (name, bytes) ->
            ListRow(null, name, first = i == 0) { RowValue(SandboxCopy.size(bytes)) }
        }
    }
    if (u.workspaces.isNotEmpty()) {
        GroupCard(Modifier.padding(top = 8.dp)) {
            u.workspaces.forEachIndexed { i, w ->
                ListRow(
                    null, SandboxCopy.workspaceTitle(w), if (w.busy) tr(R.string.sandbox_usage_busy) else null, first = i == 0,
                    onClick = if (w.busy) null else ({ onClear(w) }),
                ) {
                    if (w.conversationId != null || w.projectId != null) {
                        Text(
                            tr(R.string.terminal_open_action),
                            Modifier
                                .clickable { onOpenTerminal(w) }
                                .padding(end = 12.dp)
                                .testTag("sandbox-workspace-terminal"),
                            style = MikaType.Caption,
                            color = c.accent,
                        )
                    }
                    RowValue(SandboxCopy.size(w.bytes))
                }
            }
            if (u.workspaces.any { !it.busy }) {
                ListRow(null, SandboxCopy.CLEAR_ALL, titleColor = c.bad, onClick = onClearAll)
            }
        }
    }
    Text(SandboxCopy.USAGE_NOTE, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), style = MikaType.Hint, color = c.ink3)
}
