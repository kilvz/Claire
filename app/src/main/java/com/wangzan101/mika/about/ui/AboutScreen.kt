package com.wangzan101.mika.about.ui

import androidx.compose.ui.res.stringResource
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.about.CheckStatus
import com.wangzan101.mika.about.MikaLinks
import com.wangzan101.mika.about.QrImage
import com.wangzan101.mika.about.ReleaseInfo
import com.wangzan101.mika.about.UpdateState
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.memory.ui.Link
import com.wangzan101.mika.ui.DiagnosticsSheet
import com.wangzan101.mika.ui.MarkdownText

/** Bottom sheets opened from the About screen. */
private enum class AboutSheet { Update, Changelog, Feedback, Group, Diagnostics }

/**
 * About Claire: icon, name, one-line introduction and version (Beta badge, build number) at the top; below that check for updates and
 * update history, feedback, the open-source repository and the guide; a data statement; and the license and author at the very bottom.
 * External links all go through [onOpenUrl] (system browser); a manual check's result is announced only when it arrives
 * ([UpdateState.manualRounds] changed).
 * [diagnostics] renders the diagnostics text (called on a background thread when "Copy diagnostics" is opened as a preview in the feedback sheet).
 */
@Composable
fun AboutScreen(
    versionName: String,
    build: String,
    beta: Boolean,
    update: UpdateState,
    issueUrl: String,
    onCheck: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onSaveQr: () -> Boolean,
    onOpenLicense: () -> Unit,
    onBack: () -> Unit,
    diagnostics: () -> String = { "" },
    autoUpdate: Boolean = false,
    onAutoUpdate: (Boolean) -> Unit = {},
    mirrorEnabled: Boolean = false,
    mirrorCount: Int = 0,
    onMirrorEnabled: (Boolean) -> Unit = {},
    onOpenMirrors: () -> Unit = {},
) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    var sheet by rememberSaveable { mutableStateOf<AboutSheet?>(null) }
    // After "Check for updates" is tapped, wait for this round's result: show the notes right away when there is a new
    // version, otherwise show a short toast
    var waitingRound by remember { mutableIntStateOf(-1) }
    LaunchedEffect(update.manualRounds) {
        if (waitingRound >= 0 && update.manualRounds > waitingRound) {
            waitingRound = -1
            when {
                update.update != null -> sheet = AboutSheet.Update
                update.status == CheckStatus.Failed ->
                    Toast.makeText(context, AboutCopy.OFFLINE_TOAST, Toast.LENGTH_SHORT).show()
                else -> Toast.makeText(context, AboutCopy.UP_TO_DATE_TOAST, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val saveQr = {
        Toast.makeText(context, if (onSaveQr()) AboutCopy.QR_SAVED else AboutCopy.QR_SAVE_FAILED, Toast.LENGTH_SHORT).show()
    }
    PageScaffold(AboutCopy.TITLE, onBack) {
        Hero(versionName, build, beta)

        GroupCard {
            val v = AboutCopy.checkValue(update)
            ListRow(
                MikaIcons.Refresh, AboutCopy.CHECK, if (update.hasUpdate) AboutCopy.CHECK_SUB_NEW else AboutCopy.CHECK_SUB, first = true,
                onClick = {
                    if (update.hasUpdate) sheet = AboutSheet.Update
                    else if (update.status != CheckStatus.Checking) {
                        waitingRound = update.manualRounds
                        onCheck()
                    }
                },
            ) {
                TrailingValue(v)
                RowChevron()
            }
            ListRow(
                MikaIcons.Refresh,
                stringResource(R.string.about_auto_update),
                note = stringResource(R.string.about_auto_update_detail),
            ) {
                MikaSwitch(autoUpdate, onAutoUpdate, Modifier.testTag("auto-update-switch"))
            }
            ListRow(
                MikaIcons.Link,
                stringResource(R.string.about_mirror_download),
                note = stringResource(R.string.about_mirror_download_detail),
            ) {
                MikaSwitch(mirrorEnabled, onMirrorEnabled, Modifier.testTag("mirror-download-switch"))
            }
            ListRow(
                MikaIcons.Shield,
                stringResource(R.string.about_mirror_sources),
                onClick = onOpenMirrors,
            ) {
                RowValue(stringResource(R.string.about_mirrors_count, mirrorCount))
                RowChevron()
            }
            ListRow(MikaIcons.Lines, AboutCopy.CHANGELOG, onClick = { sheet = AboutSheet.Changelog }) { RowChevron() }
        }
        GroupCard {
            ListRow(MikaIcons.Chats, AboutCopy.GROUP, first = true, onClick = { sheet = AboutSheet.Group }) { RowChevron() }
            ListRow(MikaIcons.XMark, AboutCopy.FOLLOW, onClick = { onOpenUrl(MikaLinks.X) }) { RowChevron() }
            ListRow(MikaIcons.Bug, AboutCopy.FEEDBACK, onClick = { sheet = AboutSheet.Feedback }) { RowChevron() }
        }
        GroupCard {
            ListRow(MikaIcons.Code, AboutCopy.REPO, first = true, onClick = { onOpenUrl(MikaLinks.REPO) }) { RowChevron() }
            ListRow(MikaIcons.Book, AboutCopy.GUIDE, onClick = { onOpenUrl(MikaLinks.README) }) { RowChevron() }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp).background(c.okSoft, MikaShapes.Button).padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(MikaIcons.Shield, null, Modifier.padding(top = 1.dp).size(18.dp), tint = c.ok)
            Text(AboutCopy.DATA_NOTE, style = MikaType.Caption, color = c.ink2)
        }

        Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                buildAnnotatedString {
                    val (before, after) = stringResource(R.string.about_released_under).split("%1\$s").let { it[0] to it.getOrElse(1) { "" } }
                    append(before)
                    withStyle(SpanStyle(color = c.accent)) { append(AboutCopy.LICENSE_LINK) }
                    append(after)
                },
                Modifier.clip(RoundedCornerShape(MikaRadius.BubbleTail)).clickable(onClick = onOpenLicense).padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("about-license"),
                style = MikaType.Hint, color = c.ink3, textAlign = TextAlign.Center,
            )
            Text(AboutCopy.AUTHOR, Modifier.padding(top = 2.dp), style = MikaType.Hint, color = c.ink3)
        }
    }

    val close = { sheet = null }
    when (sheet) {
        AboutSheet.Update -> update.update?.let { r ->
            UpdateSheet(r, versionName, beta, onOpenUrl = { close(); onOpenUrl(it) }, onDismiss = close)
        }
        AboutSheet.Changelog -> ChangelogSheet(update.latest, onOpenAll = { close(); onOpenUrl(MikaLinks.RELEASES) }, onDismiss = close)
        AboutSheet.Feedback -> FeedbackSheet(
            onGitHub = { close(); onOpenUrl(issueUrl) },
            onDiagnostics = { sheet = AboutSheet.Diagnostics },
            onDismiss = close,
        )
        AboutSheet.Group -> GroupSheet(onSave = saveQr, onDismiss = close)
        AboutSheet.Diagnostics -> DiagnosticsSheet(diagnostics, onDismiss = close)
        null -> Unit
    }
}

@Composable
private fun Hero(versionName: String, build: String, beta: Boolean) {
    val c = MikaTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painterResource(R.drawable.mika_launcher_art), contentDescription = null,
            Modifier.size(72.dp).clip(RoundedCornerShape(MikaRadius.Bubble)),
        )
        Text("Claire", Modifier.padding(top = 12.dp), style = MikaType.Title, fontWeight = FontWeight.Bold, color = c.ink)
        Text(AboutCopy.TAGLINE, Modifier.padding(top = 4.dp), style = MikaType.Caption, color = c.ink2, textAlign = TextAlign.Center)
        Row(Modifier.padding(top = 10.dp).testTag("about-version"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(AboutCopy.version(versionName), style = MikaType.Hint, color = c.ink3)
            if (beta) {
                Text(
                    AboutCopy.BETA, Modifier.background(c.accentSoft, MikaShapes.Tile).padding(horizontal = 7.dp, vertical = 1.dp),
                    style = MikaType.Hint, fontWeight = FontWeight.SemiBold, color = c.accent,
                )
            }
            Text("· ${AboutCopy.buildLabel(build)}", style = MikaType.Hint, color = c.ink3)
        }
    }
}

/** Value at the end of a row: a spinner, a dot plus text, or a plain value. */
@Composable
internal fun TrailingValue(v: AboutCopy.CheckValue) {
    if (v.spinner) {
        CircularProgressIndicator(Modifier.size(16.dp).testTag("update-spinner"), color = MikaTheme.colors.accent, strokeWidth = 2.dp)
        return
    }
    if (v.dot) UpdateDot()
    v.text?.let { RowValue(it, if (v.highlight) ValueTone.Link else ValueTone.Plain) }
}

/** The small red dot shown when a new version is available. */
@Composable
internal fun UpdateDot(modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(MikaTheme.colors.bad, CircleShape).testTag("update-dot"))
}


// ---------- Bottom sheets ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutSheetFrame(title: String, sub: String?, tag: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = MikaTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = MikaShapes.SheetTop,
    ) {
        // When the sheet content is too tall (short screen, large font) the whole thing scrolls so the buttons at the bottom stay reachable
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp).testTag(tag),
        ) {
            Text(title, style = MikaType.Title, color = c.ink)
            sub?.let { Text(it, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3) }
            content()
        }
    }
}

/** Release notes (Markdown) on a light background, scrolling inside a box when too long. */
@Composable
private fun NotesBox(notes: String) {
    val c = MikaTheme.colors
    Box(
        Modifier.padding(top = 12.dp).fillMaxWidth().heightIn(max = 280.dp)
            .background(c.fill, MikaShapes.Chip).verticalScroll(rememberScrollState()).padding(12.dp),
    ) {
        MarkdownText(notes, MikaType.Caption.copy(color = c.ink2))
    }
}

@Composable
internal fun UpdateSheet(r: ReleaseInfo, current: String, beta: Boolean, onOpenUrl: (String) -> Unit, onDismiss: () -> Unit) {
    AboutSheetFrame(AboutCopy.updateTitle(r.version), AboutCopy.updateSub(current, beta, r.date), "update-sheet", onDismiss) {
        if (r.notes.isNotBlank()) NotesBox(r.notes)
        if (r.apkUrl != null) UpdateDownloadControls(r)
        else MikaButton(AboutCopy.UPDATE_DOWNLOAD, { onOpenUrl(r.pageUrl) }, Modifier.fillMaxWidth().padding(top = 14.dp))
        Text(
            AboutCopy.UPDATE_LATER,
            Modifier.fillMaxWidth().padding(top = 8.dp).clip(MikaShapes.Tile).clickable(onClick = onDismiss).padding(vertical = 10.dp),
            style = MikaType.Caption, color = MikaTheme.colors.ink3, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ChangelogSheet(latest: ReleaseInfo?, onOpenAll: () -> Unit, onDismiss: () -> Unit) {
    val c = MikaTheme.colors
    AboutSheetFrame(AboutCopy.CHANGELOG, null, "changelog-sheet", onDismiss) {
        if (latest == null) {
            Text(AboutCopy.CHANGELOG_EMPTY, Modifier.padding(top = 10.dp), style = MikaType.Caption, color = c.ink3)
        } else {
            Text(
                latest.version + (latest.date?.let { " · $it" } ?: ""), Modifier.padding(top = 10.dp),
                style = MikaType.Label, color = c.ink,
            )
            if (latest.notes.isNotBlank()) NotesBox(latest.notes)
        }
        MikaButton(AboutCopy.CHANGELOG_ALL, onOpenAll, Modifier.fillMaxWidth().padding(top = 14.dp), kind = ButtonKind.Tonal)
    }
}

@Composable
private fun FeedbackSheet(onGitHub: () -> Unit, onDiagnostics: () -> Unit, onDismiss: () -> Unit) {
    AboutSheetFrame(AboutCopy.FEEDBACK, AboutCopy.FEEDBACK_SHEET_SUB, "feedback-sheet", onDismiss) {
        Column(Modifier.padding(top = 6.dp)) {
            FeedbackOption(MikaIcons.Code, AboutCopy.FEEDBACK_GITHUB, AboutCopy.FEEDBACK_GITHUB_SUB, first = true, onGitHub)
            FeedbackOption(MikaIcons.Copy, AboutCopy.DIAGNOSTICS, AboutCopy.DIAGNOSTICS_SUB, first = false, onDiagnostics)
        }
    }
}

@Composable
private fun FeedbackOption(icon: ImageVector, title: String, sub: String, first: Boolean, onClick: () -> Unit) {
    Box(Modifier.testTag("feedback-option")) {
        ListRow(icon, title, sub, first = first, onClick = onClick)
    }
}

/**
 * Contact-the-author sheet: the author's WeChat QR code, saved to the gallery by long press or the button. The QR code takes up at most about a third of the screen, so on common phones the button is visible without scrolling.
 */
@Composable
internal fun GroupSheet(onSave: () -> Unit, onDismiss: () -> Unit) {
    val c = MikaTheme.colors
    val qrHeight = (LocalConfiguration.current.screenHeightDp * 0.34f).coerceAtMost(280f).dp
    val qr = painterResource(QrImage.RES)
    AboutSheetFrame(AboutCopy.GROUP_SHEET_TITLE, AboutCopy.GROUP_SHEET_SUB, "group-sheet", onDismiss) {
        Image(
            qr, contentDescription = AboutCopy.GROUP_SHEET_TITLE,
            Modifier.padding(top = 12.dp, bottom = 6.dp).align(Alignment.CenterHorizontally)
                .height(qrHeight).aspectRatio(qr.intrinsicSize.width / qr.intrinsicSize.height)
                .clip(MikaShapes.Chip)
                .pointerInput(Unit) { detectTapGestures(onLongPress = { onSave() }) }
                .testTag("wechat-qr"),
        )
        Text(AboutCopy.GROUP_HINT, Modifier.fillMaxWidth(), style = MikaType.Hint, color = c.ink3, textAlign = TextAlign.Center)
        MikaButton(AboutCopy.SAVE_QR, onSave, Modifier.fillMaxWidth().padding(top = 14.dp), kind = ButtonKind.Tonal)
    }
}
