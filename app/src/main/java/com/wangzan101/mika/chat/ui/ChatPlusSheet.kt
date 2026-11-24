package com.wangzan101.mika.chat.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.learn.knowledge.FrontMatter
import com.wangzan101.mika.core.learn.skills.SkillStatus
import com.wangzan101.mika.settings.RecommendedTask
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.DirectionalIcon
import com.wangzan101.mika.ui.components.IconTile
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaCard
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wangzan101.mika.ui.Route

/** One entry in the skills list: the UI uses the display name and description written in the SKILL.md header. */
internal data class PlusSkill(val name: String, val display: String, val description: String)

/** The page currently being shown in the "+" panel. */
private enum class PlusView { Main, Photos, Skills, Tasks }

/** Height taken by the sheet's top handle; it must be subtracted from the 82% of the screen. */
private val PlusHandleHeight = 22.dp
private val PlusInset = 12.dp

/** Text on the clipboard; null when there is none or it only holds non-text content. */
internal fun readClipboardText(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

/**
 * Feature panel opened by "+": rises from the bottom and takes half the screen first; drag (or scroll) up to 82%, and close it by dragging down, tapping the scrim or pressing back.
 * The content is a series of cards, with one action per row: the upper card puts things into this turn, the lower one has Claire do something or go elsewhere.
 * Albums, skills and frequent tasks open a second-level page inside the panel; camera, files and checklists are handed straight to the system or the chat page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatPlusSheet(
    selected: List<PendingAttachment>,
    onDismiss: () -> Unit,
    onPicked: (List<String>) -> Unit,
    onToggle: (PendingAttachment) -> Boolean,
    onFull: () -> Unit,
    onDeviceFiles: (() -> Unit)?,
    onPaste: () -> Unit,
    /** A demo can only be started when idle; null while a task is running, in which case this row is not shown. */
    onTeach: (() -> Unit)?,
    onChecklists: () -> Unit,
    onWorkflows: () -> Unit,
    onBrowser: () -> Unit,
    tasks: List<RecommendedTask>,
    onPickTask: (RecommendedTask) -> Unit,
    onPickSkill: (PlusSkill) -> Unit,
) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    val attach = rememberAttachLaunchers(onPicked, onToggle, onFull, onDeviceFiles)
    var view by remember { mutableStateOf(PlusView.Main) }
    // Skills require disk reads; read only when actually on the "Skills" page, so opening/closing the panel costs no extra reads
    val skills by produceState<List<PlusSkill>?>(null, view) {
        if (view == PlusView.Skills && value == null) {
            value = withContext(Dispatchers.IO) { loadSkills(context) }
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.bg,
        shape = MikaShapes.SheetTop,
        dragHandle = { PlusHandle() },
        scrimColor = Color.Black.copy(alpha = 0.28f),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Half screen → expand to 82% of the screen: handle plus content is exactly this tall
            val body = (maxHeight * 0.82f - PlusHandleHeight).coerceAtLeast(0.dp)
            Column(Modifier.fillMaxWidth().height(body)) {
                when (view) {
                    PlusView.Main -> Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = PlusInset, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MikaCard {
                            PlusRow(MikaIcons.PhotoCamera, AttachCopy.CAMERA, null, first = true, onClick = attach.camera)
                            PlusRow(MikaIcons.Photo, AttachCopy.ALBUM, stringResource(R.string.plus_sub_album), onClick = { view = PlusView.Photos }, trailing = { RowChevron() })
                            PlusRow(MikaIcons.Paperclip, AttachCopy.FILES, stringResource(R.string.plus_sub_files), onClick = attach.files, trailing = { RowChevron() })
                            attach.deviceFiles?.let { open ->
                                PlusRow(MikaIcons.Folder, stringResource(R.string.device_browser_title), stringResource(R.string.plus_sub_device), onClick = open, trailing = { RowChevron() })
                            }
                            PlusRow(MikaIcons.Copy, stringResource(R.string.plus_clipboard), stringResource(R.string.plus_sub_clipboard), onClick = onPaste)
                        }
                        MikaCard {
                            onTeach?.let { teach ->
                                PlusRow(MikaIcons.Hand, stringResource(R.string.teach_menu), stringResource(R.string.plus_sub_teach), first = true, onClick = teach)
                            }
                            PlusRow(MikaIcons.Book, stringResource(R.string.memory_skills), stringResource(R.string.plus_sub_skills), first = onTeach == null, onClick = { view = PlusView.Skills }, trailing = { RowChevron() })
                            PlusRow(MikaIcons.ListChecks, stringResource(R.string.checklists_title), null, onClick = onChecklists)
                            PlusRow(MikaIcons.Route, stringResource(R.string.workflows_title), null, onClick = onWorkflows)
                            PlusRow(MikaIcons.Star, stringResource(R.string.recommendations_title), stringResource(R.string.plus_sub_tasks), onClick = { view = PlusView.Tasks }, trailing = { RowChevron() })
                            PlusRow(MikaIcons.Globe, stringResource(R.string.drawer_browser), stringResource(R.string.plus_sub_browser), onClick = onBrowser, trailing = { RowChevron() })
                        }
                    }
                    PlusView.Photos -> PlusPhotosPage(
                        access = attach.access,
                        recent = attach.recent,
                        selected = selected,
                        onToggle = attach.toggle,
                        onGrant = attach.grant,
                        onAlbum = attach.album,
                        onBack = { view = PlusView.Main },
                    )
                    PlusView.Skills -> PlusSkillsPage(
                        skills = skills,
                        onPick = onPickSkill,
                        onBack = { view = PlusView.Main },
                    )
                    PlusView.Tasks -> PlusTasksPage(
                        tasks = tasks,
                        onPick = onPickTask,
                        onBack = { view = PlusView.Main },
                    )
                }
            }
        }
    }
}

/** Top handle: a 36×5 short bar; the drag and tap semantics come from the Material sheet shell. */
@Composable
private fun PlusHandle() {
    Box(Modifier.fillMaxWidth().height(PlusHandleHeight), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 36.dp, height = 5.dp).background(MikaTheme.colors.ink3.copy(alpha = 0.35f), CircleShape))
    }
}

/**
 * A row in the panel: icon tile, title, one line of description, and optionally an arrow or something else on the right.
 * When [first] is the card's first row, no top divider is drawn; the divider starts at the text.
 */
@Composable
private fun PlusRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    first: Boolean = false,
    onClick: () -> Unit,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .then(if (first) Modifier else Modifier.drawBehind {
                val x = 58.dp.toPx()
                drawLine(c.hair, Offset(x, 0f), Offset(size.width, 0f), strokeWidth = 0.5.dp.toPx())
            })
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = MikaType.Body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MikaType.Caption, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke(this)
    }
}

/** Top row of a second-level page: back, title, with links on the right. */
@Composable
private fun SheetHead(title: String, onBack: () -> Unit, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            DirectionalIcon(MikaIcons.Back, stringResource(R.string.back), Modifier.size(16.dp), tint = c.ink)
        }
        Text(title, Modifier.weight(1f), style = MikaType.Label, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.invoke(this)
    }
}

/** Small link on a second-level page ("All photos", "Change scope"). */
@Composable
private fun SheetLink(text: String, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clip(MikaShapes.Tile).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
        style = MikaType.Hint, color = MikaTheme.colors.accent,
    )
}

/** Album second-level page: four columns of recent photos (tap to select, the order number appears in the top-right corner; tap again to deselect). [onGrant] is used when photo permission has not been granted. */
@Composable
internal fun PlusPhotosPage(
    access: PhotoAccess,
    recent: List<PendingAttachment>?,
    selected: List<PendingAttachment>,
    onToggle: (PendingAttachment) -> Unit,
    onGrant: () -> Unit,
    onAlbum: () -> Unit,
    onBack: () -> Unit,
) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxSize().padding(horizontal = PlusInset)) {
        SheetHead(
            title = if (access == PhotoAccess.Partial) AttachCopy.RECENT_PARTIAL else AttachCopy.RECENT,
            onBack = onBack,
            trailing = {
                if (access == PhotoAccess.Partial) SheetLink(AttachCopy.CHANGE, onGrant)
                SheetLink(AttachCopy.ALL, onAlbum)
            },
        )
        when {
            access == PhotoAccess.None -> Column(
                Modifier.fillMaxSize().padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            ) {
                Text(AttachCopy.NEED_ACCESS, style = MikaType.Caption, color = c.ink2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                MikaButton(AttachCopy.GRANT, onGrant, kind = ButtonKind.Primary, size = ButtonSize.Medium)
                Text(AttachCopy.GRANT_HINT, style = MikaType.Hint, color = c.ink3, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            recent == null -> Box(Modifier.fillMaxSize())
            recent.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(AttachCopy.NO_PHOTOS, style = MikaType.Caption, color = c.ink3)
            }
            else -> LazyVerticalGrid(
                GridCells.Fixed(4),
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(top = 2.dp, bottom = 12.dp),
            ) {
                items(recent, key = { it.uri }) { p -> RecentCell(p, AttachTray.order(selected, p.uri)) { onToggle(p) } }
            }
        }
    }
}

/** Skills second-level page: tapping one writes "use this skill" into the input field and returns to the chat page. */
@Composable
private fun PlusSkillsPage(skills: List<PlusSkill>?, onPick: (PlusSkill) -> Unit, onBack: () -> Unit) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = PlusInset)) {
        SheetHead(title = stringResource(R.string.memory_skills), onBack = onBack)
        when {
            skills == null -> Box(Modifier.fillMaxWidth())
            skills.isEmpty() -> Text(
                stringResource(R.string.plus_skills_empty),
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                style = MikaType.Caption, color = c.ink3,
            )
            else -> MikaCard {
                skills.forEachIndexed { i, skill ->
                    PlusRow(MikaIcons.Book, skill.display, skill.description, first = i == 0, onClick = { onPick(skill) })
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Frequent tasks second-level page: tapping one fills its wording into the input field (without sending). */
@Composable
private fun PlusTasksPage(tasks: List<RecommendedTask>, onPick: (RecommendedTask) -> Unit, onBack: () -> Unit) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = PlusInset)) {
        SheetHead(title = stringResource(R.string.recommendations_title), onBack = onBack)
        if (tasks.isEmpty()) {
            Text(
                stringResource(R.string.recommendations_empty),
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                style = MikaType.Caption, color = c.ink3,
            )
        } else {
            MikaCard {
                tasks.forEachIndexed { i, task ->
                    PlusRow(MikaIcons.Star, task.title, task.prompt, first = i == 0, onClick = { onPick(task) })
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Installed personal skills; suspended ones are not listed (they are no longer in use); the display name and description come from the SKILL.md header. */
private fun loadSkills(context: Context): List<PlusSkill> {
    val files = (context.applicationContext as? MikaApp)?.memory?.skills() ?: return emptyList()
    return files.list()
        .filter { it.status != SkillStatus.suspended }
        .map { s ->
            val meta = FrontMatter.parse(s.markdown).first
            PlusSkill(
                name = s.name,
                display = (meta["display"] as? String)?.takeIf { it.isNotBlank() } ?: s.name,
                description = (meta["description"] as? String).orEmpty(),
            )
        }
}
