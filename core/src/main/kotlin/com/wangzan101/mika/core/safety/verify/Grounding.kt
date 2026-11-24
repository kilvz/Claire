package com.wangzan101.mika.core.safety.verify

/**
 * Code backstop: after the verifier judges pass, re-check the textual evidence with a model-independent rule. The code only
 * checks what it can check accurately — whether something appears literally — and does not judge whether a value belongs to
 * the thing the answer names (that is the verifier model's job, see the verifier prompt). In the answer body (before the
 * "basis:" section):
 * - Quoted text (「…」『…』“…”"…"‘…’, except text already present in the goal) must be found somewhere in the material;
 *   a letter inside quotes standing for a number the answer itself wrote ("today +N" meaning "today +2,271" in the body) is
 *   describing the answer's own wording, not on-screen text;
 * - Numbers with a unit, currency, percent sign or magnitude word (ten-thousand / hundred-million / thousand), decimals,
 *   integers of three or more digits, and ids embedded in letters must be found as whole words somewhere in the material;
 * - Dates and times (October 23, 2026-10-23, 14:05) must likewise be found.
 * In the "basis:" section only quotations are checked: every quotation must be found somewhere in the material (a … inside
 * it splits it into pieces, and every piece must be in the same place); the rest of the basis (source, URL, read time,
 * counts in the narrative) is not a fact to verify — matching it can help locate, but not matching it does not veto.
 * Integers of up to two digits without a unit are never required anywhere. Numbers and quoted text from the goal are taken as
 * known.
 * Two other sources also count as known, each proving only what it can: the day computed from the runtime clock when the goal
 * or the evidence says "tomorrow" or "Tuesday"; and the result of an arithmetic expression written in the answer (a operator
 * b = c, with a and b both matched in the evidence), plus rounding explicitly marked as approximate (about, ≈).
 * Before comparing, both sides are normalized: full-width/half-width (NFKC), zero-width characters, citation markers and
 * footnotes, thousands separators, ten-thousand/hundred-million scaling, punctuation and whitespace; both sides are compared
 * line by line, so one answer line is not joined with the next (leading list numbers are stripped per line) and the material
 * is not joined into one long string; the "current app:" line does not count as on-screen text.
 * Citation boundaries use explicit lookarounds rather than \b: Java's \b treats Chinese characters as word characters, so the
 * 46 in "@e46" followed directly by Chinese text would be taken as a number in the answer.
 */
internal object Grounding {
    private val QUOTED = Regex("「([^」]*)」|『([^』]*)』|“([^”]*)”|\"([^\"]*)\"|‘([^’]*)’")
    private val EVIDENCE = Regex("(?:依据|Basis|Sources)\\**\\s*[:：]", RegexOption.IGNORE_CASE)
    /**
     * A magnitude word after a number (hundred-million / ten-thousand / thousand) is a magnitude (only whitespace may come
     * between — apps often lay it out as "1561" plus the magnitude word; with punctuation in between it is not one); the
     * thousand in kilometer / kilogram / kilowatt is part of the unit, not a magnitude. Ordinals at the start of a line or of
     * a quotation ("5" then a title) are stripped by [ORDINAL] first.
     */
    private val NUMBER = Regex("(?<!\\d)(?<!\\d[.,])\\d+(?:[.,]\\d+)*(?!\\d)([ \\t]*(?:[亿万]|千(?![米克瓦卡帕焦])))?")
    private val THOUSANDS = Regex("^\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?$")
    private val DIGITS = Regex("\\d+")
    /** Characters right after a number that show it is a quantity (they never follow an ordinal): units, magnitudes, classifiers. */
    private const val UNIT_WORDS = "公里|千米|米|厘米|毫米|公斤|千克|斤|克|吨|元|块|角|小时|分钟|秒|岁|度|分|人|次|个|条|件|万|亿|千[米克瓦卡帕焦]|[A-Za-z%℃°]"

    /** Ordinals are not numbers to verify: the Nth item/step/rank…, the first N items…, Top N, No.N, #N, list numbers at the start of a line (1. 2、 3)). "the 2026th edition" is a value, not an ordinal. */
    private val ORDINAL = Regex(
        "第[ \\t]*\\d+[ \\t]*(?=[条个集页项张篇步名位行章节轮次题])|前[ \\t]*\\d+[ \\t]*(?=[个条名位项篇张])|(?i:top)[ \\t]*\\d+|(?i:no)\\.[ \\t]*\\d+|#\\d+" +
            "|(?m)^[ \\t]*\\d+[ \\t]*[.、)）](?!\\d)" +
            // An ordinal at the start of a line or quotation followed by a space is a name ("5" then a title): not a value
            "|(?m)(?:^|(?<=[「『“\"‘]))[ \\t]*\\d{1,3}[ \\t]*[.、](?!\\d)|(?m)(?:^|(?<=[「『“\"‘]))[ \\t]*\\d{1,3}[ \\t]+(?=\\p{L})(?!$UNIT_WORDS)",
    )
    /** Dates and times; the dot-separated form only accepts three parts (year.month.day), so that 1561.5 is not mistaken for a date. */
    private val DATE = Regex(
        "(?<!\\d)(?:\\d{4}\\s*[-/年]\\s*\\d{1,2}(?:\\s*[-/月]\\s*\\d{1,2}\\s*[日号]?)?|\\d{4}\\.\\d{1,2}\\.\\d{1,2}" +
            "|\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号]?|\\d{1,2}\\s*:\\s*\\d{2}(?:\\s*:\\s*\\d{2})?)(?!\\d)",
    )
    /** Units right after a number that show it is a quantity value (counting classifiers such as "items" or "pcs" do not count: those were counted by the answer itself). */
    private val UNIT_AFTER = Regex(
        "^\\s*(?:%|‰|元|块|角|美元|欧元|日元|港元|英镑|℃|°|度|公里|千米|米|厘米|毫米|公斤|千克|斤|克|吨|小时|分钟|秒|毫秒|岁|英寸|寸|折|星" +
            "|(?:km|cm|mm|kg|mAh|GB|MB|KB|TB|Hz|ms|[kKwWmgGM])(?![A-Za-z]))",
    )
    private val CURRENCY_BEFORE = Regex("[¥$€£]\\s*$")
    private val ZERO_WIDTH = Regex("[\\u200B\\u200C\\u200D\\uFEFF\\u2060]")
    /** Emphasis and code markers in the answer (**bold**, __bold__, ~~strike~~, `code`) are only formatting, not content; a single * may be a multiplication sign and is left alone. */
    private val MARKUP = Regex("\\*\\*|__|~~|`")
    private val FRAGMENT_BREAK = Regex("[^\\p{L}]+")
    private val PIECE_BREAK = Regex("[^\\p{L}\\p{N}]+")
    /**
     * Citation forms used by different models: element refs (@e16, @v2, possibly wrapped in brackets) and bracketed
     * footnotes ([1], [^2], 【3】). They are not answer content and are stripped before comparing. Boundaries use
     * lookarounds: a ref immediately followed by Chinese characters must still be recognized.
     */
    private val REF_CITATION = Regex(
        "[(\\[【]\\s*@[ev]\\d+(?:\\s*[,、]\\s*@[ev]\\d+)*\\s*[)\\]】]|(?<![A-Za-z0-9_])@[ev]\\d+(?!\\d)",
    )
    private val FOOTNOTE = Regex("\\[\\^?\\d{1,3}]|【\\d{1,3}】")
    private val MAGNITUDE = mapOf("亿" to 8, "万" to 4, "千" to 3)
    const val APP_HEADER = "Current app:"
    /** Text fragments in the basis that are too short would coincidentally match any screen ("on screen", "settings"), so they do not count. */
    private const val MIN_FRAGMENT = 4
    /** For an on-screen line to count as appearing in the basis, the line needs at least this many characters. */
    private const val MIN_LINE_IN_BASIS = 2

    private data class Num(val value: String, val magnitude: String) {
        /** The value after applying the magnitude (5.3 ten-thousand equals 53000); null when it cannot be computed. */
        val scaled: String? = value.toBigDecimalOrNull()?.let { v ->
            (MAGNITUDE[magnitude]?.let { v.movePointRight(it) } ?: v).stripTrailingZeros().toPlainString()
        }
        fun matches(screen: Num) = (value == screen.value && (magnitude.isEmpty() || magnitude == screen.magnitude)) ||
            (scaled != null && scaled == screen.scaled)
    }

    /** [Token]: an id embedded in English letters (G7541, a3f899c), compared as a whole word rather than as a number. [Quote] covers quotations in the basis. */
    enum class Kind { Quote, Number, Date, Token }

    /** One concrete fact in the answer. Numbers and dates are values that can be written as text; only quotations without digits can exist only on screen (such as a radio button's selected state). */
    data class Claim(val kind: Kind, val text: String) {
        val visualOnly: Boolean get() = kind == Kind.Quote && text.none(Char::isDigit)
    }

    /**
     * The backstop result. [unmatched] are the facts found in no evidence source; [anchored] means at least one fact or one
     * place in the basis matched.
     * [found]: true when it matches, false when it does not, null when the answer has nothing checkable (or only numbers
     * matched with no quotation or basis location).
     * [items] is the number of verifiable items in the answer (used by the evaluation's answer_grounded, see [check]).
     */
    class Result(
        val found: Boolean?, val claims: List<Claim>, val unmatched: List<Claim>, val anchored: Boolean, val located: Boolean = false, val items: Int = 0,
    )

    /** After NFKC, … is already ... . */
    private const val CUT = "..."
    /** The text right before ... in the answer (compared once it has at least a few characters). */
    private val BEFORE_CUT = Regex("([^\\n.「」『』“”\"]{2,20})\\.\\.\\.")

    private fun isCut(line: String) = line.trimEnd().endsWith(CUT)

    /**
     * Whether the answer is built on truncated text: the text right before ... in the answer, or the quoted text (with a
     * leading or trailing ... removed), appears on screen only in truncated lines and runs all the way to the truncation
     * point. Truncated text is only part of a value and cannot be verified as the whole value.
     * Only on-screen truncation counts: if the answer itself uses ... to omit the rest of a quotation while that on-screen
     * line is complete, it does not count as truncated.
     */
    fun builtOnTruncated(answer: String, screens: List<String>): Boolean {
        val a = canon(answer)
        val raw = screens.flatMap { canon(it).lines() }.filter { it.isNotBlank() && !it.startsWith(APP_HEADER) }
        fun onlyOnCutLines(piece: String): Boolean {
            val p = norm(piece)
            if (p.length < 2) return false
            val hits = raw.filter { p in norm(it) }
            return hits.isNotEmpty() && hits.all { l -> isCut(l) && norm(l.trimEnd().removeSuffix(CUT)).endsWith(p) }
        }
        // The quotation starts with ... (the beginning of that on-screen line was cut off): this text only appears in lines starting with ...
        fun onlyOnHeadCutLines(piece: String): Boolean {
            val p = norm(piece)
            if (p.length < 2) return false
            val hits = raw.filter { p in norm(it) }
            return hits.isNotEmpty() && hits.all { l -> l.trimStart().startsWith(CUT) && norm(l.trimStart().removePrefix(CUT)).startsWith(p) }
        }
        val quoted = quotes(a).map(String::trim)
        if (quoted.any { q -> q.endsWith(CUT) && onlyOnCutLines(q.removeSuffix(CUT)) }) return true
        if (quoted.any { q -> q.startsWith(CUT) && onlyOnHeadCutLines(q.removePrefix(CUT).removeSuffix(CUT)) }) return true
        if (quoted.any(::onlyOnCutLines)) return true
        // The text right before ... may be preceded by the answer's own words ("requires Kotlin + Jetpack..."): take the longest tail that appears on screen for comparison
        return BEFORE_CUT.findAll(a).any { m ->
            val cap = m.groupValues[1]
            val tail = cap.indices.map { cap.substring(it) }.firstOrNull { t -> norm(t).length >= 2 && raw.any { norm(t) in norm(it) } }
            tail != null && onlyOnCutLines(tail)
        }
    }

    /** Strip citation markers and footnotes (they are not answer content). */
    fun stripRefs(s: String): String = s.replace(REF_CITATION, " ").replace(FOOTNOTE, " ")

    /** Normalize both sides before comparing: full-width/half-width (NFKC), zero-width characters, formatting markers, citation markers and footnotes. */
    private fun canon(s: String): String =
        stripRefs(java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC).replace(ZERO_WIDTH, "").replace(MARKUP, ""))

    private fun norm(s: String) = s.filter { it.isLetterOrDigit() }

    // Empty quotes ("" in JSON, 「」) have no text to verify: skip them, and never let the whole task crash over it
    private fun quotes(s: String) = QUOTED.findAll(s).mapNotNull { m -> m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } }.toList()

    private fun num(m: MatchResult): Num {
        val raw = m.value.substring(0, m.value.length - m.groupValues[1].length)
        return Num(if (THOUSANDS.matches(raw)) raw.replace(",", "") else raw, m.groupValues[1].trim())
    }

    private fun numbers(s: String): List<Num> = NUMBER.findAll(s).map(::num).toList()

    /**
     * Numbers in [s] that count as facts: ones with a magnitude, unit or currency symbol, decimals, and integers of three or
     * more digits.
     * Integers of up to two digits without a unit are counts, ordinals or rankings and do not count.
     */
    private fun valueNumbers(s: String, known: Set<String>): List<Num> = NUMBER.findAll(s).mapNotNull { m ->
        val n = num(m)
        if (n.scaled != null && n.scaled in known) return@mapNotNull null
        val after = s.substring(m.range.last + 1, minOf(s.length, m.range.last + 9))
        val unit = n.magnitude.isNotEmpty() || UNIT_AFTER.containsMatchIn(after) || CURRENCY_BEFORE.containsMatchIn(s.substring(0, m.range.first))
        val decimal = '.' in n.value
        val long = n.value.substringBefore('.').substringBefore(',').length >= 3
        n.takeIf { unit || decimal || long }
    }.toList()

    private fun components(s: String) = DIGITS.findAll(s).map { it.value.trimStart('0').ifEmpty { "0" } }.toList()

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.isAsciiAlnum() = isAsciiLetter() || this in '0'..'9'

    /**
     * Whether the number in on-screen [s] from [start] to [end] (exclusive) is a complete word: the preceding character must
     * not be an English letter or digit; when English letters follow, they may only be a run of letters ending there (a unit,
     * such as 128GB) and no digits may follow. Digits inside hashes, ids in URLs, or ids like a3f899c do not count as
     * on-screen numbers. Decimal points and thousands separators inside the number do not matter.
     */
    private fun wholeToken(s: String, start: Int, end: Int): Boolean {
        if (s.getOrNull(start - 1)?.isAsciiAlnum() == true) return false
        var i = end
        while (i < s.length && s[i].isAsciiLetter()) i++
        return i == end || i == s.length || s[i] !in '0'..'9'
    }

    /**
     * Which lines of [text] (by [text]'s line numbers) match facts in the answer: when showing excerpts to the verifier
     * model, these lines are always kept ([EvidenceLedger.excerpt]). Not a verification rule.
     */
    fun hitLines(goal: String, answer: String, text: String, today: java.time.LocalDate? = null): Set<Int> {
        val r = check(goal, answer, listOf(text), today)
        val ok = r.claims.filter { it !in r.unmatched }
        if (ok.isEmpty()) return emptySet()
        val pieces = ok.filter { it.kind == Kind.Quote }.flatMap { q -> q.text.split(CUT).flatMap { it.split(PIECE_BREAK) } }.map(::norm).filter { it.length >= 2 }
        val raw = canon(text).lines()
        return raw.indices.filter { i ->
            val l = raw[i]
            if (l.isBlank() || l.startsWith(APP_HEADER)) return@filter false
            val line = lines(listOf(l)).single()
            pieces.any { it in line.text } || ok.any { c ->
                when (c.kind) {
                    Kind.Quote -> false
                    Kind.Number -> numbers(c.text).firstOrNull()?.let { n -> line.nums.any(n::matches) } == true
                    Kind.Date -> components(c.text).let { v -> v.isNotEmpty() && line.digits.windowed(v.size).any { it == v } } ||
                        components(c.text).drop(1).let { v -> v.size == 2 && line.digits.windowed(2).any { it == v } }
                    Kind.Token -> c.text.lowercase() in line.tokens
                }
            }
        }.toSet()
    }

    /** The complete numbers in one on-screen line (see [wholeToken]). */
    private fun screenNumbers(s: String): List<Num> = NUMBER.findAll(s).mapNotNull { m ->
        val n = num(m)
        n.takeIf { wholeToken(s, m.range.first, m.range.first + m.value.length - m.groupValues[1].length) }
    }.toList()

    /** The complete digit strings in one on-screen line (used for dates). */
    private fun screenComponents(s: String) = DIGITS.findAll(s).filter { wholeToken(s, it.range.first, it.range.last + 1) }
        .map { it.value.trimStart('0').ifEmpty { "0" } }.toList()

    private class Line(val text: String, val nums: List<Num>, val digits: List<String>, val tokens: Set<String>)

    private val ALNUM_RUN = Regex("[A-Za-z0-9]+")

    /** Alphanumeric runs in a line (used to compare ids, case-insensitive). */
    private fun alnumRuns(s: String) = ALNUM_RUN.findAll(s).map { it.value.lowercase() }.toSet()

    /**
     * The ids in [s] embedded in English letters (numbers that are not complete words, see [wholeToken]), and the text with
     * them replaced by blanks of the same length: each id is verified as one whole value and the digits inside it no longer
     * count as numbers.
     */
    private fun idTokens(s: String): Pair<List<String>, String> {
        val ids = NUMBER.findAll(s).filterNot { m -> wholeToken(s, m.range.first, m.range.first + m.value.length - m.groupValues[1].length) }
            .mapNotNull { m ->
                var a = m.range.first
                var b = m.range.last + 1
                while (a > 0 && s[a - 1].isAsciiAlnum()) a--
                while (b < s.length && s[b].isAsciiAlnum()) b++
                a until b
            }.distinct().toList()
        var t = s
        ids.forEach { r -> t = t.replaceRange(r, " ".repeat(r.last - r.first + 1)) }
        return ids.map { s.substring(it) } to t
    }

    private fun lines(screens: List<String>) = screens.flatMap { canon(it).lines() }
        .filter { it.isNotBlank() && !it.startsWith(APP_HEADER) }.map { Line(norm(it), screenNumbers(it), screenComponents(it), alnumRuns(it)) }

    /** At least one line of the quotation (which may span several lines) can be found in one of the on-screen lines. */
    fun quoteInReads(quote: String, screens: List<String>): Boolean {
        val ls = lines(screens)
        return canon(quote).lines().map(::norm).filter { it.length >= 2 }.any { q -> ls.any { q in it.text } }
    }

    /** true when it matches, false when it does not; null when the answer has nothing checkable. */
    fun found(goal: String, answer: String, screens: List<String>): Boolean? = check(goal, answer, screens).found

    /** Relative days: today, tomorrow, the day after tomorrow, yesterday, and weekday names (zhou-X / xingqi-X). */
    private val RELATIVE_DAY = Regex("今天|今日|明天|明日|后天|昨天|昨日|(?:周|星期|礼拜)([一二三四五六日天])")
    private val WEEKDAY_CHARS = "一二三四五六日"
    /** The range scanned when converting a weekday name to a concrete date: from yesterday to a week ahead. */
    private const val WEEK_AHEAD = 7L

    /** The dates [word] resolves to relative to [today] (a weekday name can have two between yesterday and a week ahead). */
    private fun resolve(word: MatchResult, today: java.time.LocalDate): List<java.time.LocalDate> = when (word.value) {
        "今天", "今日" -> listOf(today)
        "明天", "明日" -> listOf(today.plusDays(1))
        "后天" -> listOf(today.plusDays(2))
        "昨天", "昨日" -> listOf(today.minusDays(1))
        else -> {
            val c = word.groupValues[1].replace("天", "日")
            val dow = java.time.DayOfWeek.of(WEEKDAY_CHARS.indexOf(c) + 1)
            (-1L..WEEK_AHEAD).map(today::plusDays).filter { it.dayOfWeek == dow }
        }
    }

    /** The year, month and day in a date form; null for a year-month only form or for a time. */
    private fun dateParts(d: String): Triple<Int?, Int, Int>? {
        if (':' in d) return null
        val c = DIGITS.findAll(d).map { it.value.toInt() }.toList()
        return when {
            c.size == 3 && c[0] >= 1000 -> Triple(c[0], c[1], c[2])
            c.size == 2 && !Regex("^\\d{4}").containsMatchIn(d.trim()) -> Triple(null, c[0], c[1])
            else -> null
        }
    }

    /**
     * Dates the runtime clock can vouch for: when the goal or one of the evidence lines says "tomorrow" or "Tuesday", the day
     * it resolves to from [today] vouches for that date when the answer body (outside quotations and the "basis:" section)
     * writes it out concretely. A relative expression appearing only in the answer proves nothing; times of day, dates inside
     * quotations, and dates in the basis (which the answer claims were read from the page) are not vouched for by the clock.
     */
    private fun clockDates(body: String, basis: String, said: List<String>, today: java.time.LocalDate?): Set<String> {
        today ?: return emptySet()
        val meant = said.flatMap { t -> RELATIVE_DAY.findAll(t).flatMap { resolve(it, today) }.toList() }.toSet()
        if (meant.isEmpty()) return emptySet()
        val plain = QUOTED.replace(body) { " ".repeat(it.value.length) }
        val quotedOrRead = quotes(body).joinToString("\n") + "\n" + basis
        return plain.lines().flatMap { DATE.findAll(it).toList() }.mapNotNull { m ->
            val text = m.value.trim()
            val (y, mo, d) = dateParts(text) ?: return@mapNotNull null
            if (DATE.findAll(quotedOrRead).any { it.value.trim() == text }) return@mapNotNull null
            text.takeIf { meant.any { it.monthValue == mo && it.dayOfMonth == d && (y == null || it.year == y) } }
        }.toSet()
    }

    private const val NUM_TOKEN = "(\\d+(?:,\\d{3})*(?:\\.\\d+)?)"
    /** One operand: an optional currency symbol, an immediately following unit (letter, ℃, °, %), and a source noted in parentheses ("399 (product page)"). */
    private const val OPERAND = "([¥$€£]?)\\s*$NUM_TOKEN\\s*([\\p{L}℃°%]{0,4})\\s*(?:[(（][^()（）\\n]{1,20}[)）])?\\s*"
    /**
     * A one-step calculation written out in the answer: a operator b = c (or ≈ c, = about c); the operators are + - − × * ÷ /.
     * When c carries a trailing %, the results of ÷ and / are compared as percentages.
     */
    private val EXPRESSION = Regex(
        OPERAND + "([+\\-−×*÷/])\\s*" + OPERAND + "(=|≈)\\s*(约|≈)?\\s*([¥$€£]?)\\s*$NUM_TOKEN(\\s*%)?\\s*([\\p{L}℃°]{0,4})",
    )

    /** A standard unit: its dimension and the factor for converting to the base unit. */
    private class Unit(val dimension: String, val factor: java.math.BigDecimal)

    /**
     * Synonyms and conversions for standard units (a closed table, with no other guessing): kilometer/km, meter/m,
     * yuan/quai/¥/RMB/renminbi, gram/g, kilogram/kg, hour/h, minute/min, %/percent. The longest spelling is recognized first.
     */
    private val UNITS: List<Pair<String, Unit>> = listOf(
        "公里" to Unit("length", java.math.BigDecimal(1000)), "千米" to Unit("length", java.math.BigDecimal(1000)), "km" to Unit("length", java.math.BigDecimal(1000)),
        "米" to Unit("length", java.math.BigDecimal.ONE), "m" to Unit("length", java.math.BigDecimal.ONE),
        "人民币" to Unit("cny", java.math.BigDecimal.ONE), "RMB" to Unit("cny", java.math.BigDecimal.ONE), "元" to Unit("cny", java.math.BigDecimal.ONE),
        "块" to Unit("cny", java.math.BigDecimal.ONE), "¥" to Unit("cny", java.math.BigDecimal.ONE),
        "千克" to Unit("mass", java.math.BigDecimal(1000)), "公斤" to Unit("mass", java.math.BigDecimal(1000)), "kg" to Unit("mass", java.math.BigDecimal(1000)),
        "克" to Unit("mass", java.math.BigDecimal.ONE), "g" to Unit("mass", java.math.BigDecimal.ONE),
        "小时" to Unit("time", java.math.BigDecimal(3600)), "h" to Unit("time", java.math.BigDecimal(3600)),
        "分钟" to Unit("time", java.math.BigDecimal(60)), "min" to Unit("time", java.math.BigDecimal(60)),
        "百分之" to Unit("percent", java.math.BigDecimal.ONE), "%" to Unit("percent", java.math.BigDecimal.ONE),
    ).sortedByDescending { it.first.length }

    /** The standard unit recognized from the currency symbol and the characters after the number; null when none is recognized (treated as no unit written). */
    private fun unitOf(before: String, after: String): Unit? =
        UNITS.firstOrNull { (k, _) -> before == k }?.second ?: UNITS.firstOrNull { (k, _) -> after.startsWith(k, ignoreCase = k.all { it.isAsciiLetter() }) && !after.drop(k.length).take(1).any { it.isAsciiLetter() } }?.second

    /** The unit of an operand in a calculation: units in the table use the standard unit; anything not in the table (dollar sign, mile, …) becomes its own dimension and only matches itself. */
    private fun operandUnit(before: String, after: String): Unit? =
        unitOf(before, after) ?: (before + after.trim()).takeIf { it.isNotBlank() }?.let { Unit("other:" + it.lowercase(), java.math.BigDecimal.ONE) }
    /** A marker right before a number that explicitly says it is approximate. */
    private val APPROX_BEFORE = Regex("(?:约|≈)\\s*[¥$€£]?\\s*$")

    /**
     * [x] is a rounded form of [d]: when it is not marked approximate, only rounding at the last place written in [x] is
     * accepted (integers must be equal, decimals are judged at their decimal place); when it is explicitly approximate
     * (about, ≈), trailing zeros count as precision too (900 rounds to the hundreds) and the relative error must not exceed
     * [MAX_ROUND_ERROR].
     */
    private fun roundsTo(x: java.math.BigDecimal, d: java.math.BigDecimal, approx: Boolean): Boolean {
        val diff = (x - d).abs()
        if (diff.signum() == 0) return true
        val power = when {
            x.scale() > 0 -> -x.scale()
            approx -> minOf(-x.stripTrailingZeros().scale(), MAX_ROUND_POWER).coerceAtLeast(0)
            else -> 0
        }
        val half = java.math.BigDecimal.ONE.scaleByPowerOfTen(power).divide(java.math.BigDecimal(2))
        if (diff > half) return false
        return !approx || (d.signum() != 0 && diff.divide(d.abs(), java.math.MathContext.DECIMAL64) <= MAX_ROUND_ERROR)
    }

    private const val MAX_ROUND_POWER = 6
    private val MAX_ROUND_ERROR = java.math.BigDecimal("0.05")
    private val HUNDRED = java.math.BigDecimal(100)

    private fun plainValue(s: String) = s.replace(",", "").toBigDecimalOrNull()

    /**
     * Results of calculations written out in the answer whose two operands both match the evidence ("6363−5464=899"):
     * returns the written form of the result (the same way the number is written in the answer).
     * [grounded] tells whether a number matches the evidence (excluding numbers from the goal).
     */
    private fun computed(body: String, grounded: (String) -> Boolean): Set<String> = body.lines().flatMap { EXPRESSION.findAll(it).toList() }.mapNotNull { m ->
        val g = m.groupValues
        val (aCur, aRaw, aUnit, op) = listOf(g[1], g[2], g[3], g[4])
        val (bCur, bRaw, bUnit, eq) = listOf(g[5], g[6], g[7], g[8])
        val (approxWord, cCur, cRaw, pct) = listOf(g[9], g[10], g[11], g[12])
        val cUnitText = g[13]
        if (!grounded(aRaw) || !grounded(bRaw)) return@mapNotNull null
        var a = plainValue(aRaw) ?: return@mapNotNull null
        var b = plainValue(bRaw) ?: return@mapNotNull null
        var c = plainValue(cRaw) ?: return@mapNotNull null
        val mc = java.math.MathContext.DECIMAL64
        // Addition and subtraction need the same dimension: written units are converted by the standard table (kilometer and km are the same, kilometer and meter differ by a factor of 1000); a calculation mixing dimensions does not count
        if (op in setOf("+", "-", "−")) {
            val ua = operandUnit(aCur, aUnit)
            val ub = operandUnit(bCur, bUnit)
            val uc = unitOf(cCur, if (pct.isNotBlank()) "%" else cUnitText)
            val units = listOfNotNull(ua, ub, uc)
            if (units.map { it.dimension }.distinct().size > 1) return@mapNotNull null
            val base = ua ?: ub ?: uc
            if (base != null) {
                a = a * (ua ?: base).factor; b = b * (ub ?: base).factor
                c = c * (uc ?: ua ?: base).factor
            }
        }
        val r = when (op) {
            "+" -> a + b
            "-", "−" -> a - b
            "×", "*" -> a * b
            else -> if (b.signum() == 0) return@mapNotNull null else a.divide(b, mc)
        }
        val result = if (pct.isNotBlank() && (op == "÷" || op == "/")) r * HUNDRED else r
        val approx = eq == "≈" || approxWord.isNotEmpty()
        cRaw.replace(",", "").takeIf { roundsTo(c, result, approx) }
    }.toSet()

    /** A single letter in a quotation standing in for a number (the N in "today +N"). */
    private val PLACEHOLDER = Regex("(?<![A-Za-z])[A-Za-z](?![A-Za-z])")

    /**
     * The quotation [q] is describing the answer's own wording: it contains a letter standing in for a number, and with the
     * letter replaced by a number it is exactly a passage written in the answer body (outside quotes) ("today +N" matching
     * "today +2,271").
     */
    private fun template(q: String, plain: String): Boolean {
        if (!PLACEHOLDER.containsMatchIn(q)) return false
        val pattern = q.trim().split(Regex("\\s+")).joinToString("\\s*") { w ->
            PLACEHOLDER.split(w).joinToString("\\d+(?:[.,]\\d+)*") { Regex.escape(it) }
        }
        return Regex(pattern).containsMatchIn(plain)
    }

    private val EMPHASIS = Regex("\\*\\*|__|`")
    /** List markers at the start of a line (1. 2、 3) ① - * •). */
    private val LIST_MARK = Regex("^[ \\t]*(?:\\(?\\d{1,3}[.、)）:：]|（\\d{1,3}）|[①-⑳]|[-*•·])[ \\t]*")
    /** The value in a "label: value" list entry. */
    private val LABEL_VALUE = Regex("^[^:：]{1,12}[:：]\\s*(.+)$")
    /** Minimum characters per item (single characters and punctuation are on every screen and must not be used to pad the count). */
    private const val MIN_ITEM = 2

    fun check(goal: String, answer: String, screens: List<String>, today: java.time.LocalDate? = null): Result {
        val a = canon(answer)
        val entries = screens.map { lines(listOf(it)) }
        val ls = entries.flatten()
        val g = canon(goal)
        val goalNums = numbers(g).map { it.value }.toSet()
        // Numbers from the goal are compared by value (1000.0 and 1000 are the same number)
        val goalValues = numbers(g).mapNotNull { it.scaled }.toSet()
        val goalText = norm(g)
        val at = EVIDENCE.find(a)
        val basis = at?.let { a.substring(it.range.last + 1) }.orEmpty()
        val body = at?.let { a.substring(0, it.range.first) } ?: a

        fun numOnScreen(n: Num) = ls.any { l -> l.nums.any(n::matches) }
        /** [q] is in these [where] lines: either the whole text in one line, or every piece split on punctuation and whitespace in some line (numbers in it are compared as whole numbers). */
        fun textIn(q: String, where: List<Line>): Boolean {
            val t = norm(q)
            if (t.isEmpty() || where.any { t in it.text }) return true
            val pieces = q.split(PIECE_BREAK).map(::norm).filter(String::isNotEmpty)
            // A leading ordinal ("5" then a title) and digits embedded in letters (AQ3A) are not numbers and are not compared
            return pieces.size > 1 && pieces.all { p -> where.any { p in it.text } } &&
                screenNumbers(ORDINAL.replace(q.trim(), " ")).all { n -> where.any { l -> l.nums.any(n::matches) } }
        }
        /** The quotation is somewhere in the material: the pieces split by ... each match, and all in the same place. */
        fun quoteFound(q: String): Boolean {
            val parts = q.split(CUT).filter { norm(it).isNotEmpty() }.ifEmpty { listOf(q) }
            return entries.any { e -> parts.all { textIn(it, e) } }
        }
        fun dateOnScreen(d: String): Boolean {
            val c = components(d)
            val variants = listOfNotNull(c, c.takeIf { it.size == 3 && it[0].length == 4 }?.drop(1))
            return variants.any { v -> ls.any { l -> l.digits.windowed(v.size).any { it == v } } }
        }

        // Collect values line by line: one answer line is not joined with the next, and leading list numbers are stripped per line
        fun values(line: String): List<Claim> {
            val dates = DATE.findAll(line).map { it.value }.filter { d -> components(d).any { it !in goalNums } }.toList()
            val (ids, plain) = idTokens(ORDINAL.replace(DATE.replace(line, " "), " "))
            return dates.map { Claim(Kind.Date, it.trim()) } + ids.filter { norm(it) !in goalText }.map { Claim(Kind.Token, it) } +
                valueNumbers(plain, goalValues).map { Claim(Kind.Number, it.value + it.magnitude) }
        }
        val plainBody = QUOTED.replace(body, " ")
        val bodyQuotes = quotes(body).filter { norm(it).length >= 2 && norm(it) !in goalText && !template(it, plainBody) }
        val basisQuotes = quotes(basis).filter { norm(it).length >= 2 }
        val bodyLines = body.lines().map { it.replace(EMPHASIS, "") }
        val lineClaims = bodyLines.map(::values)
        val claims = (bodyQuotes.map { Claim(Kind.Quote, it) } + lineClaims.flatten() + basisQuotes.map { Claim(Kind.Quote, it) }).distinct()

        // Places where the evidence says "tomorrow" or "Tuesday": the goal and every evidence line
        val byClock = clockDates(body, basis, listOf(g) + ls.map { it.text }, today)
        fun matched(c: Claim) = when (c.kind) {
            Kind.Quote -> quoteFound(c.text)
            Kind.Number -> numbers(c.text).firstOrNull()?.let(::numOnScreen) ?: false
            Kind.Date -> dateOnScreen(c.text) || c.text in byClock
            Kind.Token -> ls.any { c.text.lowercase() in it.tokens }
        }
        // Numbers that match the evidence (numbers from the goal do not count: a result computed from the goal's numbers must still be read on screen)
        fun grounded(n: Num) = (n.scaled == null || n.scaled !in goalValues) && numOnScreen(n)
        fun groundedRaw(raw: String) = numbers(raw).firstOrNull()?.let(::grounded) == true
        val known = a.lines().flatMap { numbers(ORDINAL.replace(DATE.replace(it, " "), " ")) }.filter(::grounded).mapNotNull { it.scaled?.toBigDecimalOrNull() }
        // Results of written-out calculations, and numbers explicitly marked approximate (about, ≈) that equal a matched number after rounding at the written precision
        val derivedNums = computed(body, ::groundedRaw) + body.lines().flatMap { line ->
            NUMBER.findAll(line).mapNotNull { m ->
                val n = num(m)
                val x = n.scaled?.toBigDecimalOrNull() ?: return@mapNotNull null
                if (!APPROX_BEFORE.containsMatchIn(line.substring(0, m.range.first))) return@mapNotNull null
                (n.value + n.magnitude).takeIf { known.any { roundsTo(x, it, approx = true) } }
            }.toList()
        }
        val unmatched = claims.filterNot { matched(it) || (it.kind == Kind.Number && it.text in derivedNums) }

        // Non-quotation text in the basis only helps locate (one match is enough); a mismatch does not veto
        val basisNorm = norm(basis)
        val fragments = QUOTED.replace(basis, " ").split(FRAGMENT_BREAK).map(::norm).filter { it.length >= MIN_FRAGMENT }
        val located = (basisQuotes.isNotEmpty() && basisQuotes.all(::quoteFound)) || fragments.any { f -> ls.any { f in it.text } } ||
            ls.any { it.text.length >= MIN_LINE_IN_BASIS && it.text in basisNorm }

        val quoteMatched = claims.any { it.kind == Kind.Quote && it !in unmatched }
        val found = when {
            unmatched.isNotEmpty() -> false
            quoteMatched || located -> true
            // No quotation and the basis did not locate: only numbers matched, and numbers can coincide on screen, so on their own they do not count
            else -> null
        }

        // Verifiable items: answer lines containing matched facts (identical lines counted once), and value-less list items
        // whose whole text appears in some line; take the larger of that count and the number of distinct matched facts
        val okClaims = claims.filter { it !in unmatched }.toSet()
        val rows = mutableSetOf<String>()
        val bare = mutableSetOf<String>()
        bodyLines.forEachIndexed { i, raw ->
            val key = norm(raw)
            if (key.isEmpty()) return@forEachIndexed
            val inLine = lineClaims[i] + quotes(raw).map { Claim(Kind.Quote, it) }
            if (inLine.any { it in okClaims }) { rows += key; return@forEachIndexed }
            if (inLine.isNotEmpty() || !LIST_MARK.containsMatchIn(raw)) return@forEachIndexed
            val item = raw.replace(LIST_MARK, "").trim().let { t -> LABEL_VALUE.find(t)?.groupValues?.get(1) ?: t }
            if (norm(item).length >= MIN_ITEM && norm(item) !in goalText && textIn(item, ls)) bare += norm(item)
        }
        val okInBody = (bodyQuotes.map { Claim(Kind.Quote, it) } + lineClaims.flatten()).filter { it in okClaims }.toSet()
        val items = maxOf(rows.size, okInBody.size) + bare.size
        return Result(found, claims, unmatched, anchored = located || claims.size > unmatched.size, located = located, items = items)
    }
}
