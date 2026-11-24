package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.ModelContextSettings
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.settings.VoiceChoice
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.Hairline
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** Providers: one row per provider (stating which roles it serves) plus "Add provider"; when there are none or add was tapped, the form is laid out right here. */
@Composable
internal fun ProvidersSection(ui: ModelSetupUi, actions: ModelSetupActions) {
    val c = MikaTheme.colors
    if (ui.providers.isNotEmpty()) {
        GroupCard {
            ui.providers.forEachIndexed { i, p ->
                val line = if (ui.signedOut(p)) ModelsCopy.CHATGPT_SIGNED_OUT else ModelsCopy.providerLine(p, ui.listing[p.id], ui.rolesOf(p))
                // There is a model in use that failed its test, or the provider was saved skipping the test and is already in use: this row then warns "not verified"
                val warning = ModelsCopy.UNVERIFIED.takeIf {
                    ui.unverified(p).isNotEmpty() || (ui.untested(p) && ui.rolesOf(p).isNotEmpty())
                }
                ProviderRow(p, line, first = i == 0, warning = warning) { actions.openProvider(p.id) }
            }
            if (!ui.showForm) {
                ListRow(
                    MikaIcons.Plus, ModelsCopy.ADD_PROVIDER, titleColor = c.accent, tone = TileTone.Accent,
                    onClick = actions.openForm,
                )
            }
        }
    }
    ProbeNote(ui, actions)
    if (ui.providers.isEmpty()) AddProviderForm(ui, actions)
    FooterNote(ModelsCopy.KEY_NOTE)
}

/** Note shown when a provider is saved; if the suggested model failed its test and there is no main model yet, it offers "choose main model" (which test-calls on selection). */
@Composable
internal fun ProbeNote(ui: ModelSetupUi, actions: ModelSetupActions) {
    val c = MikaTheme.colors
    // First-time onboarding: the expanded form collapses as soon as the provider is saved while the suggested model is still being test-called, so say so here
    if (ui.form.test == ListState.Running && !ui.formOpen && ui.providers.isNotEmpty()) {
        FooterNote(ModelSetupCopy.TESTING, Modifier.testTag("setup-probe-running"))
    }
    ui.probeNote?.let { FooterNote(it, Modifier.testTag("setup-probe-note"), color = c.warn) }
    // None of the just-saved provider's models passed its test: go straight to choosing the main model (which test-calls on selection)
    if (ui.pickToProbe != null && !ui.roles.hasMain) {
        Text(
            ModelSetupCopy.PICK_MAIN,
            Modifier.padding(start = 16.dp, bottom = 12.dp).clickable { actions.pickRole(ModelRole.Main) }.testTag("setup-pick-main").padding(vertical = 4.dp),
            style = MikaType.Label, color = c.accent,
        )
    }
}

/** Chat and voice models share one group of role cards. */
@Composable
internal fun RolesSection(ui: ModelSetupUi, onPick: (ModelRole) -> Unit, onReasoning: (ModelRole, String?) -> Unit,
    onContext: ((String, ModelContextSettings) -> Boolean)? = null,
    onVoice: (() -> Unit)? = null, voiceChoice: VoiceChoice? = null,
) {
    GroupCard {
        ModelRole.entries.forEachIndexed { i, role ->
            RoleCard(ModelsCopy.roleIcon(role), ModelsCopy.roleLabel(role), ModelsCopy.roleLine(ui, role), first = i == 0, tag = "role-${role.name.lowercase()}") {
                onPick(role)
            }
            if (ui.roles[role] == RoleChoice.SameAsMain) {
                ReasoningControl(ui, role) { onReasoning(role, it) }
            } else {
                val reasoning = hasReasoningAction(ui, role)
                val context = onContext != null && hasModelContextControl(ui, role)
                if (reasoning || context) {
                    Column(Modifier.padding(start = 70.dp, end = 16.dp)) {
                        Hairline(start = 0.dp)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (reasoning) ReasoningControl(ui, role, Modifier.weight(1f)) { onReasoning(role, it) }
                            if (reasoning && context) Box(Modifier.width(0.5.dp).height(14.dp).background(MikaTheme.colors.hair))
                            if (onContext != null && context) ModelContextControl(ui, role, onContext, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        if (onVoice != null) {
            RoleCard(
                ModelsCopy.VOICE_ICON, stringResource(R.string.role_voice), ModelsCopy.voiceLine(ui.providers, voiceChoice),
                tag = "role-voice", onClick = onVoice,
            )
        }
    }
}

/** Speech to text is configured separately. */
@Composable
internal fun SpeechSection(ui: ModelSetupUi, onSpeech: (() -> Unit)?) {
    if (onSpeech == null) return
    GroupCard {
        ListRow(ModelsCopy.SPEECH_ICON, stringResource(R.string.role_speech), first = true, onClick = onSpeech) {
            RowValue(ModelsCopy.speechLine(ui).title)
            RowChevron()
        }
    }
}

/** Image model: the model used for generating/editing images, on its own row like voice. */
@Composable
internal fun ImageModelSection(ui: ModelSetupUi, onImage: (() -> Unit)?) {
    if (onImage == null) return
    GroupCard {
        ListRow(ModelsCopy.IMAGE_ICON, stringResource(R.string.role_image), first = true, onClick = onImage) {
            RowValue(ModelsCopy.imageLine(ui).title)
            RowChevron()
        }
    }
}

/** Entry to the unified voice model catalog: capabilities and protocols are declared here; the speech input, read-aloud and call pages only handle selection and usage preferences. */
@Composable
internal fun VoiceCatalogSection(onOpenVoiceCatalog: (() -> Unit)?) {
    if (onOpenVoiceCatalog == null) return
    GroupCard {
        ListRow(
            ModelsCopy.VOICE_ICON, stringResource(R.string.models_voice_catalog),
            first = true, onClick = onOpenVoiceCatalog,
        ) { RowChevron() }
    }
}
