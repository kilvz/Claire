package com.wangzan101.mika.settings.ui

import com.wangzan101.mika.R
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.ThemeAccent
import com.wangzan101.mika.settings.ThemeMode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import com.wangzan101.mika.ui.theme.accentTone
import com.wangzan101.mika.ui.theme.SkinImage
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.wangzan101.mika.i18n.AppLanguage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.Icon
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** Settings › Language: follow system, Simplified Chinese, Traditional Chinese, English; switching applies immediately and the UI is rebuilt accordingly. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LanguageScreen(language: AppLanguage, onLanguage: (AppLanguage) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val flags = remember { FeatureFlags.get(context) }
    // Follow the settings: if the appearance is changed elsewhere, this page stays in sync
    val themeMode by remember(flags) { flags.themeModeChanges() }.collectAsState(flags.themeMode())
    val themeAccent by remember(flags) { flags.themeAccentChanges() }.collectAsState(flags.themeAccent())
    val skin by remember(flags) { flags.skinChanges() }.collectAsState(flags.skin())
    val scope = rememberCoroutineScope()
    val pickSkin = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            if (!SkinImage.import(context, uri)) Toast.makeText(context, R.string.skin_failed, Toast.LENGTH_SHORT).show()
        }
    }
    val focusManager = LocalFocusManager.current
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    PageScaffold(stringResource(R.string.appearance_title), onBack, modifier = Modifier.imePadding()) {
        // Language and appearance share one page: both define "what the UI looks like"
        GroupLabel(stringResource(R.string.appearance_group))
        GroupCard {
            ThemeMode.entries.forEachIndexed { idx, m ->
                RadioRow(
                    appearanceLabel(m), selected = m == themeMode, first = idx == 0,
                    subtitle = if (m == ThemeMode.System) stringResource(R.string.appearance_system_detail) else null,
                    onClick = {
                        // Colors change immediately with the setting, without rebuilding, so the page and scroll position are preserved
                        if (m != themeMode) flags.setThemeMode(m)
                    },
                )
            }
        }
        GroupLabel(stringResource(R.string.accent_group))
        GroupCard {
            val dark = MikaTheme.colors.isDark
            // When the background image's color serves as the accent, the selection here is unused for now and the row is greyed out
            val followingImage = skin.accentHue != null
            ThemeAccent.entries.forEachIndexed { idx, a ->
                RadioRow(
                    accentLabel(a), selected = a == themeAccent, first = idx == 0, enabled = !followingImage,
                    subtitle = if (a == ThemeAccent.Blue) stringResource(R.string.accent_default) else null,
                    onClick = { if (a != themeAccent) flags.setThemeAccent(a) },
                ) {
                    // The swatch is drawn with the current light/dark tone, matching what you see after selecting it
                    Box(Modifier.size(18.dp).background(accentTone(a, dark).first, CircleShape))
                }
            }
        }
        GroupLabel(stringResource(R.string.skin_group))
        GroupCard {
            ListRow(
                null, stringResource(if (skin.active) R.string.skin_change else R.string.skin_pick), first = true,
                onClick = { pickSkin.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )
            if (skin.active) SkinControls(skin, flags::setSkin, onClear = { SkinImage.clear(context) })
        }
        GroupLabel(stringResource(R.string.language_title))
        var langQuery by remember { mutableStateOf("") }
        var langSearchFocused by remember { mutableStateOf(false) }
        val c = MikaTheme.colors
        val filteredLanguages = remember(langQuery) { filterLanguages(langQuery) }

        LaunchedEffect(langSearchFocused, langQuery) {
            if (langSearchFocused || langQuery.isNotEmpty()) {
                bringIntoViewRequester.bringIntoView()
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .height(36.dp)
                .clip(MikaShapes.Pill)
                .background(c.fill)
                .then(if (langSearchFocused) Modifier.border(1.dp, c.accent, MikaShapes.Pill) else Modifier)
                .padding(horizontal = 10.dp)
                .bringIntoViewRequester(bringIntoViewRequester),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(MikaIcons.Search, null, Modifier.size(16.dp), tint = c.ink3)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (langQuery.isEmpty()) {
                    Text(stringResource(R.string.language_search_hint), style = MikaType.Caption, color = c.ink3, maxLines = 1)
                }
                BasicTextField(
                    value = langQuery,
                    onValueChange = { langQuery = it },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { langSearchFocused = it.isFocused },
                    singleLine = true,
                    textStyle = MikaType.Caption.copy(color = c.ink),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                )
            }
            if (langQuery.isNotEmpty()) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape).clickable {
                        langQuery = ""
                        focusManager.clearFocus()
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MikaIcons.Close, null, Modifier.size(12.dp), tint = c.ink3)
                }
            }
        }
        GroupCard(Modifier.animateContentSize()) {
            if (filteredLanguages.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.file_library_empty),
                        style = MikaType.Caption,
                        color = c.ink3,
                    )
                }
            } else {
                filteredLanguages.forEachIndexed { i, l ->
                    RadioRow(
                        languageLabel(l), selected = l == language, onClick = {
                            if (l != language) onLanguage(l)
                            focusManager.clearFocus()
                        },
                        first = i == 0,
                    )
                }
            }
        }
    }
}

/** Language names: each language is always written with its own native name and spelling (e.g. Español…); "Follow system" is written in the current language. */
@Composable
fun languageLabel(l: AppLanguage): String = stringResource(
    when (l) {
        AppLanguage.System -> R.string.language_system
        AppLanguage.SimplifiedChinese -> R.string.language_simplified_chinese
        AppLanguage.TraditionalChinese -> R.string.language_traditional_chinese
        AppLanguage.Arabic -> R.string.language_arabic
        AppLanguage.Persian -> R.string.language_persian
        AppLanguage.English -> R.string.language_english
        AppLanguage.Japanese -> R.string.language_japanese
        AppLanguage.Korean -> R.string.language_korean
        AppLanguage.Spanish -> R.string.language_spanish
        AppLanguage.Portuguese -> R.string.language_portuguese
        AppLanguage.Russian -> R.string.language_russian
        AppLanguage.Indonesian -> R.string.language_indonesian
        AppLanguage.Turkish -> R.string.language_turkish
    },
)

// ---------- Privacy and data ----------
