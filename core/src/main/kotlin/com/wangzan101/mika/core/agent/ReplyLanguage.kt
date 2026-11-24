package com.wangzan101.mika.core.agent

import java.lang.Character.UnicodeScript

/**
 * Whether the final answer's language follows the user: counted by the script each letter belongs to (Unicode
 * scripts), with no model and no word list involved. Pure functions.
 *
 * Scripts are grouped into families: Han and kana are counted together, and with enough kana (at least [KANA_SHARE]
 * of the two) they count as Japanese, otherwise as Chinese; Hangul is Korean; Latin, Cyrillic, Arabic, and other
 * scripts each form their own family. Digits, punctuation, whitespace, and markdown symbols are not letters.
 *
 * Cross-script counting must be fair: Chinese and Japanese do not segment on spaces, so one Han character (kana) is
 * roughly one word and counting is by characters; other space-segmented scripts count by words (letters of the same
 * script joined together count as one word), not by letters, otherwise one English word would rival several Han
 * characters. Words in Latin or Cyrillic with no lowercase letter (GB, OS, model abbreviations) are mostly units and
 * abbreviations, and do not count.
 *
 * Parts of the answer that do not count: content inside the various quotes (「」『』“”"" 《》), inside parentheses (()
 * and ()), code in backticks, URLs, and angle-bracket tags. These are mostly copied or annotated original text that
 * should be preserved anyway. In addition, when a line (or a cell split by | or 、) holds only one run of characters
 * from the same family and no more than [NAME_UNITS] units, it is treated as a name or label (an App name in a list, a
 * table cell) and does not count either: an answer listing only foreign names is not judged as having switched
 * languages.
 *
 * Judging a language switch requires all of: the user's sentence has at least [MIN_USER_UNITS] units, the remaining
 * answer body has at least [MIN_ANSWER_UNITS] units, the largest family in the answer covers at least
 * [MISMATCH_SHARE], and it is not the largest family in the user's sentence.
 */
internal object ReplyLanguage {
    const val MIN_USER_UNITS = 3
    const val MIN_ANSWER_UNITS = 20
    const val MISMATCH_SHARE = 0.6
    const val NAME_UNITS = 6
    const val KANA_SHARE = 0.1

    /** A script family; [display] is the name written for the model. */
    data class Family(val display: String)

    val CHINESE = Family("Chinese")
    val JAPANESE = Family("Japanese")
    val KOREAN = Family("Korean")

    /** The family of the answer [answer] and the family of the user's sentence [user]. */
    data class Mismatch(val answer: Family, val user: Family)

    /** Returns both families when the answer body is clearly not in the user's sentence's language; otherwise (or too few characters to judge) null. */
    fun mismatch(user: String, answer: String): Mismatch? {
        val u = units(user, skipNames = false)
        val a = units(stripQuoted(answer), skipNames = true)
        val uTotal = u.values.sum()
        val aTotal = a.values.sum()
        if (uTotal < MIN_USER_UNITS || aTotal < MIN_ANSWER_UNITS) return null
        val userFamily = u.maxBy { it.value }.key
        val (answerFamily, count) = a.maxBy { it.value }
        if (count < aTotal * MISMATCH_SHARE || answerFamily == userFamily) return null
        return Mismatch(answerFamily, userFamily)
    }

    /** Unit counts per family in [text] (see the class docs); with [skipNames], lines or cells holding only one short run are not counted. */
    fun units(text: String, skipNames: Boolean): Map<Family, Int> {
        val cells = text.split(CELL).map { cell -> runsOf(cell).filter { it.units > 0 } }
        val counted = (if (skipNames) cells.filterNot { it.size == 1 && it[0].units <= NAME_UNITS } else cells).flatten()
        val han = counted.filter { it.key == HAN }.sumOf { it.units }
        val kana = counted.filter { it.key == KANA }.sumOf { it.units }
        val cjk = if (kana > 0 && kana >= (han + kana) * KANA_SHARE) JAPANESE else CHINESE
        val out = LinkedHashMap<Family, Int>()
        for (r in counted) {
            val family = when (r.key) {
                HAN, KANA -> cjk
                UnicodeScript.HANGUL.name -> KOREAN
                else -> Family(r.key.lowercase().replaceFirstChar(Char::uppercaseChar) + " script")
            }
            out[family] = (out[family] ?: 0) + r.units
        }
        return out
    }

    /** Removes from the answer the content inside quotes, parentheses, and backticks, plus URLs and tags (parentheses may nest, both inner and outer are removed). */
    fun stripQuoted(text: String): String {
        var s = FENCE.replace(text, " ")
        for (r in listOf(INLINE_CODE, URL, TAG, QUOTES)) s = r.replace(s, " ")
        while (true) {
            val next = PARENS.replace(s, " ")
            if (next == s) return s
            s = next
        }
    }

    /** A run of consecutive characters in the same script: [key] is the script (Han and kana are kept separate before merging), [units] is the unit count. */
    private data class Run(val key: String, var units: Int)

    /**
     * Splits a cell into runs: letters of the same script stay in one run when only spaces, hyphens, apostrophes,
     * combining marks (or letters belonging to no script) separate them. Each Han character and each kana counts as
     * one unit; other scripts count one unit per word, and Latin or Cyrillic words without any lowercase letter do not
     * count.
     */
    private fun runsOf(cell: String): List<Run> {
        val out = mutableListOf<Run>()
        var current: Run? = null
        val word = StringBuilder()
        fun endWord() {
            val r = current
            if (word.isNotEmpty() && r != null && countsAsWord(word)) r.units++
            word.clear()
        }
        var i = 0
        while (i < cell.length) {
            val cp = cell.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            if (type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()) continue
            // Letters belonging to no particular script (the long-vowel mark 「ー」 and the like) follow the characters around them and do not count by themselves
            if (Character.isLetter(cp) && UnicodeScript.of(cp).let { it == UnicodeScript.COMMON || it == UnicodeScript.INHERITED }) continue
            if (Character.isLetter(cp)) {
                val key = keyOf(cp)
                if (current?.key != key) { endWord(); current = Run(key, 0).also { out += it } }
                if (key == HAN || key == KANA) current!!.units++ else word.appendCodePoint(cp)
            } else if (cp == ' '.code || cp == '-'.code || cp == '\''.code || cp == '’'.code) {
                endWord()
            } else {
                endWord()
                current = null
            }
        }
        endWord()
        return out
    }

    private fun countsAsWord(word: CharSequence): Boolean {
        val script = UnicodeScript.of(Character.codePointAt(word, 0))
        if (script != UnicodeScript.LATIN && script != UnicodeScript.CYRILLIC) return true
        return word.codePoints().anyMatch(Character::isLowerCase)
    }

    private fun keyOf(cp: Int): String = when (val s = UnicodeScript.of(cp)) {
        UnicodeScript.HAN -> HAN
        UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA -> KANA
        else -> s.name
    }

    private const val HAN = "HAN"
    private const val KANA = "KANA"

    /** Each line, table cell, and enumeration item separated by the ideographic comma is one cell. */
    private val CELL = Regex("[\\n|、]")
    private val FENCE = Regex("```[\\s\\S]*?```")
    private val INLINE_CODE = Regex("`[^`\\n]*`")
    private val URL = Regex("(?i)\\b(?:https?://|www\\.)\\S+")
    private val TAG = Regex("<[^<>\\n]*>")
    private val QUOTES = Regex("「[^」\\n]*」|『[^』\\n]*』|“[^”\\n]*”|\"[^\"\\n]*\"|《[^》\\n]*》")
    /** Matches only the innermost pair of parentheses, removing them repeatedly until none remain; half-width and full-width parentheses may pair with each other. */
    private val PARENS = Regex("[(（][^()（）\\n]*[)）]")
}
