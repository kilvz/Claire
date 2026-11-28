package com.wangzan101.mika.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.Hairline
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.SearchField
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** The search box at the top of the settings home. */
@Composable
internal fun SettingsSearchBar(query: String, onQuery: (String) -> Unit) {
    SearchField(
        query, onQuery, stringResource(R.string.settings_search_hint),
        Modifier.testTag("settings-search-input"),
        onClear = { onQuery("") }, clearTag = "settings-search-clear",
    )
}

/**
 * Search results: [available] are the entries visible in settings right now, matched against [query] after resolving text in the current UI language.
 * Each row shows the title and where it is (group › parent page), with matched characters highlighted; tapping a row goes to [onOpen].
 */
@Composable
internal fun SettingsSearchResults(
    query: String,
    available: List<SettingsSearchEntry>,
    onOpen: (SettingsDestination) -> Unit,
) {
    val resources = LocalContext.current.resources
    val configuration = LocalConfiguration.current
    val items = remember(available, resources, configuration) { SettingsSearchIndex.resolve(available) { resources.getString(it) } }
    val hits = remember(items, query) { SettingsSearch.search(items, query) }
    if (hits.isEmpty()) {
        EmptyNote(stringResource(R.string.settings_search_empty), Modifier.testTag("settings-search-empty"))
        return
    }
    val accent = MikaTheme.colors.accent
    GroupCard(Modifier.testTag("settings-search-results")) {
        hits.forEachIndexed { i, hit ->
            if (i > 0) Hairline(16.dp)
            SettingsSearchResultRow(hit, accent) { onOpen(hit.item.entry.destination) }
        }
    }
}

@Composable
private fun SettingsSearchResultRow(hit: SettingsSearchHit, accent: Color, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = MikaSize.RowMin).padding(horizontal = 16.dp)
            .testTag("settings-search-${hit.item.entry.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f).padding(vertical = 11.dp)) {
            Text(highlighted(hit.item.title, hit.titleMatches, accent), style = MikaType.Body, color = c.ink)
            if (hit.item.path.isNotEmpty()) {
                Text(highlighted(hit.item.path, hit.pathMatches, accent), Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3)
            }
        }
        RowChevron()
    }
}

/** Style the [ranges] segments in the accent color and bold. */
internal fun highlighted(text: String, ranges: List<IntRange>, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val style = SpanStyle(color = color, fontWeight = FontWeight.SemiBold)
    for (r in ranges) addStyle(style, r.first, r.last + 1)
}
