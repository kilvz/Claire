package com.wangzan101.mika.core.frontdesk

/** Maximum characters in one front-desk progress line, clipped beyond that; it should be understood at a glance or by ear, and details do not belong there. */
const val DESK_LINE_MAX_CHARS = 40

/** Limit on task result text handed to the front desk: protocol frames cap tool results sent back to the realtime model, leaving room for the outer wrapper. */
const val DESK_RESULT_MAX_CHARS = 1_800

/** Coordinate forms: the [x,y] and (x,y) common in screen trees and tap positions, plus their full-width variants; bounds are two adjacent pairs, stripped pair by pair. */
private val COORDINATE_PATTERN = Regex("""[\[\(（【]\s*\d+\s*[,，、]\s*\d+\s*[\]\)）】]""")

/**
 * Quoted passages: details such as calendar titles, contact names and message content are usually quoted, so the whole passage is stripped. 【】 is also treated as a quote (when not numeric).
 * The ASCII single quote is also the English apostrophe (don't, it's): it counts as a quote only when no letter or digit is adjacent on either side; inside a word it is left alone.
 */
private val QUOTED_PATTERN = Regex(
    """「[^」]*」|『[^』]*』|《[^》]*》|【[^】]*】|"[^"]*"|“[^”]*”|‘[^’]*’|(?<![\p{L}\p{N}])'[^']*'(?![\p{L}\p{N}])""",
)

private val WHITESPACE_RUN = Regex("""\s+""")

/** Strip coordinates and quoted details such as titles, then collapse the whitespace left behind. */
fun stripDetails(text: String): String =
    WHITESPACE_RUN.replace(COORDINATE_PATTERN.replace(QUOTED_PATTERN.replace(text, ""), ""), " ").trim()

/** Turn one progress description into a front-desk line: strip details and clip to [DESK_LINE_MAX_CHARS]; null when nothing is left. */
fun deskLine(narration: String?): String? =
    narration?.let { stripDetails(it).take(DESK_LINE_MAX_CHARS) }?.ifBlank { null }
