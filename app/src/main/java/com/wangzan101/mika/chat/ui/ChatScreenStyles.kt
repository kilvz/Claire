package com.wangzan101.mika.chat.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaType

/** How long the jump target row stays highlighted (milliseconds). */
internal const val JUMP_HIGHLIGHT_MS = 1_800L

/** Position of the vertical rail (measured from the left); the step dots and the spinner center all sit on this line. */
internal val StepRail = 8.dp

/** Horizontal inset of the composer shell; the empty-state suggestion cards use it too to line up with the text field. */
internal val ComposerInset = 12.dp

internal val FileTileShape = MikaShapes.Tile
internal val EmptyLead = MikaType.Value
/** The empty-chat greeting: one step lighter than a display title (semi-bold), with slightly tighter letter spacing. */
internal val EmptyGreeting = MikaType.LargeTitle.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, letterSpacing = (-0.3).sp)
internal val BubbleText = MikaType.Body.copy(fontSize = 15.sp)
/** Answer: body text size, justified (Chinese paragraphs get even edges except the last line). */
/** Claire's answer body: left-aligned (justifying stretches characters), line height about 1.55× (tight within a paragraph, separated by paragraph spacing), slightly loose letter spacing. */
internal val AnswerText = MikaType.Body.copy(fontSize = 15.sp, lineHeight = 23.5.sp, letterSpacing = 0.2.sp, textAlign = TextAlign.Start)
internal val NowText = MikaType.Value
internal val ClockText = MikaType.Hint.copy(fontFeatureSettings = "tnum")
internal val StepText = MikaType.Caption
internal val StepCurrentText = MikaType.Caption.copy(fontWeight = FontWeight.Medium)
/** The small text a steer collapses into: 14sp, a touch lighter than the body text. */
internal val SupplementText = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
/** Right-side pill for a steer while work is running: a size smaller than the user bubble, with the tail corner at the bottom right. */
internal val SteerPillShape = RoundedCornerShape(14.dp, 14.dp, 5.dp, 14.dp)
internal val NoteText = MikaType.Hint
internal val DoneText = MikaType.Caption
internal val NeedTitle = MikaType.Value.copy(fontWeight = FontWeight.SemiBold)
internal val CardBody = MikaType.Body
private val PillText = MikaType.Caption
private val ChipText = MikaType.Hint.copy(fontWeight = FontWeight.Medium)
internal val FileName = MikaType.Body.copy(fontWeight = FontWeight.Medium)
internal val FileMeta = MikaType.Hint

/**
 * Markdown layout for chat answers: dark grey body, pure black bold, paragraph spacing 14 (about half a line more than the line height), headings not enlarged, muted list bullets,
 * and quotes and inline code on a light background one step deeper than the answer bubble.
 */
internal fun chatMarkdownLook(c: com.wangzan101.mika.ui.theme.MikaColors): com.wangzan101.mika.ui.MarkdownLook {
    val deeper = if (c.isDark) androidx.compose.ui.graphics.Color(0x14FFFFFF) else androidx.compose.ui.graphics.Color(0xFFE9EBEF)
    return com.wangzan101.mika.ui.MarkdownLook(
        gap = 14,
        flatHeadings = true,
        bodyInk = if (c.isDark) androidx.compose.ui.graphics.Color(0xFFE2E2E6) else androidx.compose.ui.graphics.Color(0xFF2B2E34),
        strongInk = c.ink,
        markerInk = c.ink3,
        quoteFill = deeper,
        codeFill = deeper,
    )
}
