package com.wangzan101.mika.core.learn.memory

import kotlin.math.ln
import kotlin.math.max

/**
 * Local lexical tokenization: Han characters are taken as adjacent pairs (a lone Han character is kept as a single
 * character); English and digits are split into words and lowercased.
 * [withUnigrams] additionally emits each Han character on its own (used for queries, so single-character search terms
 * can match).
 */
object Tokenize {
    private val WORD = Regex("[A-Za-z0-9]+")

    fun terms(text: String, withUnigrams: Boolean = false): List<String> {
        val out = mutableListOf<String>()
        val han = StringBuilder()
        fun flushHan() {
            if (han.length == 1) out += han.toString()
            else {
                for (i in 0 until han.length - 1) out += han.substring(i, i + 2)
                if (withUnigrams) han.forEach { out += it.toString() }
            }
            han.clear()
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (isHan(c)) {
                han.append(c); i++; continue
            }
            flushHan()
            val m = WORD.matchAt(text, i)
            if (m != null) { out += m.value.lowercase(); i = m.range.last + 1 } else i++
        }
        flushHan()
        return out
    }

    internal fun isHan(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    internal fun isUnigram(term: String) = term.length == 1 && isHan(term[0])
}

/**
 * Builds an inverted index from content + keywords and scores with BM25 (k1=1.2, b=0.75; search-term tf counts double).
 * When there are few memories, IDF is smoothed as if the corpus had at least [MIN_CORPUS] entries; otherwise nothing
 * would pass the threshold with only one or two memories.
 * Only "relevant" items are returned: the search term matched, or at least two distinct query terms (two-character
 * pairs/words) hit, or the only matched term occurs in this item alone and is one of its keywords.
 * Single characters take part in scoring only and are not counted in these three checks.
 */
class MemoryIndex(private val items: List<MemoryItem>) {
    private val docs: List<Map<String, Int>> = items.map { item ->
        val counts = HashMap<String, Int>()
        Tokenize.terms(item.content).forEach { counts.merge(it, 1, Int::plus) }
        // a single-character search term is already tokenized into single characters; multi-character search terms are
        // not additionally split into single characters, so single-character noise does not drown out two-character hits
        item.keywords.flatMap { Tokenize.terms(it) }.forEach { counts.merge(it, KEYWORD_TF, Int::plus) }
        counts
    }
    private val lengths = docs.map { d -> d.values.sum() }
    private val keywordTerms: List<Set<String>> = items.map { it.keywords.flatMap { k -> Tokenize.terms(k.lowercase()) }.toSet() }

    /** Sorted by score, highest first. */
    fun search(query: String, limit: Int, includePending: Boolean = false): List<Pair<MemoryItem, Double>> {
        val eligible = items.indices.filter { includePending || items[it].status == MemoryStatus.active }
        if (eligible.isEmpty() || limit <= 0) return emptyList()
        val corpus = max(eligible.size, MIN_CORPUS).toDouble()
        val avgLen = eligible.sumOf { lengths[it] }.toDouble() / eligible.size
        val q = query.lowercase()
        val terms = Tokenize.terms(q, withUnigrams = true).distinct().filter { it !in STOP_TERMS }
        if (terms.isEmpty()) return emptyList()
        val termSet = terms.toSet()
        val df = terms.associateWith { t -> eligible.count { t in docs[it] } }
        val idf = df.mapValues { (_, n) -> ln(1 + (corpus - n + 0.5) / (n + 0.5)) }

        return eligible.mapNotNull { i ->
            val doc = docs[i]
            val matched = terms.filter { it in doc }
            if (matched.isEmpty()) return@mapNotNull null
            val multi = matched.filterNot(Tokenize::isUnigram)
            val keywordHit = items[i].keywords.any { kw ->
                val k = kw.lowercase()
                val kt = Tokenize.terms(k)
                q.contains(k) || (kt.isNotEmpty() && kt.all { it in termSet })
            }
            // only one term hit: that term must occur in this item alone and be one of its keywords (a hit on a common
            // word such as a generic time adverb does not count)
            val relevant = keywordHit || multi.size >= 2 ||
                (multi.size == 1 && df.getValue(multi.single()) == 1 && multi.single() in keywordTerms[i])
            if (!relevant) return@mapNotNull null
            val norm = K1 * (1 - B + B * lengths[i] / avgLen.coerceAtLeast(1.0))
            val score = matched.sumOf { t ->
                val tf = doc.getValue(t)
                idf.getValue(t) * tf * (K1 + 1) / (tf + norm)
            }
            items[i] to score
        }.sortedByDescending { it.second }.take(limit)
    }

    private companion object {
        const val K1 = 1.2
        const val B = 0.75
        const val KEYWORD_TF = 2
        const val MIN_CORPUS = 50
        // applies only to query terms: colloquial function-word pairs occur in almost every sentence, so relevance
        // cannot rest on them alone
        val STOP_TERMS = setOf(
            "帮我", "给我", "一下", "我的", "不要", "这个", "那个", "可以", "一个", "用户",
            "今天", "这次", "现在", "什么", "怎么", "我们", "一些", "看看",
        )
    }
}
