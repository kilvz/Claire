package com.wangzan101.mika.chat.ui


import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.foundation.layout.size

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaShapes

internal const val MENU_IN_MS = 200
internal const val MENU_OUT_MS = 140
internal val DrawerWidth = 360.dp

/** The card shrinks vertically first while the outline stays circular on screen, so the top and bottom corners are not squashed into ellipses. */
internal class DrawerCardShape(private val progress: Float) : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = with(density) { 32.dp.toPx() } * progress
        return Outline.Rounded(RoundRect(
            rect = Rect(Offset.Zero, size),
            cornerRadius = CornerRadius(radius, radius / (1f - 0.04f * progress)),
        ))
    }
}
internal val NavShape = MikaShapes.Chip
internal val SectionText = MikaType.Hint.copy(fontWeight = FontWeight.Medium)
// Titles use regular weight so a column does not look heavy; only the current conversation is bold
internal val ConversationTitle = MikaType.Body.copy(fontSize = 15.sp, letterSpacing = 0.1.sp)
internal val ConversationTitleCurrent = ConversationTitle.copy(fontWeight = FontWeight.Medium)
internal val ConversationTime = MikaType.Hint.copy(fontSize = 11.5.sp)
internal val ConversationTag = MikaType.Micro.copy(fontWeight = FontWeight.Medium)
