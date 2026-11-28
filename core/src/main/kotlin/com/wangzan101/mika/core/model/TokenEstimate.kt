package com.wangzan101.mika.core.model

import kotlin.math.ceil

/**
 * Rough estimate without a tokenizer: non-Han characters / 4 + Han characters × 0.75; for images, width×height/[PIXELS_PER_TOKEN] when the size is known, otherwise 1000.
 * Used only to decide whether to compact, and to truncate by token within the context budget.
 * CJK punctuation, full-width characters, kana, and hangul are counted at 0.75 like Han characters.
 */
object TokenEstimate {
    private const val IMAGE_TOKENS = 1000
    /** The convention used by common providers (about one token per 28×28 pixels; providers differ slightly). */
    const val PIXELS_PER_TOKEN = 750.0

    /** Approximate tokens for a [width]×[height] image; 1000 when the size is unknown. */
    fun image(width: Int, height: Int): Int = if (width > 0 && height > 0) ceil(width.toLong() * height / PIXELS_PER_TOKEN).toInt() else IMAGE_TOKENS

    fun of(messages: List<Message>): Int {
        var images = 0
        var text = 0.0
        for (m in messages) for (p in m.parts) when (p) {
            is Part.Image -> images += image(p.width, p.height)
            is Part.Text -> text += chars(p.text)
            is Part.Thinking -> text += chars(p.text)
            is Part.ToolCall -> text += chars(p.name) + chars(p.argsJson)
            is Part.ToolResult -> text += chars(p.content)
        }
        return images + ceil(text).toInt()
    }

    /**
     * Calibrate the rough estimate against actual usage: returns the new "actual / estimated" ratio. When [previous] is null, this run's ratio is used directly;
     * otherwise it is smoothed together with the previous one. The result is clamped to [MIN_SCALE]..[MAX_SCALE], so a single anomalous usage cannot skew the estimate too far.
     */
    fun calibrated(previous: Double?, estimated: Int, actual: Int): Double {
        if (estimated <= 0 || actual <= 0) return previous ?: 1.0
        val ratio = actual.toDouble() / estimated
        val next = if (previous == null) ratio else previous * (1 - SMOOTHING) + ratio * SMOOTHING
        return next.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    const val MIN_SCALE = 0.5
    const val MAX_SCALE = 3.0
    private const val SMOOTHING = 0.3

    /** Estimates a piece of text with the same convention, for fixed overhead not present in the messages (base prompt, tool definitions). */
    fun of(text: String): Int = ceil(chars(text)).toInt()

    /** Cuts to the longest prefix of at most [maxTokens] tokens, appending [hint] at the end; used only for the context budget. */
    fun clip(text: String, maxTokens: Int, hint: String = "…"): String {
        if (maxTokens <= 0 || text.isEmpty()) return ""
        if (of(text) <= maxTokens) return text
        val room = maxTokens - of(hint)
        if (room <= 0) return hint
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (of(text.substring(0, mid)) <= room) lo = mid else hi = mid - 1
        }
        // Do not split a surrogate pair in half
        if (lo in 1 until text.length && text[lo - 1].isHighSurrogate() && text[lo].isLowSurrogate()) lo--
        return text.substring(0, lo) + hint
    }

    private val WIDE_SCRIPTS = setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL,
    )
    private val WIDE_BLOCKS = setOf(
        Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION, Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS,
    )

    private fun wide(cp: Int) = Character.UnicodeScript.of(cp) in WIDE_SCRIPTS || Character.UnicodeBlock.of(cp) in WIDE_BLOCKS

    private fun chars(s: String): Double {
        var han = 0
        var other = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (wide(cp)) han++ else other++
            i += Character.charCount(cp)
        }
        return han * 0.75 + other / 4.0
    }
}
