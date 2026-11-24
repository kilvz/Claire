package com.wangzan101.mika.selfcheck

import java.util.Locale

/**
 * The comparison used when the self-check recognises text on the self-check page. The text screen reading supplies is not necessarily character-for-character what the page shows: by default a button is rendered all in capitals by the system theme,
 * so screen reading reports it in capitals; whitespace may also be off by one, or replaced with non-breaking spaces. So both sides are normalised before comparing:
 * leading and trailing whitespace is trimmed, inner whitespace is collapsed into a single ordinary space, and case is ignored.
 */
internal object SelfCheckText {
    private val SPACES = Regex("[\\s\\p{Z}]+")

    /**
     * Normalises any decimal digit variant into ASCII digits: Arabic-Indic digits (٠-٩), extended Arabic-Indic digits (۰-۹), full-width digits and so on.
     * In languages such as Arabic the text supplied by screen reading reports digits in the local form, while the page templates and other code assume ASCII digits, so without normalising they cannot be recognised.
     */
    fun digitsToAscii(s: String): String = buildString(s.length) {
        for (c in s) {
            val d = Character.digit(c, 10)
            append(if (d >= 0) ('0' + d) else c)
        }
    }

    fun normalize(s: String): String = digitsToAscii(s).replace(SPACES, " ").trim().uppercase(Locale.ROOT).lowercase(Locale.ROOT)

    fun same(a: String, b: String): Boolean = normalize(a) == normalize(b)

    fun contains(text: String, part: String): Boolean = normalize(text).contains(normalize(part))

    /**
     * Text containing a number ("tapped %d times", "timer %d", "row %d"). [template] is the whole sentence with the number replaced by [sentinel];
     * when matching, the two segments before and after the number are compared as described above, and only digits may appear in between.
     */
    class Numbered(template: String, sentinel: String) {
        private val parts = normalize(template).split(normalize(sentinel))

        fun matches(s: String): Boolean {
            if (parts.size != 2) return false
            val (head, tail) = parts
            val n = normalize(s)
            if (n.length <= head.length + tail.length || !n.startsWith(head) || !n.endsWith(tail)) return false
            return n.substring(head.length, n.length - tail.length).all(Char::isDigit)
        }
    }
}
