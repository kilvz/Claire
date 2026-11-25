package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaTextField
import androidx.compose.foundation.layout.padding
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.components.InputRow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.ModelContextSettings
import com.wangzan101.mika.settings.MODEL_READ_TIMEOUT_SECONDS
import com.wangzan101.mika.settings.ModelOption
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.ui.components.CapabilityTag
import com.wangzan101.mika.ui.components.TagTone
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

internal fun hasModelContextControl(ui: ModelSetupUi, role: ModelRole): Boolean =
    (ui.roles[role] is RoleChoice.Model || ui.roles[role] is RoleChoice.Group) && ui.configFor(role) != null

@Composable
internal fun ModelContextControl(ui: ModelSetupUi, role: ModelRole,
    onSave: (String, ModelContextSettings) -> Boolean, modifier: Modifier = Modifier,
) {
    // Both a concrete model and a group can be configured; for a group the first member is used, and per-member settings live on the group edit page
    if (ui.roles[role] !is RoleChoice.Model && ui.roles[role] !is RoleChoice.Group) return
    val cfg = ui.configFor(role) ?: return
    val saved = ui.providerOf(cfg)?.contextSettings?.get(cfg.model) ?: ModelContextSettings()
    var open by rememberSaveable(cfg.id) { mutableStateOf(false) }
    ModelOptionButton(stringResource(R.string.model_context_title), "context-${role.name.lowercase()}", modifier) { open = true }
    if (!open) return
    ModelContextDialog(cfg, saved, "context", { settings -> onSave(cfg.id, settings) }) {
        open = false
    }
}

/** Context window / compaction trigger for one model config; shared by the role page and the group edit page. When [onSave] returns false the dialog stays open and shows the reason. */
@Composable
internal fun ModelContextDialog(
    cfg: ProviderConfig, saved: ModelContextSettings, tagPrefix: String,
    onSave: (ModelContextSettings) -> Boolean, onDismiss: () -> Unit,
) {
    var window by rememberSaveable(cfg.id) { mutableStateOf(saved.windowTokens?.toString().orEmpty()) }
    var trigger by rememberSaveable(cfg.id) { mutableStateOf(saved.triggerTokens?.toString().orEmpty()) }
    var readTimeout by rememberSaveable(cfg.id) { mutableStateOf(saved.readTimeoutSeconds?.toString().orEmpty()) }
    var saveFailed by remember { mutableStateOf(false) }
    fun positiveInt(text: String) = text.trim().takeIf { it.all { c -> c in '0'..'9' } }?.toIntOrNull()?.takeIf { it > 0 }
    val settings = ModelContextSettings(positiveInt(window), positiveInt(trigger),
        positiveInt(readTimeout)?.takeIf { it in MODEL_READ_TIMEOUT_SECONDS })
    val modelWindow = cfg.capabilities.contextTokens
    val limits = settings.limits(modelWindow)
    val badWindow = window.isNotBlank() && settings.windowTokens == null
    val badTrigger = trigger.isNotBlank() && (settings.triggerTokens == null || (!badWindow && settings.triggerTokens != limits.trigger))
    val badReadTimeout = readTimeout.isNotBlank() && settings.readTimeoutSeconds == null
    val keyboard = KeyboardOptions(keyboardType = KeyboardType.Number)
    MikaDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        title = { Text(stringResource(R.string.model_context_title)) },
        text = {
            val c = MikaTheme.colors
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${cfg.model} · ${stringResource(R.string.model_context_note)}", style = MikaType.Hint, color = c.ink3)
                // Three numbers grouped on a light background block, the same as settings input rows; leave blank to use the automatic value, shown in the placeholder
                Column(Modifier.fillMaxWidth().clip(MikaShapes.Button).background(c.fill)) {
                    InputRow(stringResource(R.string.model_context_window), window, { window = it; saveFailed = false },
                        modelWindow.toString(), first = true, keyboard = KeyboardType.Number,
                        labelWidth = 150.dp, modifier = Modifier.testTag("$tagPrefix-window"))
                    InputRow(stringResource(R.string.model_context_trigger), trigger, { trigger = it; saveFailed = false },
                        limits.trigger.toString(), keyboard = KeyboardType.Number,
                        labelWidth = 150.dp, modifier = Modifier.testTag("$tagPrefix-trigger"))
                    InputRow(stringResource(R.string.model_context_read_timeout), readTimeout, { readTimeout = it; saveFailed = false },
                        "120", keyboard = KeyboardType.Number,
                        labelWidth = 150.dp, modifier = Modifier.testTag("context-read-timeout"))
                }
                val problem = when {
                    badWindow || badTrigger -> stringResource(R.string.model_context_integer)
                    badReadTimeout -> "${stringResource(R.string.model_context_integer)} (1–${MODEL_READ_TIMEOUT_SECONDS.last})"
                    saveFailed -> stringResource(R.string.model_context_save_failed)
                    else -> null
                }
                if (problem != null) Text(problem, style = MikaType.Hint, color = c.bad)
                if (!badWindow && (trigger.isBlank() || settings.triggerTokens != null)) Text(
                    stringResource(R.string.model_context_effective, limits.window, limits.trigger) +
                        if (limits.usable > 0) " · " + stringResource(R.string.model_context_range, limits.minTrigger.coerceAtLeast(1), limits.usable) else "",
                    Modifier.testTag("$tagPrefix-effective"), style = MikaType.Hint, color = c.ink3)
                Text(stringResource(R.string.reset), Modifier.clip(MikaShapes.Chip)
                    .clickable { window = ""; trigger = ""; readTimeout = ""; saveFailed = false }
                    .padding(vertical = 6.dp), style = MikaType.Label, color = c.ink2)
            }
        },
        confirmButton = { TextButton(onClick = {
            if (onSave(settings)) onDismiss() else saveFailed = true
        }, enabled = !badWindow && !badTrigger && !badReadTimeout, modifier = Modifier.testTag("$tagPrefix-save")) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Capability tags of a model; when [accent] is false everything uses the neutral color (in list rows the accent color is reserved for the "in use" pill). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Tags(o: ModelOption, forVision: Boolean = false, accent: Boolean = true) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ModelSetupCopy.tags(o, forVision).forEach { (text, strong) -> CapabilityTag(text, if (strong && accent) TagTone.Accent else TagTone.Neutral) }
    }
}

internal val ModelIdStyle = MikaType.Value.copy(fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
