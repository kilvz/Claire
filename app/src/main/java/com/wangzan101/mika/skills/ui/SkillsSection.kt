package com.wangzan101.mika.skills.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.compose.ui.res.stringResource
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.components.KeyboardDismissArea
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.core.learn.knowledge.AppKnowledgeStage
import com.wangzan101.mika.core.learn.knowledge.FrontMatter
import com.wangzan101.mika.core.learn.skills.SkillImport
import com.wangzan101.mika.ui.components.ActionSheet
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.RowActions
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.SheetAction
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.memory.ui.AppKnowledgeRow
import com.wangzan101.mika.memory.ui.MemoryViewModel
import com.wangzan101.mika.memory.ui.SkillRow
import com.wangzan101.mika.memory.ui.SkillsUi

/**
 * The two skill-related blocks. When [pending] is false this is the "Skills" section: accepted personal skills (the switch enables/disables them) and the app knowledge in use;
 * when true it is the part inside the "Pending" section: new skill drafts (view the full text, edit then accept, accept, reject) and app knowledge drafts.
 * Personal skills can be shared as full text, and full text shared by others is pasted in through "Import skill", landing in "Pending" first (#165).
 */
@Composable
internal fun SkillsSection(vm: MemoryViewModel, ui: SkillsUi, pending: Boolean, onMarket: () -> Unit = {}) {
    var viewing by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<SkillRow?>(null) }
    var acting by remember { mutableStateOf<SkillRow?>(null) }
    var importing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    fun toggle(id: String) { viewing = if (viewing == id) null else id }

    if (pending) {
        if (ui.drafts.isNotEmpty()) {
            GroupLabel(stringResource(R.string.skills_drafts))
            GroupCard {
                ui.drafts.forEachIndexed { i, d ->
                    Column(Modifier.testTag("skill-row")) {
                        ListRow(null, d.display, d.description.ifBlank { null }, first = i == 0,
                            detail = stringResource(R.string.skills_imported).takeIf { d.imported }, onClick = { toggle(d.id) })
                        SkillBody(d, expanded = viewing == d.id)
                        RowActions {
                            MikaButton(stringResource(R.string.skills_accept_try), { vm.approveSkill(d.id) }, size = ButtonSize.Small)
                            MikaButton(stringResource(R.string.edit_it), { editing = d }, kind = ButtonKind.Tonal, size = ButtonSize.Small)
                            MikaButton(if (viewing == d.id) stringResource(R.string.skills_hide_full) else stringResource(R.string.skills_view_full), { toggle(d.id) }, kind = ButtonKind.Tonal, size = ButtonSize.Small)
                            MikaButton(stringResource(R.string.no_thanks), { vm.rejectSkill(d.id) }, kind = ButtonKind.Tonal, size = ButtonSize.Small)
                        }
                    }
                }
            }
        }
        val drafts = ui.appKnowledge.filter { it.stage == AppKnowledgeStage.Draft }
        if (drafts.isNotEmpty()) {
            GroupLabel(stringResource(R.string.skills_app_drafts))
            GroupCard {
                drafts.forEachIndexed { i, row -> AppKnowledgeItem(vm, row, i == 0, viewing == row.id) { toggle(row.id) } }
            }
        }
    } else {
        GroupLabel(stringResource(R.string.skills_mine))
        GroupCard {
            if (ui.installed.isEmpty()) EmptyNote(stringResource(R.string.skills_empty))
            ui.installed.forEachIndexed { i, s ->
                val state = when { s.suspended -> stringResource(R.string.skills_suspended); s.trial -> stringResource(R.string.skills_trial); else -> null }
                val detail = listOfNotNull(stringResource(R.string.skills_user_created).takeIf { s.userCreated },
                    stringResource(R.string.skills_imported).takeIf { s.imported }, state, s.recent.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.skills_recent, it.joinToString(" · ")) }).joinToString(" · ")
                Column(Modifier.testTag("skill-row")) {
                    ListRow(null, s.display, s.description.ifBlank { null }, first = i == 0, detail = detail, onClick = { acting = s }) {
                        MikaSwitch(!s.suspended, { on -> if (on) vm.restoreSkill(s.name) else vm.suspendSkill(s.name) })
                    }
                    SkillBody(s, expanded = viewing == s.id)
                }
            }
        }
        GroupLabel(stringResource(R.string.skills_get))
        GroupCard {
            ListRow(null, stringResource(R.string.skills_import), stringResource(R.string.skills_import_sub), first = true, onClick = { importing = true }) {
                RowChevron()
            }
            ListRow(null, stringResource(R.string.market_title), stringResource(R.string.market_sub), onClick = onMarket) {
                RowChevron()
            }
        }
        val inUse = ui.appKnowledge.filter { it.stage != AppKnowledgeStage.Draft }
        if (inUse.isNotEmpty()) {
            GroupLabel(stringResource(R.string.skills_app_knowledge))
            GroupCard {
                inUse.forEachIndexed { i, row -> AppKnowledgeItem(vm, row, i == 0, viewing == row.id) { toggle(row.id) } }
            }
        }
    }

    acting?.let { s ->
        ActionSheet(
            s.display,
            listOfNotNull(
                SheetAction(if (viewing == s.id) stringResource(R.string.skills_hide_full) else stringResource(R.string.skills_view_full)) { toggle(s.id) },
                SheetAction(stringResource(R.string.skills_share)) {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, s.display).putExtra(Intent.EXTRA_TEXT, SkillImport.shareable(s.markdown))
                    context.startActivity(Intent.createChooser(send, null))
                },
                if (s.canRestore) SheetAction(stringResource(R.string.skills_restore_prev)) { vm.restorePreviousSkill(s.name) } else null,
                SheetAction(stringResource(R.string.confirm_delete), destructive = true) { vm.deleteSkill(s.name) },
            ),
            onDismiss = { acting = null },
        )
    }

    if (importing) {
        var text by remember { mutableStateOf("") }
        var problem by remember { mutableStateOf<String?>(null) }
        MikaDialog(
            onDismissRequest = { importing = false },
            title = { Text(stringResource(R.string.skills_import)) },
            text = {
                KeyboardDismissArea {
                    Column {
                        Text(stringResource(R.string.skills_import_hint), style = MikaType.Hint, color = MikaTheme.colors.ink3)
                        OutlinedTextField(text, { text = it; problem = null }, Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 240.dp).keepsKeyboard().testTag("skill-import"))
                        problem?.let { Text(it, Modifier.padding(top = 6.dp), style = MikaType.Hint, color = MikaTheme.colors.bad) }
                    }
                }
            },
            confirmButton = {
                TextButton({ problem = vm.importSkill(text); if (problem == null) importing = false }, enabled = text.isNotBlank()) {
                    Text(stringResource(R.string.skills_import_confirm))
                }
            },
            dismissButton = { TextButton({ importing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    editing?.let { d ->
        var text by remember(d.id) { mutableStateOf(d.markdown) }
        MikaDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.skills_edit_title)) },
            text = { KeyboardDismissArea { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 240.dp).keepsKeyboard().testTag("skill-markdown")) } },
            confirmButton = { TextButton({ vm.approveSkill(d.id, text); editing = null }) { Text(stringResource(R.string.skills_accept_try)) } },
            dismissButton = { TextButton({ editing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** One piece of app knowledge: tap to see the full text; drafts can be accepted as a trial, unverified ones can be archived; the entry temporarily replaced by a revision under trial is view-only. */
@Composable
private fun AppKnowledgeItem(vm: MemoryViewModel, row: AppKnowledgeRow, first: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    val stage = if (row.replaced) tr(R.string.skills_app_replaced) else when (row.stage) {
        AppKnowledgeStage.Draft -> tr(if (row.revision) R.string.skills_app_revision else R.string.skills_app_addition)
        AppKnowledgeStage.Trial -> stringResource(R.string.skills_trial)
        AppKnowledgeStage.Verified -> tr(R.string.skills_verified)
        AppKnowledgeStage.Archived -> tr(R.string.skills_archived)
    }
    Column {
        ListRow(null, row.name, stage, first = first, onClick = onToggle)
        ChangesAndText(row.changedLines, expanded) {
            Text(row.markdown, Modifier.padding(top = 6.dp), style = MikaType.Hint, color = MikaTheme.colors.ink2)
        }
        RowActions {
            MikaButton(if (expanded) stringResource(R.string.skills_hide_full) else stringResource(R.string.skills_view_full), onToggle, kind = ButtonKind.Tonal, size = ButtonSize.Small)
            if (row.stage == AppKnowledgeStage.Draft) MikaButton(stringResource(R.string.skills_accept_try), { vm.approveAppDraft(row.id) }, size = ButtonSize.Small)
            if (row.stage != AppKnowledgeStage.Verified && !row.replaced) MikaButton(stringResource(R.string.skills_archive), { vm.rejectAppDraft(row.id) }, kind = ButtonKind.Tonal, size = ButtonSize.Small)
        }
    }
}

/** Below a skill row: which lines the update draft changed; when expanded, the full text is shown as well. */
@Composable
private fun SkillBody(r: SkillRow, expanded: Boolean) = ChangesAndText(r.changedLines, expanded) {
    val body = remember(r.markdown) { skillPreviewBody(r.markdown) }
    SkillReadingView(body)
}

/** Strip only a recognized skill header; horizontal rules in ordinary Markdown are content. */
internal fun skillPreviewBody(markdown: String): String {
    val (metadata, body) = FrontMatter.parse(markdown)
    return if (metadata["name"] is String && body.isNotBlank()) body else markdown
}

/** Below a row: which lines this draft changed ([changed]); when expanded, the full text is shown as well. */
@Composable
private fun ChangesAndText(changed: List<String>, expanded: Boolean, content: @Composable () -> Unit) {
    val c = MikaTheme.colors
    if (changed.isEmpty() && !expanded) return
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        if (changed.isNotEmpty()) {
            Text(stringResource(R.string.skills_changed_lines), style = MikaType.Hint, color = c.ink3)
            changed.forEach { Text(it, style = MikaType.Hint, color = if (it.startsWith("+")) c.ok else c.bad) }
        }
        if (expanded) content()
    }
}
