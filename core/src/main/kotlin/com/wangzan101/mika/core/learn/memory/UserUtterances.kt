package com.wangzan101.mika.core.learn.memory

import java.text.Normalizer

/**
 * Things the user himself said during this task: his own words in the opening message, in interjections and in answers
 * to follow-up questions (attached screen and browser-page descriptions, quoted replies from Claire and attachment lines
 * do not count; quotes of his own earlier words do count), and remarks typed when taking back control.
 * A memory's `quote` must be a substring of one of those sentences (compared after unifying full-width/half-width forms
 * and trimming) — text from screens, web pages or tool results does not count.
 */
class UserUtterances {
    private val texts = mutableListOf<String>()
    /** The only utterance that may be quoted across turns: the user in the previous turn explicitly confirmed the assistant's proposal/question from the turn before that. */
    private var previousConfirmation: String? = null

    @Synchronized fun add(text: String) {
        if (text.isNotBlank()) texts += text
    }

    /** The user's words in this turn; automatic extraction uses only these too, and does not treat the previous turn's confirmation as having been said again this turn. */
    val all: List<String> @Synchronized get() = texts.toList()

    /**
     * Registers the only cross-turn exception. [confirmation] is the user's own words in the previous turn, [proposal]
     * is the assistant's reply in the turn immediately before that.
     * Both the proposal/question and the explicit affirmation must be visible in the original text; negations,
     * corrections, plain statements and earlier turns are never registered.
     */
    @Synchronized fun addPreviousConfirmation(confirmation: String, proposal: String, proposalStatus: String) {
        previousConfirmation = null
        if (proposalStatus != "succeeded" && proposalStatus != "unverified") return
        if (!proposal.contains('?') && !proposal.contains('？') && !PROPOSAL.containsMatchIn(proposal)) return
        val text = confirmation.trim()
        if (text.isEmpty() || NEGATION.containsMatchIn(text) || !CONFIRMATION.containsMatchIn(text)) return
        previousConfirmation = text
    }

    /** Cleared at the start of every task: words from the previous task do not count for this one, and a cross-turn confirmation must be registered again against this run's context. */
    @Synchronized fun clear() {
        texts.clear()
        previousConfirmation = null
    }

    /**
     * The specifics in [content] that are found neither in [quote] nor in what the user said this time: values the
     * model copied off the screen while paraphrasing.
     * A specific is: quoted content; a number (Chinese numerals and separated digits are first unified into one run of
     * Arabic digits); an English word/identifier of 3+ letters; or an address fragment (a place-marker character plus
     * the two characters before it, e.g. "…Road" or "…Garden"; only two characters are taken, so the model adding a
     * district name does not cause a false rejection).
     * The marker characters that are one character long (road, lane, garden, city, number, …) are also the ending or
     * the beginning of many ordinary words: "know", "park", "city" are not addresses, so bringing them out under a
     * different wording while paraphrasing does not count as adding a specific ([ordinaryWord]).
     * Before comparing, "the user('s)" and "I/my" are stripped from both sides, so third-person paraphrasing alone does
     * not cause a false rejection.
     *
     * Limitation: Chinese personal names without quotation marks and place names without a marker character cannot be
     * recognized (e.g. "send it to Wang Xiaoming"); these rely on the prompt and on the user reviewing the result card.
     */
    @Synchronized fun unsupportedSpecifics(content: String, quote: String): List<String> =
        unsupportedSpecifics(content, quote, texts)

    /**
     * Used when editing a memory: the same as [unsupportedSpecifics], except that values present in [known] (that
     * entry's original content and keywords) also count — specifics carried over unchanged are not newly added. When
     * the user says "make that one more concise", they will not repeat the address or numbers in it.
     */
    @Synchronized fun unsupportedEditSpecifics(content: String, quote: String, known: List<String>): List<String> =
        unsupportedSpecifics(content, quote, texts + known)

    /**
     * For memory_save, that utterance takes part in the specific-value check only when the quote itself comes from the
     * previous turn's confirmation; ordinary writes in the current turn keep the original validation.
     */
    @Synchronized fun unsupportedMemorySpecifics(content: String, quote: String): List<String> {
        val sources = if (matches(quote, texts)) texts else texts + listOfNotNull(previousConfirmation)
        return unsupportedSpecifics(content, quote, sources)
    }

    private fun unsupportedSpecifics(content: String, quote: String, sources: List<String>): List<String> {
        val c = comparable(content)
        val said = (sources + quote).map(::comparable)
        val specifics = QUOTED.findAll(c).map { it.groupValues[1] } + DIGITS.findAll(c).map { it.value } +
            LATIN.findAll(c).map { it.value } + PLACE.findAll(c).filterNot { ordinaryWord(c, it) }.map { it.value }
        return specifics.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .filter { v -> said.none { it.contains(v, ignoreCase = true) } }.toList()
    }

    private fun comparable(s: String) = PERSON.replace(NumberText.collapse(s), "")

    /**
     * Whether the [PLACE] match is in fact an ordinary word rather than an address: a common word ending in a single
     * marker character ("know", "park", "mobile number"), or the marker character is actually the start of the word
     * that follows (the city marker inside the word for "city", the lane marker inside the word for "inside").
     * Places whose names happen to end with such words (e.g. "Chaoyang Park") are therefore not recognized; addresses
     * usually also carry a street name and a house number, and those are still checked.
     */
    private fun ordinaryWord(text: String, m: MatchResult): Boolean {
        val v = m.value
        if (v.length != 3) return false
        if (v.substring(1) in ORDINARY_ENDINGS || v in ORDINARY_ENDINGS) return true
        val next = text.getOrNull(m.range.last + 1) ?: return false
        return "${v[2]}$next" in ORDINARY_STARTS
    }

    /** Quotes shorter than 2 characters do not count: one or two characters can be found in any sentence. Ordinary writes only accept the current turn. */
    @Synchronized fun contains(quote: String): Boolean = matches(quote, texts)

    /** Used when editing or deleting a memory: checks the quote's context against the full original utterance, for example a negation preceding the quote. */
    @Synchronized internal fun matchingCurrentMessages(quote: String): List<String> =
        matchingMessages(quote, texts).toList()

    /**
     * Which sentence of this turn the quote falls in, and which clauses of that sentence it covers (split at clause
     * punctuation): one group per sentence; empty when no sentence of this turn contains the quote.
     * In "don't remember my address, remember that I like tea", quoting "don't remember my address" covers only the
     * first clause — the "remember" in the second half cannot be borrowed for it.
     */
    @Synchronized internal fun quotedClauses(quote: String): List<List<String>> {
        val q = norm(quote)
        if (q.length < MIN_QUOTE) return emptyList()
        return texts.map(::norm).mapNotNull { m ->
            val hits = generateSequence(m.indexOf(q)) { at -> m.indexOf(q, at + 1) }.takeWhile { it >= 0 }.toList()
            if (hits.isEmpty()) null
            else clauses(m).filter { c -> hits.any { at -> c.first < at + q.length && at <= c.last } }.map { m.substring(it.first, it.last + 1) }
        }
    }

    private fun clauses(m: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = 0
        for (end in CLAUSE_END.findAll(m)) {
            if (end.range.first > start) out += start until end.range.first
            start = end.range.last + 1
        }
        if (start < m.length) out += start until m.length
        return out
    }

    /** memory_save additionally accepts a registered explicit confirmation from the previous turn; the assistant's proposal itself is never a quotable user utterance. */
    @Synchronized fun containsMemoryQuote(quote: String): Boolean =
        matches(quote, texts + listOfNotNull(previousConfirmation))

    private fun matches(quote: String, sources: List<String>): Boolean = matchingMessages(quote, sources).any()

    private fun matchingMessages(quote: String, sources: List<String>): Sequence<String> {
        val q = norm(quote)
        if (q.length < MIN_QUOTE) return emptySequence()
        return sources.asSequence().map(::norm).filter { it.contains(q) }
    }

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC).trim()

    private companion object {
        const val MIN_QUOTE = 2
        // clause punctuation; an English period counts only when followed by whitespace or at the end of the text
        // (the dots in 3.5 or example.com do not count)
        val CLAUSE_END = Regex("[,，。;；!！?？\\n]|\\.(?=\\s|$)")
        val CONFIRMATION = Regex("""^(?:(?:嗯+|恩+|呃|哦|噢|啊)[\s,，。.!！~～]*)?(?:好的?|好呀|好嘞|行|可以|对的?|是的?|没错|同意|就这样|就这么|ok(?:ay)?|yes|yeah|yep|sure|correct|exactly|agreed|sounds\s+good|go\s+ahead)(?=$|[\s,，。.!！:：;；~～])""", RegexOption.IGNORE_CASE)
        val NEGATION = Regex("""不|别|算了|取消|但是|不过|\b(?:no|not|don't|do\s+not|stop|cancel|wrong|but)\b""", RegexOption.IGNORE_CASE)
        val PROPOSAL = Regex("""建议|推荐|可以叫|叫我|命名为|起名|名字|要不要|不如|我可以|你觉得|怎么样|suggest|recommend|how\s+about|would\s+you\s+like|shall\s+(?:i|we)|you\s+can\s+call|i\s+(?:can|could)|let['’]s""", RegexOption.IGNORE_CASE)
        val QUOTED = Regex("[「『“\"]([^「」『』“”\"\n]+)[」』”\"]")
        val DIGITS = Regex("\\d+")
        val LATIN = Regex("[A-Za-z][A-Za-z0-9._-]{2,}")
        val PLACE = Regex("\\p{IsHan}{2}(?:小区|大厦|广场|公寓|花园|单元|路|街|巷|弄|镇|村|号|楼|栋|室|区|县|市|省|园|苑|城|道|里|湾)")
        val PERSON = Regex("用户的|用户|我的|我")
        // Common words that end with one of the single marker characters in [PLACE] but are not place names. Only those
        // where the marker character clearly belongs to the word itself are included: "avenue", "homeland", "building",
        // "community"; the kind that is also a real place-name suffix (e.g. Chang'an Avenue, Xingfu Homeland) is
        // excluded, preferring to reject one too many now and then. "First Road", "road" and "mountain area" are also
        // excluded: Keji First Road, Second Road and Baoshan District are all real place names
        val ORDINARY_ENDINGS = setOf(
            "知道", "味道", "频道", "渠道", "通道", "地道", "报道", "难道", "道路", "思路", "套路", "出路", "销路",
            "这里", "那里", "哪里", "家里", "心里", "手里", "夜里", "屋里", "城里", "公里",
            "城市", "超市", "都市", "上市", "门市", "夜市", "公园", "校园", "乐园", "果园",
            "账号", "帐号", "型号", "编号", "信号", "符号", "称号", "问号", "外号", "口号", "手机号", "微信号", "公众号", "订单号",
            "地区", "市区", "郊区", "上楼", "下楼", "节省", "反省",
            "教室", "卧室", "浴室", "办公室", "会议室", "实验室", "休息室", "上街", "逛街", "乡村", "农村", "城镇", "乡镇",
        )
        // Common words that start with a marker character: the two characters before it plus the marker get taken by
        // [PLACE] as a place name. Directional phrases that follow a real place name — "on the road", "at the
        // intersection", "on the street", "in the village" — are not included (the "Renmin Road" inside "the shop on
        // Renmin Road" is genuine).
        // Nothing starting with a province, city, district, county, town or village character is included either:
        // administrative divisions are often followed directly by an institution name, and "Nanjing Civil Affairs
        // Bureau", "Chaoyang District Branch", "Guangdong Provincial Public Institution" and "Lijia Village Committee"
        // contain, once split up, exactly the ordinary words for "citizen", "distinguish", "save trouble" and
        // "villager", so genuine place names would be missed as ordinary words
        val ORDINARY_STARTS = setOf(
            "城市", "道理", "道歉", "道具", "道德", "里面", "里边", "里头",
            "号码", "号称", "号召", "楼梯", "室内", "室外", "栋梁",
        )
    }
}
