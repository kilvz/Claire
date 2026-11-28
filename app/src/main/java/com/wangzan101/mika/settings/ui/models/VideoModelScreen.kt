package com.wangzan101.mika.settings.ui.models

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.VideoChoice
import com.wangzan101.mika.settings.VideoProviders
import com.wangzan101.mika.settings.VideoSelection
import com.wangzan101.mika.settings.VideoSettings
import com.wangzan101.mika.settings.VideoSettingsState
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.theme.MikaTheme

/**
 * Settings › Models › Video model: choose provider, model, default duration/aspect ratio/resolution, and the maximum per task; video_generate is offered only after it is turned on.
 * Lists only providers that can generate video ([VideoProviders.compatible]); the available durations, aspect ratios and resolutions come from the capabilities of the entered model.
 * When the stored choice does not match the current providers, the reason is shown and tasks do not use it either.
 * Like speech transcription and voice models, it is not in [com.wangzan101.mika.core.model.ModelRole].
 */
@Composable
fun VideoModelScreen(store: ProviderStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { VideoSettings.get(context) }
    val state by settings.state.collectAsState()
    val providers by store.providers.collectAsState()
    val c = MikaTheme.colors
    var model by remember { mutableStateOf(state.choice?.model.orEmpty()) }
    var duration by remember { mutableStateOf(state.durationSec.toString()) }
    var maxPerTask by remember { mutableStateOf(state.maxPerTask.toString()) }
    val compatible = VideoProviders.compatible(providers)
    val selection = VideoProviders.resolve(state.choice, providers)
    val target = when (selection) {
        is VideoSelection.Ready -> selection.target
        is VideoSelection.NoModel -> selection.target
        else -> null
    }

    Page(tr(R.string.role_video), onBack) {
        Text(tr(R.string.video_hint), Modifier.padding(top = 4.dp, bottom = 12.dp), fontSize = 14.5.sp, lineHeight = 22.sp, color = c.ink2)
        GroupCard {
            ListRow(
                null, tr(R.string.video_enable), tr(R.string.video_enable_note), first = true,
                trailing = { MikaSwitch(state.enabled, settings::setEnabled) },
            )
        }

        GroupLabel(tr(R.string.video_provider), Modifier.padding(top = 12.dp))
        if (selection is VideoSelection.Stale) FooterNote(tr(R.string.video_choice_stale))
        when {
            providers.isEmpty() -> FooterNote(tr(R.string.video_no_providers))
            compatible.isEmpty() -> FooterNote(tr(R.string.video_no_compatible, VideoProviders.presetLabels().joinToString(" / ")))
            else -> GroupCard {
                compatible.forEachIndexed { i, (p, backend) ->
                    RadioRow(
                        ModelsCopy.providerName(p), selected = target?.provider?.id == p.id, first = i == 0,
                        subtitle = p.baseUrl,
                        onClick = { settings.setChoice(VideoChoice(p.id, model, backend.id)) },
                    )
                }
            }
        }

        val choice = state.choice
        if (target != null && choice != null) {
            val caps = (selection as? VideoSelection.Ready)?.capabilities
            GroupCard {
                InputRow(
                    tr(R.string.video_model_name), model,
                    onChange = { model = it; settings.setChoice(choice.copy(model = it)) },
                    placeholder = target.backend.modelHint, first = true,
                )
                InputRow(
                    tr(R.string.video_duration), duration,
                    onChange = {
                        duration = it
                        it.toIntOrNull()?.let { v -> settings.setDurationSec(v) }
                    },
                    placeholder = caps?.let { tr(R.string.video_duration_range, it.durations.first, it.durations.last) } ?: "5",
                    keyboard = KeyboardType.Number,
                )
                InputRow(
                    tr(R.string.video_max_per_task), maxPerTask,
                    onChange = {
                        maxPerTask = it
                        it.toIntOrNull()?.let { v -> settings.setMaxPerTask(v.coerceIn(1, VideoSettingsState.MAX_PER_TASK_LIMIT)) }
                    },
                    placeholder = "1", keyboard = KeyboardType.Number,
                )
            }

            if (caps != null) {
                // When this model does not support a stored preference, show the one the tool will actually use (consistent with video_generate's fallback rules)
                val aspect = state.aspect.takeIf { it in caps.aspects } ?: caps.aspects.first()
                val resolution = state.resolution.takeIf { it in caps.resolutions } ?: caps.resolutions.first()
                GroupLabel(tr(R.string.video_aspect))
                GroupCard {
                    caps.aspects.forEachIndexed { i, a ->
                        RadioRow(a, aspect == a, { settings.setAspect(a) }, first = i == 0)
                    }
                }
                GroupLabel(tr(R.string.video_resolution))
                GroupCard {
                    caps.resolutions.forEachIndexed { i, r ->
                        RadioRow(r, resolution == r, { settings.setResolution(r) }, first = i == 0)
                    }
                }
            }
        }

        FooterNote(tr(R.string.video_footer), Modifier.padding(top = 10.dp))
    }
}
