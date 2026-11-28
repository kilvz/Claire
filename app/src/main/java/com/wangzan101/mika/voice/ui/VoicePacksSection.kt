package com.wangzan101.mika.voice.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wangzan101.mika.R
import com.wangzan101.mika.about.DownloadPhase
import com.wangzan101.mika.download.DownloadProgress
import com.wangzan101.mika.settings.ui.models.Page
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voice.model.VoiceCatalogSnapshot
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import com.wangzan101.mika.voice.ondevice.OnDeviceCatalog
import com.wangzan101.mika.voice.ondevice.VoicePackEntry
import com.wangzan101.mika.voice.ondevice.VoicePackInstaller.ImportInput
import com.wangzan101.mika.voice.ondevice.VoicePackInstaller.ImportResult
import com.wangzan101.mika.voice.ondevice.VoicePackStatus
import java.io.IOException
import java.util.Locale

/**
 * Settings › Models › Voice input › Offline voice packs: one card per pack (name, languages, size, license, status and actions).
 * Reached from the voice input page and from "Download offline voice pack" when voice input is not available yet. When no pack can run on the device the page shows only the explanation (the entry point is hidden anyway).
 */
@Composable
fun VoicePacksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel = remember { VoicePacksViewModel.forApp(context, scope) { copyToClipboard(context, it) } }
    Page(stringResource(R.string.voice_packs_title), onBack) {
        VoicePacksSection(viewModel)
    }
}

/** The "Offline voice packs" section: the explanation, the latest result notice, one card per pack, and confirmation dialogs for metered downloads and deletion. */
@Composable
fun VoicePacksSection(viewModel: VoicePacksViewModel) {
    if (!viewModel.visible) return
    val context = LocalContext.current
    val cards by viewModel.cards.collectAsState()
    val prompt by viewModel.prompt.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val mirrorEnabled by viewModel.mirrorEnabled.collectAsState()
    var deleting by remember { mutableStateOf<VoicePackEntry?>(null) }
    // Remember which pack an import is for while the system file picker is open
    var importFor by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val entry = cards.firstOrNull { it.entry.id == importFor }?.entry
        importFor = null
        if (entry != null && uris.isNotEmpty()) viewModel.import(entry, importInputs(context, uris))
    }
    // Re-check files when returning to the foreground: they may have been deleted or installed elsewhere
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    Column(Modifier.testTag("voice-packs")) {
        Text(
            stringResource(R.string.voice_packs_lead), Modifier.padding(top = 4.dp, bottom = 12.dp),
            style = MikaType.Body, color = MikaTheme.colors.ink2,
        )
        // Same switch as "Use mirrors for downloads" on the About page: GitHub is often slow from some regions, so it is shown where downloads happen
        GroupCard {
            ListRow(
                MikaIcons.Link,
                stringResource(R.string.voice_pack_mirror),
                note = stringResource(R.string.voice_pack_mirror_note),
                first = true,
            ) {
                MikaSwitch(mirrorEnabled, viewModel::setMirrorEnabled, Modifier.testTag("voice-pack-mirror-switch"))
            }
        }
        notice?.let { n ->
            val (text, bad) = noticeText(n)
            FooterNote(text, Modifier.testTag("voice-pack-notice"), color = if (bad) MikaTheme.colors.bad else MikaTheme.colors.ink2)
        }
        cards.forEach { card ->
            VoicePackCardView(
                card,
                mirrorEnabled = mirrorEnabled,
                onDownload = { viewModel.download(card.entry) },
                onCancel = { viewModel.cancel(card.entry) },
                onDelete = { deleting = card.entry },
                onImport = {
                    importFor = card.entry.id
                    picker.launch(arrayOf("*/*"))
                },
                onCopy = { viewModel.copyUrls(card.entry) },
            )
        }
    }

    (prompt as? VoicePackPrompt.Metered)?.let { p ->
        MikaDialog(
            onDismissRequest = viewModel::dismissPrompt,
            title = { Text(stringResource(R.string.voice_pack_metered_title)) },
            text = { Text(stringResource(R.string.voice_pack_metered_body, size(context, p.bytes))) },
            dismissButton = { TextButton(onClick = viewModel::dismissPrompt) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                TextButton(onClick = viewModel::confirmMetered, modifier = Modifier.testTag("voice-pack-metered-confirm")) {
                    Text(stringResource(R.string.voice_pack_metered_confirm))
                }
            },
        )
    }

    deleting?.let { entry ->
        MikaDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.voice_pack_delete_title)) },
            text = { Text(stringResource(R.string.voice_pack_delete_body, entry.displayName)) },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        viewModel.delete(entry)
                    },
                    modifier = Modifier.testTag("voice-pack-delete-confirm"),
                ) { Text(stringResource(R.string.voice_pack_delete), color = MikaTheme.colors.bad) }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoicePackCardView(
    card: VoicePackCard,
    mirrorEnabled: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onImport: () -> Unit,
    onCopy: () -> Unit,
) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    val entry = card.entry
    val id = entry.id
    val progress = card.progress
    val downloading = progress != null && progress.phase != DownloadPhase.Failed
    val status = statusText(card, context)
    GroupCard(Modifier.testTag("voice-pack-$id")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.displayName, Modifier.weight(1f, fill = false), style = MikaType.Body.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
                if (card.recommended) StatusPill(stringResource(R.string.voice_pack_recommended), PillTone.Accent, Modifier.testTag("voice-pack-recommended-$id"))
            }
            Text(
                stringResource(R.string.voice_pack_meta, languagesText(entry), size(context, entry.totalBytes), entry.license.name),
                Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3,
            )
            // Screen readers announce this status line, including the percentage while downloading
            Text(
                status, Modifier.padding(top = 8.dp).testTag("voice-pack-status-$id"),
                style = MikaType.Hint, color = if (card.status == VoicePackStatus.Corrupt) c.warn else c.ink2,
            )
            if (downloading) {
                val fraction = progress!!.fraction()
                val name = entry.displayName
                val bar = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("voice-pack-progress-$id")
                    .semantics {
                        contentDescription = name
                        stateDescription = status
                    }
                if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = bar)
                else LinearProgressIndicator(modifier = bar)
            }
            if (progress?.phase == DownloadPhase.Failed) {
                // With mirrors off, point the user at mirrors first; unreachable GitHub is usually the cause
                val failed = if (mirrorEnabled) R.string.voice_pack_download_failed else R.string.voice_pack_download_failed_mirror_off
                Text(
                    stringResource(failed), Modifier.padding(top = 6.dp).testTag("voice-pack-failed-$id"),
                    style = MikaType.Hint, color = c.bad,
                )
            }
            FlowRow(
                Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val idle = !downloading && !card.importing
                when {
                    downloading -> PackButton(R.string.voice_pack_cancel, "voice-pack-cancel-$id", onCancel, ButtonKind.Tonal)
                    card.importing -> Unit
                    card.status == VoicePackStatus.Installed -> {
                        PackButton(R.string.voice_pack_delete, "voice-pack-delete-$id", onDelete, ButtonKind.Tonal)
                        // Correct sizes but bad content (reported as "voice pack damaged" when used) still counts as installed: re-downloading the same version overwrites it
                        // without deleting, so the selection and fallback chain stay (deleting would reset the selection to "Auto")
                        PackButton(R.string.voice_pack_redownload, "voice-pack-redownload-$id", onDownload, ButtonKind.Text)
                    }
                    card.status == VoicePackStatus.UpdateAvailable -> {
                        PackButton(R.string.voice_pack_update, "voice-pack-download-$id", onDownload)
                        PackButton(R.string.voice_pack_delete, "voice-pack-delete-$id", onDelete, ButtonKind.Tonal)
                    }
                    card.status == VoicePackStatus.Corrupt -> {
                        PackButton(R.string.voice_pack_redownload, "voice-pack-download-$id", onDownload)
                        PackButton(R.string.voice_pack_delete, "voice-pack-delete-$id", onDelete, ButtonKind.Tonal)
                    }
                    else -> PackButton(R.string.voice_pack_download, "voice-pack-download-$id", onDownload)
                }
                // Installed packs need neither import nor address copying; other states keep both paths for offline users
                if (idle && card.status != VoicePackStatus.Installed) {
                    PackButton(R.string.voice_pack_import, "voice-pack-import-$id", onImport, ButtonKind.Tonal)
                    PackButton(R.string.voice_pack_copy_urls, "voice-pack-copy-$id", onCopy, ButtonKind.Text)
                }
            }
        }
    }
}

@Composable
private fun PackButton(label: Int, tag: String, onClick: () -> Unit, kind: ButtonKind = ButtonKind.Primary) {
    MikaButton(stringResource(label), onClick, Modifier.testTag(tag), kind = kind, size = ButtonSize.Small)
}

@Composable
private fun statusText(card: VoicePackCard, context: Context): String {
    val p = card.progress
    return when {
        p != null && p.phase != DownloadPhase.Failed -> p.fraction()?.let {
            stringResource(R.string.voice_pack_status_downloading_percent, (it * 100).toInt())
        } ?: stringResource(R.string.voice_pack_status_downloading)
        card.importing -> stringResource(R.string.voice_pack_status_importing)
        else -> when (card.status) {
            VoicePackStatus.NotInstalled -> stringResource(R.string.voice_pack_status_not_installed)
            VoicePackStatus.Installed -> stringResource(R.string.voice_pack_status_installed, card.entry.version, size(context, card.entry.totalBytes))
            VoicePackStatus.Corrupt -> stringResource(R.string.voice_pack_status_corrupt)
            VoicePackStatus.UpdateAvailable -> stringResource(R.string.voice_pack_status_update)
        }
    }
}

/** With few languages, list their names (in the UI language); with many, just the count. */
@Composable
private fun languagesText(entry: VoicePackEntry): String {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    return if (entry.languages.size <= MAX_NAMED_LANGUAGES) {
        entry.languages.joinToString(", ") { code -> Locale.forLanguageTag(code).getDisplayLanguage(locale).ifBlank { code } }
    } else stringResource(R.string.voice_pack_languages_count, entry.languages.size)
}

private const val MAX_NAMED_LANGUAGES = 5

/** First is the text to show, second is whether the user must act (shown in red). */
@Composable
private fun noticeText(n: VoicePackNotice): Pair<String, Boolean> {
    val context = LocalContext.current
    return when (n) {
        is VoicePackNotice.NoSpace -> stringResource(R.string.voice_pack_no_space, size(context, n.needed), size(context, n.free)) to true
        VoicePackNotice.Unsupported -> stringResource(R.string.voice_pack_unsupported) to true
        VoicePackNotice.NotStarted -> stringResource(R.string.voice_pack_not_started) to true
        VoicePackNotice.DeleteFailed -> stringResource(R.string.voice_pack_delete_failed) to true
        VoicePackNotice.Copied -> stringResource(R.string.voice_pack_copied) to false
        VoicePackNotice.MirrorSaveFailed -> stringResource(R.string.about_mirrors_save_failed) to true
        is VoicePackNotice.Imported -> when (val r = n.result) {
            ImportResult.Installed -> stringResource(R.string.voice_pack_import_done) to false
            is ImportResult.Missing -> stringResource(R.string.voice_pack_import_missing, r.files.joinToString(", ")) to true
            is ImportResult.Mismatch -> stringResource(R.string.voice_pack_import_mismatch, r.file) to true
            ImportResult.Busy -> stringResource(R.string.voice_pack_import_busy) to true
            is ImportResult.NoSpace -> stringResource(R.string.voice_pack_no_space, size(context, r.needed), size(context, r.free)) to true
            ImportResult.UnsupportedDevice -> stringResource(R.string.voice_pack_unsupported) to true
            is ImportResult.Failed -> stringResource(R.string.voice_pack_import_failed) to true
        }
    }
}

/** Increments each time the page returns to the foreground; used as a remember key to re-check files on return. */
@Composable
internal fun rememberResumeCount(): Int {
    var count by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        count++
        onPauseOrDispose {}
    }
    return count
}

/**
 * The "download required" badge on an on-device pack in the selection list: the model and selection exist but the files do not (restored from backup, deleted).
 * Files are re-checked only when [resumed] changes (returning to this page) or the catalog changes.
 */
@Composable
internal fun voicePackBadge(environment: VoiceModelEnvironment, snapshot: VoiceCatalogSnapshot, resumed: Int): (VoiceOption) -> String? {
    val label = stringResource(R.string.voice_pack_needs_download)
    val missing = remember(snapshot, resumed) {
        snapshot.models.filter { it.serviceId == OnDeviceCatalog.SERVICE_ID }
            .filter { environment.voicePackNeedsDownload(OnDeviceCatalog.ref(it.id)) }
            .mapTo(HashSet()) { it.id }
    }
    return { option -> label.takeIf { OnDeviceCatalog.isOnDevice(option.ref) && option.ref.modelId in missing } }
}

private fun DownloadProgress.fraction(): Float? =
    if (totalBytes > 0) (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else null

private fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

/** A file the user picked: the display name comes from the system (falling back to the last path segment), and every open reads from the start. */
private fun importInputs(context: Context, uris: List<Uri>): List<ImportInput> = uris.map { uri ->
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    ImportInput(name) { context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri") }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.voice_pack_copy_urls), text))
}
