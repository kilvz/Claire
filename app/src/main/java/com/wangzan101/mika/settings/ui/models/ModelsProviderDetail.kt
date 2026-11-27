package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.usage.TokenBudgetLimits
import com.wangzan101.mika.i18n.tr
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.components.KeyboardDismissArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.core.model.ApiKeyText
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.OpenAiServiceTier
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.FieldClearButton
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.settings.ui.TokenBudgetDialog
import com.wangzan101.mika.settings.ui.tokenBudgetNumber

class ProviderDetailActions(
    val refresh: () -> Unit = {},
    val changeKey: (String) -> Unit = {},
    val addKey: (String) -> Unit = {},
    val removeKey: (String) -> Unit = {},
    val remove: () -> Unit = {},
    /**
     * Use one of this provider's models for a role (tapped in the "Use as" sheet); the last parameter is called after saving. The main model is test-called first,
     * with progress and the reason in [ModelSetupUi.manualPick].
     */
    val use: (ModelRole, String, () -> Unit) -> Unit = { _, _, _ -> },
    /** Clears the previous save or test-call result (when the "Use as" sheet opens). */
    val clearPick: () -> Unit = {},
    /** Whether the user says one of this provider's models can see images (the switch in the "Use as" sheet). */
    val setVision: (String, Boolean) -> Unit = { _, _ -> },
    val setServiceTier: (String, OpenAiServiceTier?) -> Boolean = { _, _ -> false },
    val showAll: () -> Unit = {},
    /** Test the model the user confirmed. */
    val retestModel: (String) -> Unit = {},
    /** Voice-model capability references under this provider; previews which voice models would be deleted along with it. */
    val voiceUsage: (String) -> List<VoiceModelRef> = { emptyList() },
    /** Account-signed-in providers (ChatGPT, xAI, OpenCode Go, OpenRouter): sign in again, sign out, open that provider's usage or account page. */
    val signIn: () -> Unit = {},
    val signOut: () -> Unit = {},
    val manageUsage: () -> Unit = {},
    /** Local daily Token budget for this provider, summed over all models and roles. */
    val setDailyBudget: (Long?) -> Boolean = { false },
    /** Set this provider's default model as the main model (the row in account sign-in details). */
    val useAsMain: () -> Unit = {},
    /** 改这家的备注;null 是去掉备注。 */
    val rename: (String?) -> Unit = {},
)

/**
 * 服务商详情里的密钥区:标题按池里 Key 的数量说话,每把 Key 一行(多把时可单独移除),末尾常驻「添加」。
 * [keys] 是这家服务商池里的 Key 原文,[showKeys] 为真时才明文显示。
 */
@Composable
internal fun KeyPoolSection(
    keys: List<String>, showKeys: Boolean,
    onToggleShow: () -> Unit, onRemove: (String) -> Unit, onAdd: () -> Unit,
) {
    val c = MikaTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GroupLabel(KeyPoolCopy.sectionTitle(keys.size), Modifier.weight(1f))
        if (keys.isNotEmpty()) {
            Text(
                stringResource(if (showKeys) R.string.model_keys_hide else R.string.model_keys_show),
                color = c.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable(onClick = onToggleShow)
                    .padding(end = 4.dp, top = 8.dp, bottom = 4.dp)
                    .testTag("toggle-show-keys"),
            )
        }
    }
    GroupCard {
        if (keys.isEmpty()) {
            ListRow(
                null, stringResource(R.string.model_key_not_configured),
                subtitle = stringResource(R.string.model_key_not_configured_hint), first = true,
            ) { RowValue(stringResource(R.string.model_key_required), ValueTone.Bad) }
        } else {
            keys.forEachIndexed { i, k ->
                val displayKey = if (showKeys) k else when {
                    k.length > 14 -> "${k.take(8)}…${k.takeLast(4)}"
                    k.length > 8  -> "${k.take(4)}…${k.takeLast(4)}"
                    else          -> "••••••••"
                }
                ListRow(null, stringResource(R.string.model_key_number, i + 1), subtitle = displayKey, first = i == 0) {
                    RowValue(stringResource(R.string.model_key_active), ValueTone.Ok)
                    if (keys.size > 1) {
                        Text(
                            stringResource(R.string.model_key_remove),
                            color = c.bad,
                            style = MikaType.Caption,
                            modifier = Modifier
                                .clickable { onRemove(k) }
                                .padding(start = 12.dp, top = 2.dp, bottom = 2.dp)
                                .testTag("remove-key-$i"),
                        )
                    }
                }
            }
        }
        ListRow(
            icon = MikaIcons.Plus,
            title = stringResource(R.string.model_key_add),
            subtitle = stringResource(if (keys.isEmpty()) R.string.model_key_add_first_hint else R.string.model_key_add_another_hint),
            first = keys.isEmpty(),
            onClick = onAdd,
        ) { RowChevron() }
    }
    KeyPoolCopy.footerNote(keys.size)?.let { FooterNote(it) }
}

/**
 * 一个服务商:连接(状态、接口、地址、Key 可更换)、几个模型(在用的在前,最多 [MODEL_PREVIEW] 个,其余点「查看全部」)、删除。
 * 点一个模型从底部弹出「用作」。页面长度基本固定,模型再多也不会把删除挤到很远。
 */
@Composable
fun ProviderDetailContent(ui: ModelSetupUi, p: Provider, actions: ProviderDetailActions, onBack: () -> Unit) {
    val c = MikaTheme.colors
    var changingKey by remember { mutableStateOf(false) }
    var addingKey by remember { mutableStateOf(false) }
    var showKeys by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRetest by remember(p.id) { mutableStateOf<String?>(null) }
    var editingBudget by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var customBudget by remember { mutableStateOf("") }
    var budgetSaveFailed by remember { mutableStateOf(false) }
    var using by remember { mutableStateOf<String?>(null) }
    val state = ui.listing[p.id]
    val list = ui.sortedOptions(p)
    Page(ModelsCopy.providerName(p), onBack) {
        // ── Connection status ──────────────────────────────────────────────
        if (p.kind == ProviderConfig.Kind.ChatGpt) {
            GroupLabel(ModelsCopy.CONNECTION)
            ChatGptConnection(ui, state, actions)
        } else if (p.kind == ProviderConfig.Kind.Xai || p.kind == ProviderConfig.Kind.OpenCodeGo || p.signIn == ProviderStore.OPENROUTER_SIGN_IN) {
            GroupLabel(ModelsCopy.CONNECTION)
            AccountConnection(ui, p, state, actions)
        } else {
            // Status pill + endpoint at a glance
            GroupLabel(ModelsCopy.CONNECTION)
            GroupCard {
                ListRow(null, ModelsCopy.STATUS, first = true) {
                    when (state) {
                        ListState.Running -> RowValue(ModelsCopy.LISTING)
                        is ListState.Failed -> RowValue(ModelsCopy.LIST_FAILED, ValueTone.Bad)
                        else -> RowValue(ModelsCopy.CONNECTED, ValueTone.Ok)
                    }
                }
                ListRow(null, ModelsCopy.BASE_URL) { RowValue(ModelsCopy.shortUrl(p.baseUrl)) }
                ListRow(
                    null, stringResource(R.string.provider_nickname_label), onClick = { renaming = true },
                    modifier = Modifier.testTag("provider-nickname"),
                ) {
                    RowValue(p.nickname ?: ModelsCopy.UNSET)
                    RowChevron()
                }
            }
            if (renaming) {
                ProviderRenameDialog(
                    current = p.nickname,
                    onDismiss = { renaming = false },
                    onConfirm = { nickname -> actions.rename(nickname); renaming = false },
                )
            }
            (state as? ListState.Failed)?.let { FooterNote(ModelSetupCopy.failed(it.message), color = c.bad) }

            KeyPoolSection(
                ui.providerKeyMap[p.id].orEmpty(), showKeys,
                onToggleShow = { showKeys = !showKeys },
                onRemove = actions.removeKey,
                onAdd = { addingKey = true },
            )
        }

        GroupCard {
            ListRow(
                MikaIcons.Clock, stringResource(R.string.models_daily_token_budget),
                p.dailyTokenBudget?.let(::tokenBudgetNumber) ?: stringResource(R.string.settings_token_budget_unlimited),
                first = true,
                onClick = {
                    customBudget = p.dailyTokenBudget?.takeIf { it !in TokenBudgetLimits.PRESETS }?.toString().orEmpty()
                    budgetSaveFailed = false
                    editingBudget = true
                },
            )
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GroupLabel(ModelsCopy.MODELS, Modifier.weight(1f))
            BarIconButton(MikaIcons.Refresh, ModelsCopy.REFRESH, actions.refresh, enabled = state != ListState.Running)
        }
        GroupCard {
            list.take(MODEL_PREVIEW).forEachIndexed { i, o ->
                ModelListRow(o, ui.rolesOf(p.id, o.id), first = i == 0) { using = o.id }
            }
            if (list.size > MODEL_PREVIEW) {
                ListRow(null, ModelsCopy.viewAll(list.size), titleColor = c.accent, onClick = actions.showAll) { RowChevron() }
            }
            ManualModel(p, state = null, first = list.isEmpty()) { using = it }
        }
        if (list.isNotEmpty()) FooterNote(ModelsCopy.MODELS_NOTE)
        // Saved skipping the test: say clearly that it has not been tested yet, not that "this provider offers no model list" — that would be wrong for it
        val untested = ui.untested(p)
        val unverified = ui.unverified(p)
        if (untested) {
            FooterNote(ModelsCopy.UNTESTED_NOTE, Modifier.testTag("provider-untested"), color = c.warn)
        } else if (unverified.isNotEmpty()) {
            FooterNote(ModelsCopy.unverifiedNote(unverified), Modifier.testTag("provider-unverified"), color = c.warn)
        }
        ui.usedChatModels(p).forEach { model ->
            val retest = ui.retest[p.id to model]
            MikaButton(
                if (retest == ListState.Running) ModelSetupCopy.TESTING else tr(R.string.models_retest_model, model),
                { confirmRetest = model },
                Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("provider-retest-${model}"),
                kind = ButtonKind.Secondary, size = ButtonSize.Medium, enabled = retest != ListState.Running,
            )
            (retest as? ListState.Failed)?.let { FooterNote(it.message, Modifier.testTag("provider-retest-failed"), color = c.bad) }
        }
        when {
            // No models were listed when this was saved skipping the test, so that must not count as evidence that "this provider offers no list"
            p.models?.isEmpty() == true && !untested -> FooterNote(ModelsCopy.NO_LIST_NOTE)
            p.models == null && list.isEmpty() && state != ListState.Running -> FooterNote(ModelsCopy.NOT_LISTED)
        }

        GroupCard(Modifier.padding(top = 8.dp)) {
            ListRow(MikaIcons.Trash, ModelsCopy.DELETE, titleColor = c.bad, tone = TileTone.Bad, first = true, onClick = { confirmDelete = true })
        }
    }

    confirmRetest?.let { model ->
        MikaDialog(
            onDismissRequest = { confirmRetest = null },
            title = { Text(tr(R.string.models_retest_confirm_title)) },
            text = { Text(tr(R.string.models_retest_confirm_body, model, ModelsCopy.providerName(p))) },
            confirmButton = {
                TextButton(
                    { confirmRetest = null; actions.retestModel(model) },
                    modifier = Modifier.testTag("provider-confirm-retest"),
                ) { Text(ModelsCopy.RETEST) }
            },
            dismissButton = { TextButton({ confirmRetest = null }) { Text(ModelSetupCopy.CANCEL) } },
        )
    }

    if (editingBudget) TokenBudgetDialog(
        title = stringResource(R.string.models_daily_token_budget),
        description = stringResource(R.string.models_daily_token_budget_sub),
        current = p.dailyTokenBudget,
        custom = customBudget,
        onCustom = { customBudget = it.filter(Char::isDigit).take(9); budgetSaveFailed = false },
        onPick = {
            if (actions.setDailyBudget(it)) editingBudget = false else budgetSaveFailed = true
        },
        onDismiss = { editingBudget = false },
        saveError = if (budgetSaveFailed) stringResource(R.string.models_daily_token_budget_save_failed) else null,
    )
    using?.let { model -> UseAsSheet(ui, p, model, actions, onDismiss = { using = null }) }
    if (changingKey) {
        var key by remember { mutableStateOf("") }
        var notice by remember { mutableStateOf(ApiKeyText.Notice.None) }
        val blocked = notice == ApiKeyText.Notice.IllegalCharacters
        val parsedKeys = remember(key) { com.wangzan101.mika.core.model.KeyPoolManager.shared.parseKeys(key) }
        MikaDialog(
            onDismissRequest = { changingKey = false },
            title = { Text(ModelsCopy.CHANGE_KEY) },
            text = {
                KeyboardDismissArea {
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().keepsKeyboard().background(c.fill, MikaShapes.Button)
                                .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.weight(1f)) {
                                if (key.isEmpty()) Text(ModelSetupCopy.KEY_POOL_HINT, style = MikaType.Body, color = c.ink3)
                                BasicTextField(
                                    key,
                                    { raw ->
                                        val accepted = ApiKeyText.accept(raw)
                                        key = accepted.text
                                        notice = accepted.notice
                                    },
                                    Modifier.fillMaxWidth().testTag("new-key"), singleLine = false, maxLines = 4,
                                    textStyle = MikaType.Body.copy(color = c.ink), cursorBrush = SolidColor(c.accent),
                                    visualTransformation = PasswordVisualTransformation(),
                                )
                            }
                            if (key.isNotEmpty()) {
                                FieldClearButton(
                                    stringResource(R.string.model_key_clear),
                                    {
                                        val cleared = ApiKeyText.accept("")
                                        key = cleared.text
                                        notice = cleared.notice
                                    },
                                    Modifier.testTag("new-key-clear"),
                                )
                            }
                        }
                        if (parsedKeys.size > 1) {
                            Text(
                                KeyPoolCopy.detectedChange(parsedKeys.size),
                                style = MikaType.Caption,
                                color = c.ok,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        } else {
                            ApiKeyNoticeNote(notice, tag = "new-key-notice")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parsed = com.wangzan101.mika.core.model.KeyPoolManager.shared.parseKeys(key.trim())
                        if (blocked || parsed.isEmpty() || !parsed.all { ApiKeyText.isHeaderLegal(it) }) return@TextButton
                        changingKey = false
                        actions.changeKey(key)
                    },
                    enabled = key.isNotBlank() && !blocked,
                ) { Text(ModelsCopy.SAVE) }
            },
            dismissButton = { TextButton({ changingKey = false }) { Text(ModelSetupCopy.CANCEL) } },
        )
    }
    if (addingKey) {
        var newKeyText by remember { mutableStateOf("") }
        var showAddKey by remember { mutableStateOf(false) }
        var notice by remember { mutableStateOf(ApiKeyText.Notice.None) }
        val blocked = notice == ApiKeyText.Notice.IllegalCharacters
        val parsedAdding = remember(newKeyText) { com.wangzan101.mika.core.model.KeyPoolManager.shared.parseKeys(newKeyText) }
        MikaDialog(
            onDismissRequest = { addingKey = false },
            title = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.model_key_add), Modifier.weight(1f))
                    if (newKeyText.isNotEmpty()) {
                        Text(
                            stringResource(if (showAddKey) R.string.hide else R.string.show),
                            color = c.accent,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clickable { showAddKey = !showAddKey }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            },
            text = {
                KeyboardDismissArea {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.model_key_add_note),
                            style = MikaType.Caption, color = c.ink2,
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                        Box(
                            Modifier.fillMaxWidth().keepsKeyboard()
                                .background(c.fill, MikaShapes.Button)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            if (newKeyText.isEmpty()) {
                                Text(
                                    stringResource(R.string.model_key_hint),
                                    style = MikaType.Body, color = c.ink3,
                                )
                            }
                            BasicTextField(
                                newKeyText,
                                { raw ->
                                    val accepted = ApiKeyText.accept(raw)
                                    newKeyText = accepted.text
                                    notice = accepted.notice
                                },
                                Modifier.fillMaxWidth().testTag("add-key-input"),
                                singleLine = false,
                                maxLines = 5,
                                textStyle = MikaType.Body.copy(
                                    color = c.ink,
                                    fontFamily = if (showAddKey) FontFamily.Monospace else null,
                                ),
                                cursorBrush = SolidColor(c.accent),
                                visualTransformation = if (showAddKey) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                            )
                        }
                        // Live feedback
                        when {
                            parsedAdding.size > 1 -> Text(
                                KeyPoolCopy.detectedAdd(parsedAdding.size),
                                style = MikaType.Caption, color = c.ok,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            newKeyText.isNotBlank() && !blocked -> Text(
                                stringResource(R.string.model_key_one_ready),
                                style = MikaType.Caption, color = c.ok,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            else -> ApiKeyNoticeNote(notice, tag = "add-key-notice")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parsed = com.wangzan101.mika.core.model.KeyPoolManager.shared.parseKeys(newKeyText.trim())
                        if (blocked || parsed.isEmpty() || !parsed.all { ApiKeyText.isHeaderLegal(it) }) return@TextButton
                        addingKey = false
                        actions.addKey(newKeyText.trim())
                    },
                    enabled = newKeyText.isNotBlank() && !blocked,
                ) { Text(KeyPoolCopy.addConfirm(parsedAdding.size, ModelsCopy.SAVE)) }
            },
            dismissButton = { TextButton({ addingKey = false }) { Text(ModelSetupCopy.CANCEL) } },
        )
    }
    if (confirmDelete) {
        MikaDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(ModelsCopy.deleteTitle(ModelsCopy.providerName(p))) },
            text = { Text(ModelsCopy.deleteBody(ui.rolesOf(p), actions.voiceUsage(p.id))) },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    actions.remove()
                }, Modifier.testTag("confirm-delete")) { Text(ModelsCopy.DELETE_CONFIRM, color = c.bad) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(ModelSetupCopy.CANCEL) } },
        )
    }
}

/** 改账号备注:空着保存就是去掉备注。字数在输入时按 [Provider.MAX_NICKNAME] 截。 */
@Composable
internal fun ProviderRenameDialog(current: String?, onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var text by remember { mutableStateOf(current.orEmpty()) }
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.provider_rename_title)) },
        text = {
            MikaTextField(
                value = text,
                onValueChange = { text = nicknameInput(it) },
                modifier = Modifier.fillMaxWidth().testTag("provider-rename-input"),
                singleLine = true,
                label = { Text(stringResource(R.string.provider_nickname_label)) },
                placeholder = { Text(stringResource(R.string.provider_nickname_hint)) },
            )
        },
        confirmButton = {
            TextButton({ onConfirm(Provider.cleanNickname(text)) }, modifier = Modifier.testTag("provider-rename-save")) {
                Text(ModelsCopy.SAVE)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(ModelSetupCopy.CANCEL) } },
    )
}
