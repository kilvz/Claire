package com.wangzan101.mika.core.learn.skills

import com.wangzan101.mika.core.learn.memory.NumberText

/**
 * When distilling a skill, this run's specific values are removed: quoted content in the goal and answer, digit strings of 2 or more, and amounts of money;
 * when drafting, screen text quoted by verification and the text typed this run are also treated as specific values. Chinese personal names are not detected — that relies on quotes and the user reviewing the result.
 * All text is first normalized (NFKC; Chinese numerals and separated digits are joined into one Arabic numeral string), and comparison and replacement use the same form.
 */
object SkillRedactor {
    const val PLACEHOLDER = "<参数>"

    private val QUOTED = listOf("「", "」", "『", "』", "“", "”", "\"", "\"", "‘", "’", "《", "》")
        .chunked(2).map { (open, close) -> Regex(Regex.escape(open) + "([^" + Regex.escape(close) + "\\n]+)" + Regex.escape(close)) }
    private val AMOUNT = Regex("[¥$]\\s*\\d+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?\\s*(?:元|块钱|块|万元|万|美元)")
    private val DIGITS = Regex("\\d{2,}")
    private val ONLY_DIGITS = Regex("\\d+")
    private val PIECE_SPLIT = Regex("[\\s,.:;!?、。·]+")
    /** These lines in the draft's front matter are identifiers and are not scanned for numbers (run id, package names); the exemption applies only inside the front matter. */
    private val KEEP_LINES = Regex("^(?:name|apps|packages|domains|learned_from|source):")

    fun norm(s: String): String = NumberText.collapse(s)

    fun literals(goal: String, criteria: List<String>): Set<String> {
        val out = LinkedHashSet<String>()
        fun quoted(t: String) = QUOTED.forEach { re -> re.findAll(t).forEach { out += it.groupValues[1].trim() } }
        val g = norm(goal)
        quoted(g)
        out += numbers(g)
        criteria.map(::norm).forEach(::quoted)
        return keep(out)
    }

    /**
     * A whole piece of text (screen text quoted by verification, typed text) together with the numbers and amounts inside it, plus each piece split on whitespace and punctuation, all count as specific values
     * (a personal name in a message like "Wang Xiaoming: see you tomorrow" must be replaced even when it appears on its own).
     */
    fun textLiterals(texts: List<String>): Set<String> =
        keep(texts.map(::norm).flatMap { t -> listOf(t.trim()) + numbers(t) + t.split(PIECE_SPLIT) })

    /** Longer values are replaced first so a short value does not split a longer one; all-digit values only replace a whole number ("12" does not touch "12306"). */
    fun redact(md: String, literals: Set<String>): String {
        var out = norm(md)
        for (lit in keep(literals.map(::norm)).sortedByDescending { it.length }) {
            out = if (ONLY_DIGITS.matches(lit)) Regex("(?<!\\d)" + lit + "(?!\\d)").replace(out, PLACEHOLDER)
            else out.replace(lit, PLACEHOLDER)
        }
        return out
    }

    /**
     * Scan the whole draft again: any remaining amounts and digit strings of 2 or more are always replaced (order numbers, phone numbers and prices are mostly this run's values);
     * [keepDigits] are digits that belong to app names (appearing in built-in skill names and aliases) and are kept.
     */
    fun scrubNumbers(md: String, keepDigits: Set<String>): String {
        val lines = norm(md).lines()
        val frontEnd = if (lines.firstOrNull()?.trimEnd() == "---") (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" } ?: -1 else -1
        return lines.mapIndexed { i, line ->
            if (i in 1 until frontEnd && KEEP_LINES.containsMatchIn(line)) line
            else DIGITS.replace(AMOUNT.replace(line) { m -> if (m.value.filter(Char::isDigit) in keepDigits) m.value else PLACEHOLDER }) { m ->
                if (m.value in keepDigits) m.value else PLACEHOLDER
            }
        }.joinToString("\n")
    }

    private fun numbers(t: String) = AMOUNT.findAll(t).map { it.value.trim() } + DIGITS.findAll(t).map { it.value }

    // Replacing a one-character value would scramble the body, since such characters appear everywhere in the text; all-digit values are exempt from this limit
    private fun keep(values: Iterable<String>) =
        values.map { it.trim() }.filter { it.isNotEmpty() && (it.length >= 2 || ONLY_DIGITS.matches(it)) }.toSet()
}
