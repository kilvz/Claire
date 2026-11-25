package com.wangzan101.mika.settings.ui.models

import android.net.Uri
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.core.model.ApiKeyText
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderKeySites
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.Hairline
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.SelectChip
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.openInBrowser

/** Add provider: first the "sign in with account" group (no Key needed), then the Key-based flow: preset chips → (when custom) protocol and URL → API Key → "Test and save". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddProviderForm(ui: ModelSetupUi, actions: ModelSetupActions, standalone: Boolean = false) {
    val c = MikaTheme.colors
    val f = ui.form
    var showKey by remember { mutableStateOf(false) }
    var confirmUntested by remember { mutableStateOf(false) }
    if (!standalone) {
        Text(
            ModelsCopy.ADD_PROVIDER, Modifier.padding(top = 16.dp, bottom = 8.dp),
            style = MikaType.Body, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        )
    }
    // Sign-in options (ChatGPT, xAI, OpenCode Go, OpenRouter)
    AccountSignInGroup(ui, actions.openSignIn, actions.openProvider)
    // The Key-based group: preset chips
    GroupLabel(ModelsCopy.KEY_GROUP)
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProviderPresets.all.forEach { p ->
            SelectChip(if (p == ProviderPresets.custom) stringResource(R.string.preset_custom) else p.label, f.preset == p.label, { actions.selectPreset(p.label) }, Modifier.testTag("preset-${p.label}"))
        }
    }
    GroupCard {
        if (f.isCustom) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(ModelSetupCopy.KIND, Modifier.padding(end = 12.dp), style = MikaType.Body, color = c.ink)
                // The four protocols wrap onto the next line when they do not fit on one
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProviderConfig.Kind.withApiKey.forEach { k -> SelectChip(ModelsCopy.kindLabel(k), f.kind == k, { actions.setKind(k) }, small = true) }
                }
            }
        }
        if (f.isCloudflare) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 10.dp, bottom = 4.dp)
            ) {
                Text(
                    stringResource(R.string.model_setup_account_id),
                    style = MikaType.Body, color = c.ink,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(c.fill, MikaShapes.Button)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    if (f.accountId.isEmpty()) {
                        Text(
                            stringResource(R.string.model_setup_account_id_placeholder),
                            style = MikaType.Body, color = c.ink3,
                        )
                    }
                    androidx.compose.foundation.text.BasicTextField(
                        value = f.accountId,
                        onValueChange = actions.setAccountId,
                        modifier = Modifier.fillMaxWidth().testTag("setup-account-id"),
                        singleLine = true,
                        textStyle = MikaType.Body.copy(
                            color = c.ink,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            autoCorrectEnabled = false,
                        ),
                    )
                }
            }
        } else if (f.urlEditable) {
            InputRow(
                ModelSetupCopy.BASE_URL, f.baseUrl, actions.setBaseUrl, "https://…", first = !f.isCustom,
                keyboard = KeyboardType.Uri, modifier = Modifier.testTag("setup-base-url"),
            )
        }
        if (f.isCustom) {
            // Optional: the hint itself shows the name used when left empty (the host name of the URL)
            InputRow(
                ModelSetupCopy.NAME, f.name, actions.setName,
                if (f.urlProblem == null) f.defaultName else ModelSetupCopy.NAME_HINT,
                modifier = Modifier.testTag("setup-name"),
            )
        }
        // ── API Key input — multi-line, large, prominent ──────────────────
        val parsedKeys = remember(f.apiKey) { com.wangzan101.mika.core.model.KeyPoolManager.shared.parseKeys(f.apiKey) }
        val poolActive = parsedKeys.size > 1
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = if (!f.urlEditable) 0.dp else 0.dp, bottom = 2.dp)
        ) {
            if (!f.urlEditable) Hairline(start = 0.dp)
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    ModelSetupCopy.API_KEY,
                    style = MikaType.Body, color = c.ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (showKey) stringResource(R.string.hide) else stringResource(R.string.show),
                    Modifier.clickable { showKey = !showKey }.padding(start = 8.dp),
                    fontSize = 13.sp, color = c.accent,
                )
            }
            // Large text area for single or multi-key paste
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(c.fill, MikaShapes.Button)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                if (f.apiKey.isEmpty()) {
                    Text(
                        if (f.keyRequired) ModelSetupCopy.KEY_POOL_HINT
                        else ModelSetupCopy.KEY_OPTIONAL,
                        style = MikaType.Body, color = c.ink3, lineHeight = 20.sp,
                    )
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = f.apiKey,
                    onValueChange = { raw ->
                        val accepted = com.wangzan101.mika.core.model.ApiKeyText.accept(raw)
                        actions.setApiKey(accepted.text)
                    },
                    modifier = Modifier.fillMaxWidth().testTag("setup-api-key"),
                    minLines = 2,
                    maxLines = 5,
                    textStyle = MikaType.Body.copy(
                        color = c.ink,
                        fontFamily = if (!showKey) null else androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                    visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None
                                           else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
            }
            // Live pool feedback
            when {
                poolActive -> Text(
                    "${parsedKeys.size} keys detected — pool will auto-rotate on rate-limit or timeout",
                    Modifier.padding(top = 6.dp).testTag("setup-key-notice"),
                    style = MikaType.Caption, color = c.ok,
                )
                f.keyNotice == com.wangzan101.mika.core.model.ApiKeyText.Notice.IllegalCharacters -> Text(
                    ModelSetupCopy.KEY_ILLEGAL,
                    Modifier.padding(top = 6.dp).testTag("setup-key-notice-illegal"),
                    style = MikaType.Caption, color = c.bad,
                )
                f.keyNotice == com.wangzan101.mika.core.model.ApiKeyText.Notice.InvisibleRemoved -> Text(
                    ModelSetupCopy.KEY_INVISIBLE_REMOVED,
                    Modifier.padding(top = 6.dp).testTag("setup-key-notice"),
                    style = MikaType.Caption, color = c.ink2,
                )
                f.apiKey.isNotBlank() -> Text(
                    "1 key ready",
                    Modifier.padding(top = 6.dp),
                    style = MikaType.Caption, color = c.ok,
                )
            }
            // When the provider can be recognized from the URL, link straight to the official Key page; hide it when it cannot
            ProviderKeySites.forBaseUrl(f.baseUrl)?.let { url ->
                val context = LocalContext.current
                Text(
                    ModelSetupCopy.KEY_WEBSITE,
                    Modifier.padding(top = 6.dp).clickable { openInBrowser(context, url) }.testTag("setup-key-website"),
                    style = MikaType.Caption, color = c.accent,
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        if (f.showModel) {
            InputRow(
                ModelSetupCopy.MODEL_NAME, f.model, actions.setModel, ModelSetupCopy.modelExample(f.exampleKind),
                keyboard = KeyboardType.Uri, modifier = Modifier.testTag("setup-model"),
            )
        }
    }
    // When the wanted model is not in the list, or this provider offers no list, it can always be entered manually (that field is already shown when no models were listed)
    if (!f.noList) {
        Text(
            if (f.manual) ModelSetupCopy.MANUAL_OFF else ModelSetupCopy.MANUAL_ON,
            Modifier.padding(start = 16.dp, top = 8.dp).clickable { actions.setManual(!f.manual) }.padding(vertical = 4.dp).testTag("setup-manual-model"),
            style = MikaType.Caption, color = c.accent,
        )
    }
    if (f.manual && !f.noList) {
        FooterNote(tr(R.string.model_manual_catalog_note), Modifier.testTag("setup-manual-note"))
    }
    f.urlProblem?.takeIf { f.urlEditable && f.baseUrl.isNotBlank() }?.let { FooterNote(it, color = c.bad) }
    f.suggestedPreset?.let { preset -> PresetSuggestion(f, preset.label) { actions.selectPreset(preset.label) } }
    if (f.noList) {
        FooterNote(tr(R.string.model_no_catalog_note), Modifier.testTag("setup-no-list"))
    }
    if (f.isSelfHosted) FooterNote(ModelSetupCopy.SELF_HOSTED_NOTE, Modifier.testTag("setup-self-hosted"))
    f.unencryptedWarning?.let { FooterNote(it, Modifier.testTag("setup-url-unencrypted"), color = c.warn) }
    MikaButton(
        if (f.test == ListState.Running) ModelSetupCopy.TESTING else ModelSetupCopy.TEST_AND_SAVE,
        actions.testAndSave, Modifier.fillMaxWidth().padding(top = 2.dp).testTag("setup-test"),
        kind = ButtonKind.Secondary, size = ButtonSize.Medium, enabled = f.canTest,
    )
    (f.test as? ListState.Failed)?.let { failed ->
        Text(
            ModelSetupCopy.failed(failed.message), Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).testTag("setup-test-failed"),
            style = MikaType.Caption, color = c.bad,
        )
    }
    // For saving without a successful test: make it a button instead of hiding it as a small line — it is a real action, not an explanation
    MikaButton(
        ModelSetupCopy.SAVE_UNTESTED, { confirmUntested = true },
        Modifier.fillMaxWidth().padding(top = 6.dp).testTag("setup-save-untested"),
        kind = ButtonKind.Tonal, size = ButtonSize.Small, enabled = f.canSaveUntested,
    )
    if (confirmUntested) MikaDialog(
        onDismissRequest = { confirmUntested = false },
        title = { Text(ModelSetupCopy.SAVE_UNTESTED_TITLE) },
        text = { Text(ModelSetupCopy.SAVE_UNTESTED_BODY) },
        confirmButton = {
            TextButton({
                confirmUntested = false
                actions.saveUntested()
            }) { Text(ModelSetupCopy.SAVE_UNTESTED_CONFIRM) }
        },
        dismissButton = {
            TextButton({ confirmUntested = false }) { Text(stringResource(R.string.cancel)) }
        },
    )
    FooterNote(tr(R.string.model_test_catalog_note), Modifier.padding(top = 6.dp))
}

/**
 * The line under the API Key field: secondary text color when zero-width characters were removed, error color for line breaks or other characters that cannot go into a request header.
 * Uses the same type scale as the other [FooterNote]s on the form.
 */
@Composable
internal fun ApiKeyNoticeNote(notice: ApiKeyText.Notice, tag: String = "setup-key-notice") {
    val c = MikaTheme.colors
    when (notice) {
        ApiKeyText.Notice.None -> Unit
        ApiKeyText.Notice.InvisibleRemoved ->
            FooterNote(ModelSetupCopy.KEY_INVISIBLE_REMOVED, Modifier.testTag(tag), color = c.ink2)
        ApiKeyText.Notice.IllegalCharacters ->
            FooterNote(ModelSetupCopy.KEY_ILLEGAL, Modifier.testTag("$tag-illegal"), color = c.bad)
    }
}

/** The custom entry is some provider's official URL: suggest switching to that provider's preset (without blocking), mention it when the protocol does not match, and switch over on one tap while keeping the Key. */
@Composable
private fun PresetSuggestion(f: AddForm, label: String, onUse: () -> Unit) {
    val c = MikaTheme.colors
    FooterNote(ModelSetupCopy.presetSuggestion(f), Modifier.padding(top = 2.dp).testTag("setup-preset-hint"), color = c.warn)
    Text(
        ModelSetupCopy.usePreset(label),
        Modifier.padding(start = 16.dp, bottom = 12.dp).clickable(onClick = onUse).testTag("setup-use-preset").padding(vertical = 4.dp),
        style = MikaType.Label, color = c.accent,
    )
}
