package com.wangzan101.mika.ui.components

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/**
 * Skeleton for a second-level page: page background + top bar (back in the top-start corner) + scrolling content below (16 on each side).
 * Pages whose content is very long or needs lazy loading don't use this; they place a LazyColumn under [TopBar] themselves.
 */
@Composable
fun SubPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    right: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    PageBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar(title, MikaIcons.Back, onBack, stringResource(R.string.back), right = right)
            Column(
                modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                content = content,
            )
        }
    }
}

/** A row of action buttons under a row (small pills), aligned with the row's text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RowActions(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/**
 * Search field: light fill background, 14 corner radius, 40 high, magnifier on the left. When [onSubmit] is given the keyboard shows "Go", and pressing it hands over.
 * When [onClear] is given, a clear button appears on the right whenever there is text ([clearTag] is its test tag).
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null,
    clearTag: String? = null,
) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().height(40.dp).keepsKeyboard().background(if (LocalPlainList.current) c.surface else c.fill, MikaShapes.Button).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(MikaIcons.Search, null, Modifier.size(18.dp), tint = c.ink3)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = MikaType.Body, color = c.ink3)
            BasicTextField(
                value, onValueChange, modifier.fillMaxWidth(), singleLine = true,
                textStyle = MikaType.Body.copy(color = c.ink, textDirection = androidx.compose.ui.text.style.TextDirection.Content), cursorBrush = SolidColor(c.accent),
                keyboardOptions = if (onSubmit != null) KeyboardOptions(imeAction = ImeAction.Go) else KeyboardOptions.Default,
                keyboardActions = KeyboardActions(onGo = { onSubmit?.invoke() }),
            )
        }
        if (onClear != null && value.isNotEmpty()) {
            // IconButton keeps the 48dp touch target even though the field itself is only 40dp tall.
            IconButton(onClick = onClear, modifier = if (clearTag != null) Modifier.testTag(clearTag) else Modifier) {
                Icon(MikaIcons.Close, stringResource(R.string.clear), Modifier.size(18.dp), tint = c.ink3)
            }
        }
    }
}

/** Empty state: one or two centered sentences. */
@Composable
fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
        style = MikaType.Body, color = MikaTheme.colors.ink3, textAlign = TextAlign.Center,
    )
}
