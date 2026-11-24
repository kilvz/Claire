package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.screen.uistate.Pace
import com.wangzan101.mika.core.screen.uistate.TextField
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * The rhythm of typing into an input field (pure logic, it does not touch the field): a person types one character at a time, CJK text goes on screen word by word through the IME,
 * the pace varies between characters, and there is an occasional pause after punctuation and spaces. [plan] arranges a stretch of text into a series of ever-longer prefixes plus how long to wait before each step;
 * the last step is always the full text.
 *
 * - Each Latin letter, digit and punctuation mark waits for a random span picked from [Limits.latinMs]; each CJK character waits [Limits.cjkMs],
 *   with several consecutive ones ([Limits.cjkGroup]) going on screen together;
 * - After each step there is a chance ([Limits.pauseChance], doubled after punctuation and spaces) to pause for an extra [Limits.pauseMs];
 * - With a lot of text, several characters merge into one step, up to [Limits.maxSteps] steps; when the total duration exceeds [Limits.maxTotalMs], every step is shortened proportionally, so even long messages don't take minutes to type.
 * - A character is never split: surrogate pairs, combining marks, variation selectors and zero-width-joiner emoji sequences all count as one with the preceding character.
 *
 * [Instant] writes the full text in one go without waiting (used for "fast", tests, and callers that don't need pacing).
 */
class TypingPacing(private val random: Random?, val limits: Limits = Limits()) {
    /** Durations are in milliseconds. */
    data class Limits(
        val latinMs: LongRange = 60L..170L,
        val cjkMs: LongRange = 160L..380L,
        val cjkGroup: IntRange = 1..3,
        val pauseChance: Double = 0.05,
        val pauseMs: LongRange = 300L..900L,
        val maxSteps: Int = 40,
        val maxTotalMs: Long = 6_000L,
    ) {
        companion object {
            /** Apps with limited browsing speed (and, in "human-like" mode, all apps): types slower, pauses more, and long messages take at most 12 seconds. */
            val CAREFUL = Limits(
                latinMs = 90L..230L, cjkMs = 220L..520L, pauseChance = 0.1, pauseMs = 500L..1_400L, maxSteps = 60, maxTotalMs = 12_000L,
            )
        }
    }

    /** One step: after waiting [delayMs], the input field holds the first [end] UTF-16 units of the full text. */
    data class Step(val end: Int, val delayMs: Long)

    /** Paced (not [Instant]). */
    val paced: Boolean get() = random != null

    /** Arranges [text] into steps; with [Instant] or an empty string there is only one step (the full text, no wait). */
    fun plan(text: String): List<Step> {
        val r = random ?: return listOf(Step(text.length, 0))
        val units = units(text)
        if (units.size <= 1) return listOf(Step(text.length, Pace.pick(r, limits.latinMs)))
        val per = ceil(units.size.toDouble() / limits.maxSteps).toInt().coerceAtLeast(1)
        val steps = mutableListOf<Step>()
        var i = 0
        while (i < units.size) {
            // CJK text goes on screen word by word through the IME (several consecutive characters, never crossing into another script); with a lot of text, at least per characters per step
            val group = if (isCjk(text, units[i])) {
                val want = Pace.pick(r, limits.cjkGroup)
                var k = 1
                while (k < want && i + k < units.size && isCjk(text, units[i + k])) k++
                k
            } else 1
            val upto = minOf(i + maxOf(group, per), units.size)
            var wait = 0L
            for (j in i until upto) wait += Pace.pick(r, if (isCjk(text, units[j])) limits.cjkMs else limits.latinMs)
            val end = if (upto == units.size) text.length else units[upto]
            val last = text.codePointBefore(end)
            val boundary = Character.isWhitespace(last) || isPunctuation(last)
            if (r.nextDouble() < limits.pauseChance * (if (boundary) 2 else 1)) wait += Pace.pick(r, limits.pauseMs)
            steps += Step(end, wait)
            i = upto
        }
        val total = steps.sumOf { it.delayMs }
        if (total <= limits.maxTotalMs) return steps
        val scale = limits.maxTotalMs.toDouble() / total
        return steps.map { it.copy(delayMs = (it.delayMs * scale).roundToLong()) }
    }

    /**
     * The pacing to use for this input field: password fields ([secret]) are not split (the original text cannot be read back, so a partial prefix cannot be verified); web fields that only receive input via paste ([web])
     * are not split either (the app does not receive the written characters). Those two still write in one go ([Instant]); everything else uses this pacing.
     */
    fun forField(web: Boolean, secret: Boolean): TypingPacing = if (web || secret) Instant else this

    /**
     * Writes the prefixes of [text] into [field] step by step according to [plan], leaving the full-text step to the caller (which still writes it all at once and reads it back to verify), and returns after the last step's pause has elapsed.
     * After writing the first step it reads back to verify: if the field does not accept partial text (read-back mismatches, or cannot be read) or the write fails, it stops immediately and returns false, and the caller writes the full text in one go as usual.
     * Returns true after all prefixes have been written; when nothing is split ([Instant], a single character) it writes nothing and returns false.
     */
    suspend fun leadIn(field: TextField, text: String, sleep: suspend (Long) -> Unit = { delay(it) }): Boolean {
        val steps = plan(text)
        if (steps.size < 2) return false
        for ((k, s) in steps.dropLast(1).withIndex()) {
            sleep(s.delayMs)
            val prefix = text.substring(0, s.end)
            if (!field.write(prefix)) return false
            if (k == 0) {
                val back = field.read() ?: return false
                if (back.filterNot { it.isWhitespace() } != prefix.filterNot { it.isWhitespace() }) return false
            }
        }
        sleep(steps.last().delayMs)
        return true
    }

    companion object {
        /** Writes the full text in one go. */
        val Instant = TypingPacing(null)

        /** The human-like typing rhythm; [random] can be replaced with a fixed seed (tests). */
        fun human(random: Random = Random.Default, limits: Limits = Limits()) = TypingPacing(random, limits)

        /** The start index (UTF-16 index) of each character in [text]: surrogate pairs, combining marks, variation selectors and zero-width-joiner sequences count as one with the preceding character. */
        internal fun units(text: String): List<Int> {
            val out = mutableListOf<Int>()
            var i = 0
            var joinNext = false
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val attach = out.isNotEmpty() && (joinNext || attaches(cp))
                if (!attach) out += i
                joinNext = cp == ZWJ
                i += Character.charCount(cp)
            }
            return out
        }

        private const val ZWJ = 0x200D

        private fun attaches(cp: Int): Boolean = when (Character.getType(cp)) {
            Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt() -> true
            else -> cp == ZWJ || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF
        }

        private fun isCjk(text: String, at: Int): Boolean = when (Character.UnicodeScript.of(text.codePointAt(at))) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
            else -> false
        }

        private fun isPunctuation(cp: Int): Boolean = when (Character.getType(cp)) {
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(), Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }
}
