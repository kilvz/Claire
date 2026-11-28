package com.wangzan101.mika.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.MarkdownText
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes

/**
 * The app's unified dialog: it keeps the caller's content, actions and test tags, and only unifies the container, body hierarchy and action area.
 * Actions are still supplied by the caller; this only gives them a consistent light background, corner radius and arrangement, without pulling confirm logic into the component.
 */
@Composable
fun MikaDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    confirmButton: @Composable () -> Unit = {},
    dismissButton: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    val c = MikaTheme.colors
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        title = title?.let { content ->
            {
                CompositionLocalProvider(
                    LocalContentColor provides c.ink,
                    LocalTextStyle provides MikaType.Title,
                ) { content() }
            }
        },
        text = text?.let { content ->
            {
                CompositionLocalProvider(
                    LocalContentColor provides c.ink2,
                    LocalTextStyle provides MikaType.Body,
                ) { content() }
            }
        },
        confirmButton = {
            if (actions != null) {
                actions()
            } else {
                // 操作区不用强调色:两个按钮同一浅灰底,确认用正文色加粗,取消用次要色;调用方自己给的颜色(如删除的红)照旧
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (dismissButton != null) {
                        DialogAction(Modifier.weight(1f), c.fill, c.ink2, androidx.compose.ui.text.font.FontWeight.Normal) { dismissButton() }
                    }
                    DialogAction(Modifier.weight(1f), c.fill, c.ink, androidx.compose.ui.text.font.FontWeight.SemiBold) { confirmButton() }
                }
            }
        },
        shape = MikaShapes.Sheet,
        containerColor = c.surface,
        titleContentColor = c.ink,
        textContentColor = c.ink2,
        tonalElevation = 0.dp,
        properties = properties,
    )
}

@Composable
private fun DialogAction(
    modifier: Modifier, background: Color, ink: Color,
    weight: androidx.compose.ui.text.font.FontWeight, content: @Composable () -> Unit,
) {
    val shape = MikaShapes.Button
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    // The whole block is the button: pass the block's size as the minimum size to the button inside so it fills the block, and tapping anywhere counts
    Box(
        modifier.heightIn(min = 44.dp).background(background, shape).clip(shape),
        contentAlignment = Alignment.Center,
        propagateMinConstraints = true,
    ) {
        // TextButton uses primary as its text color; here it is replaced with a neutral color
        androidx.compose.material3.MaterialTheme(colorScheme = scheme.copy(primary = ink)) {
            CompositionLocalProvider(LocalTextStyle provides MikaType.Body.copy(fontWeight = weight)) { content() }
        }
    }
}

/** The body and answer of a notice dialog use the same Markdown layout, so raw markup is never handed straight to the user. */
@Composable
fun MikaNoticeDialog(
    message: String,
    onDismiss: () -> Unit,
    title: String? = null,
    confirmText: String = stringResource(R.string.ok_got_it),
) {
    MikaDialog(
        onDismissRequest = onDismiss,
        title = title?.let { { Text(it) } },
        text = { MarkdownText(message, MikaType.Body.copy(color = MikaTheme.colors.ink2)) },
        confirmButton = { TextButton(onDismiss) { Text(confirmText) } },
    )
}
