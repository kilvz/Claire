package com.wangzan101.mika.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The wordmark "Claire" uses this weight everywhere: one notch lighter than a title, so the top bar, drawer and answer header all read as the same name. */
val WordmarkWeight = FontWeight.Medium

/** Font sizes and weights. Only size, weight and line height are defined here; the color is supplied by the place that uses it. */
object MikaType {
    /** Large title: empty states, welcome page. */
    val LargeTitle = TextStyle(fontSize = 28.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold)
    /** Page title: top bar. */
    val Title = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    /** Body text and row titles. */
    val Body = TextStyle(fontSize = 15.5.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
    /** Body text that needs emphasis, such as card titles. */
    val BodyStrong = TextStyle(fontSize = 15.5.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    /** Explanatory text. */
    val Caption = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
    /** Small group headings. */
    val Label = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    /** Hints and footers. */
    val Hint = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal)
    /** The value at the end of a row. */
    val Value = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
    /** Badges, tags, and very small counters. */
    val Micro = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Normal)
    /** Oversized numbers: large presentation numbers such as totals and verification codes. */
    val Display = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
}

/** Spacing. Pages keep [Page] on the left and right. */
object MikaSpace {
    val S4 = 4.dp
    val S8 = 8.dp
    val S12 = 12.dp
    val S16 = 16.dp
    val S20 = 20.dp
    val S24 = 24.dp
    val Page = 16.dp
}

/** Sizes of the various elements. */
object MikaSize {
    val TopBar = 56.dp
    val IconButton = 40.dp
    val RowMin = 52.dp
    val Button = 52.dp
    val Composer = 52.dp
    val Icon = 22.dp
    /** The icon tile on the left of a list row; the glyph inside it is [TileIcon]. */
    val Tile = 30.dp
    val TileIcon = 18.dp
    /** Small icons such as the chevron at the end of a row. */
    val SmallIcon = 16.dp
    const val IconStroke = 1.7f
}

/** Corner radii. */
object MikaRadius {
    /** Extra small: badges, dots. */
    val Micro = 4.dp
    val BubbleTail = 6.dp
    val Tile = 9.dp
    /** Small cards, images, chips. */
    val Chip = 12.dp
    val Button = 14.dp
    val Card = 16.dp
    val Bubble = 20.dp
    /** Large cards, dialogs, bottom sheets. */
    val Sheet = 24.dp
}

/** Common shapes; top-level constants, so recomposition doesn't create new ones. */
object MikaShapes {
    val Micro = RoundedCornerShape(MikaRadius.Micro)
    val Card = RoundedCornerShape(MikaRadius.Card)
    val Chip = RoundedCornerShape(MikaRadius.Chip)
    val Sheet = RoundedCornerShape(MikaRadius.Sheet)
    /** Bottom sheet: the top two corners are rounded and the bottom ones are square. */
    val SheetTop = RoundedCornerShape(topStart = MikaRadius.Bubble, topEnd = MikaRadius.Bubble)
    /** User message bubble: the bottom-right corner is the tail. */
    val UserBubble = RoundedCornerShape(MikaRadius.Bubble, MikaRadius.Bubble, MikaRadius.BubbleTail, MikaRadius.Bubble)
    /** Claire's answer: the bottom-left corner is the tail, mirroring the user bubble. */
    val AnswerBubble = RoundedCornerShape(MikaRadius.Bubble, MikaRadius.Bubble, MikaRadius.Bubble, MikaRadius.BubbleTail)
    val Button = RoundedCornerShape(MikaRadius.Button)
    val Tile = RoundedCornerShape(MikaRadius.Tile)
    /** Composer shell: a fixed radius that doesn't change when the input is multiline (unlike [Pill], which gets rounder with height). */
    val Composer = RoundedCornerShape(MikaRadius.Sheet)
    val Pill = CircleShape
}
