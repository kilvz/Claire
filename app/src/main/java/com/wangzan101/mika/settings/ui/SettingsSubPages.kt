package com.wangzan101.mika.settings.ui

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.R
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import com.wangzan101.mika.settings.ThemeAccent
import com.wangzan101.mika.settings.ThemeMode
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.settings.SkinBlur
import com.wangzan101.mika.settings.SkinSpec
import com.wangzan101.mika.ui.components.MikaSwitch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlin.math.roundToInt
import com.wangzan101.mika.browser.ui.WebSitesSection
import com.wangzan101.mika.browser.ui.WebSitesState
import com.wangzan101.mika.ui.Route

// ---------- Phone control ----------

/** Settings › Website logins. */
@Composable
fun WebSitesScreen(state: WebSitesState, onBack: () -> Unit) {
    PageScaffold(stringResource(R.string.settings_web_logins), onBack) { WebSitesSection(state, labeled = false) }
}

// ---------- Advanced ----------

/** Settings › Files & compute environment: allow scripts to go online, network proxy, space used (clean the workspace per conversation), reset the runtime environment. */
@Composable
fun SandboxScreen(onBack: () -> Unit) {
    PageScaffold(stringResource(R.string.settings_sandbox), onBack) { AdvancedSettings() }
}

/** Settings › Remembered action routes: [count] entries, all of which can be cleared (with confirmation); an empty [count] means it has not been read yet. */
@Composable
fun RoutesScreen(count: Int?, onClear: () -> Unit, onBack: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    PageScaffold(stringResource(R.string.settings_routes), onBack) {
        GroupCard {
            ListRow(
                MikaIcons.Route, stringResource(R.string.routes_saved),
                if (count == null) stringResource(R.string.loading) else trPlural(R.plurals.routes_count, count), first = true,
            ) {
                if (count != null && count > 0) Text(
                    stringResource(R.string.routes_clear_all), Modifier.clickable { confirmClear = true }.padding(4.dp),
                    style = MikaType.Value, color = MikaTheme.colors.bad,
                )
            }
        }
    }
    if (confirmClear) {
        MikaDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.routes_clear_title)) },
            text = { Text(stringResource(R.string.routes_clear_body)) },
            confirmButton = { TextButton({ confirmClear = false; onClear() }) { Text(stringResource(R.string.clear)) } },
            dismissButton = { TextButton({ confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Settings › Recovery & repair: several permission switches for phone control, followed by the compatibility self-check entry; [scrollState] is supplied by the page switcher so returning from the self-check stops at the previous position. */
@Composable
fun RecoveryScreen(onBack: () -> Unit, onOpenSelfCheck: () -> Unit = {}, scrollState: ScrollState = rememberScrollState()) {
    PageScaffold(stringResource(R.string.settings_recovery), onBack, scrollState = scrollState) {
        RecoverySwitches()
        GroupCard {
            ListRow(
                MikaIcons.Bug, stringResource(R.string.selfcheck_title), first = true,
                onClick = onOpenSelfCheck,
            ) { RowChevron() }
        }
    }
}

/** Current wording for the appearance (used by the settings page and this row). */
fun appearanceLabel(mode: ThemeMode): String = tr(
    when (mode) {
        ThemeMode.System -> R.string.appearance_system
        ThemeMode.Light -> R.string.appearance_light
        ThemeMode.Dark -> R.string.appearance_dark
    },
)

/**
 * Background image adjustments: scrim strength, image position, blur, whether the accent color follows the image, and switching back to a solid color.
 * The image is the page background itself, so dragging shows the effect right away; settings are written only when the value changes (integer percentages, position in 5% steps), not every frame.
 */
@Composable
internal fun SkinControls(skin: SkinSpec, onChange: (SkinSpec) -> Unit, onClear: () -> Unit) {
    val scrimLabel = stringResource(R.string.skin_scrim)
    ListRow(null, scrimLabel) { RowValue("${skin.scrim}%") }
    Slider(
        value = skin.scrim.toFloat(),
        onValueChange = { v -> v.roundToInt().let { if (it != skin.scrim) onChange(skin.copy(scrim = it)) } },
        valueRange = SkinSpec.MIN_SCRIM.toFloat()..SkinSpec.MAX_SCRIM.toFloat(),
        modifier = Modifier.padding(horizontal = 16.dp).semantics { contentDescription = scrimLabel }.testTag("skin-scrim"),
    )
    val positionLabel = stringResource(R.string.skin_position)
    ListRow(null, positionLabel)
    Slider(
        value = skin.focus,
        onValueChange = { v -> ((v * 20).roundToInt() / 20f).let { if (it != skin.focus) onChange(skin.copy(focus = it)) } },
        valueRange = -1f..1f,
        modifier = Modifier.padding(horizontal = 16.dp).semantics { contentDescription = positionLabel }.testTag("skin-position"),
    )
    ListRow(null, stringResource(R.string.skin_blur))
    SegmentedControl(
        listOf(stringResource(R.string.skin_blur_off), stringResource(R.string.skin_blur_light), stringResource(R.string.skin_blur_strong)),
        selected = skin.blur.ordinal,
        onSelect = { onChange(skin.copy(blur = SkinBlur.entries[it])) },
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    )
    ListRow(
        null, stringResource(R.string.skin_accent_follow), note = stringResource(R.string.skin_accent_follow_sub),
        onClick = { onChange(skin.copy(accentFromImage = !skin.accentFromImage)) },
    ) {
        MikaSwitch(skin.accentFromImage, { onChange(skin.copy(accentFromImage = it)) }, Modifier.testTag("skin-accent-follow"))
    }
    ListRow(null, stringResource(R.string.skin_clear), onClick = onClear)
}

fun accentLabel(accent: ThemeAccent): String = tr(
    when (accent) {
        ThemeAccent.Blue -> R.string.accent_blue
        ThemeAccent.Indigo -> R.string.accent_indigo
        ThemeAccent.Purple -> R.string.accent_purple
        ThemeAccent.Pink -> R.string.accent_pink
        ThemeAccent.Teal -> R.string.accent_teal
        ThemeAccent.Graphite -> R.string.accent_graphite
    },
)
