package com.wangzan101.mika.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** The small button at the end of a row in the action sheet (e.g. audition): tapping it only does that, without selecting the row or closing the sheet. [description] is read by screen readers. */
class SheetTrailing(
    val icon: ImageVector,
    val description: String,
    val onClick: () -> Unit,
)

/**
 * One item in the action sheet; [icon] goes to the left of the text; when [destructive] is true the text uses the error red.
 * Items with [searchable] false (such as "Manual input", "Default") stay visible during search too.
 */
class SheetAction(
    val label: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val trailing: SheetTrailing? = null,
    val searchable: Boolean = true,
    val onClick: () -> Unit,
)

/** When there are this many items (and the caller provided searchHint) a search field appears at the top. */
const val SHEET_SEARCH_MIN = 12

/** Keeps items whose text contains [query] (case-insensitive); items not participating in search always stay. */
internal fun filterSheetActions(actions: List<SheetAction>, query: String): List<SheetAction> {
    val q = query.trim()
    if (q.isEmpty()) return actions
    return actions.filter { !it.searchable || it.label.contains(q, ignoreCase = true) }
}

/**
 * An action sheet that rises from the bottom after tapping a row: a top line explains what is being acted on, and each item is one row below.
 * Tapping any item executes it first, then closes the sheet. When there are too many items for one screen (e.g. an engine's hundreds of voices) the list scrolls inside the sheet and the title stays put.
 * When [searchHint] is given and there are at least [SHEET_SEARCH_MIN] items, an extra search field under the title filters by text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(title: String?, actions: List<SheetAction>, searchHint: String? = null, onDismiss: () -> Unit) {
    val c = MikaTheme.colors
    val searching = searchHint != null && actions.size >= SHEET_SEARCH_MIN
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(actions, query, searching) { if (searching) filterSheetActions(actions, query) else actions }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = MikaShapes.SheetTop,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            if (!title.isNullOrEmpty()) {
                Text(
                    title, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MikaType.Caption, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (searching) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SearchField(
                        query, { query = it }, searchHint!!,
                        modifier = Modifier.testTag("sheet-search"),
                        onClear = { query = "" }, clearTag = "sheet-search-clear",
                    )
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                if (searching && shown.none { it.searchable }) {
                    EmptyNote(stringResource(R.string.sheet_no_matches))
                }
                shown.forEachIndexed { i, a ->
                    ListRow(
                        a.icon, a.label, first = i == 0,
                        titleColor = if (a.destructive) c.bad else c.ink,
                        onClick = { a.onClick(); onDismiss() },
                    ) {
                        a.trailing?.let { t ->
                            // IconButton has its own 48dp touch area; the icon itself has no left or right, and under RTL it mirrors to the left along with the row
                            IconButton(onClick = t.onClick) {
                                Icon(t.icon, t.description, Modifier.size(20.dp), tint = c.accent)
                            }
                        }
                    }
                }
            }
        }
    }
}
