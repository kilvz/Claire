package com.wangzan101.mika.core.learn.memory

import java.text.Normalizer

/**
 * A canonical form for numbers, shared by memory validation and skill redaction: full-width forms become half-width,
 * runs of three or more consecutive Chinese numerals are converted digit by digit into Arabic numerals
 * (tens, hundreds and thousands are dropped: both sides use the same conversion, so they only need to agree when
 * compared), and spaces, hyphens, dots and commas between digits are removed
 * ("138 0013 8000", "1,234" and the same digits spelled out as Chinese numerals all become a single digit run).
 * Decimal digits from every script are first normalized to ASCII, so that digits on Arabic or Persian interfaces count
 * as the same value as Latin digits.
 */
internal object NumberText {
    private val CN_DIGIT = mapOf('零' to '0', '〇' to '0', '一' to '1', '二' to '2', '三' to '3', '四' to '4',
        '五' to '5', '六' to '6', '七' to '7', '八' to '8', '九' to '9')
    val CN_RUN = Regex("[零〇一二三四五六七八九十百千]{3,}")
    private val SEPARATED = Regex("\\d(?:[\\s\\-.,]*\\d)+")
    private const val MIN_SEPARATED = 3

    fun collapse(s: String): String {
        val t = CN_RUN.replace(asciiDigits(Normalizer.normalize(s, Normalizer.Form.NFKC))) { m ->
            m.value.mapNotNull { CN_DIGIT[it] }.joinToString("").ifEmpty { m.value }
        }
        return SEPARATED.replace(t) { m ->
            val digits = m.value.filter(Char::isDigit)
            if (digits.length >= MIN_SEPARATED) digits else m.value
        }
    }

    /** NFKC only handles full-width forms; other decimal digits (Nd) such as Arabic-Indic and Persian ones must also be reduced to ASCII, otherwise \d in [SEPARATED] does not recognize them. */
    private fun asciiDigits(s: String): String = buildString(s.length) {
        for (c in s) {
            append(if (Character.getType(c) == Character.DECIMAL_DIGIT_NUMBER.toInt()) '0' + Character.digit(c, 10) else c)
        }
    }
}
