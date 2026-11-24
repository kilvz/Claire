package com.wangzan101.mika.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaShapes

/** One entry in the action sheet; [icon] goes to the left of the text; when [destructive] is true the text uses the error red. */
class SheetAction(
    val label: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * An action sheet that rises from the bottom after tapping a row: one line at the top says what the actions apply to, then one row per item below.
 * Tapping any item runs it first and then dismisses the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(title: String?, actions: List<SheetAction>, onDismiss: () -> Unit) {
    val c = MikaTheme.colors
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
            actions.forEachIndexed { i, a ->
                ListRow(
                    a.icon, a.label, first = i == 0,
                    titleColor = if (a.destructive) c.bad else c.ink,
                    onClick = { a.onClick(); onDismiss() },
                )
            }
        }
    }
}
