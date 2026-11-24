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
import com.wangzan101.mika.ui.theme.WordmarkWeight

/** How long the jump target row stays highlighted (milliseconds). */
internal const val JUMP_HIGHLIGHT_MS = 1_800L

/** Position of the vertical rail (measured from the left); the step dots and the spinner center all sit on this line. */
internal val StepRail = 8.dp

/** Horizontal inset of the composer shell; the empty-state suggestion cards use it too to line up with the text field. */
internal val ComposerInset = 12.dp

internal val FileTileShape = MikaShapes.Tile
internal val EmptyLead = MikaType.Value
internal val BubbleText = MikaType.Body
/** Answer: body font size, justified (Chinese paragraphs then have even line ends, except for the last line). */
internal val AnswerText = MikaType.Body.copy(textAlign = TextAlign.Justify)
internal val NowText = MikaType.Value
internal val TurnNameText = MikaType.Label.copy(fontWeight = WordmarkWeight)
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
