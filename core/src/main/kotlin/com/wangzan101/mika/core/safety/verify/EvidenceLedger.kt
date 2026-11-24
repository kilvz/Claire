package com.wangzan101.mika.core.safety.verify

import com.wangzan101.mika.core.screen.observation.InputHints
import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.Observation
import com.wangzan101.mika.core.run.record.Redactor

/**
 * All the material the assistant actually read during a task (the evidence ledger): screen reads, web page reads, command
 * output and files read, and facts from tool results.
 * The runtime verifier and the evaluation checker use the same ledger and the same selection rule ([forAnswer]), so the two
 * never look at different things.
 *
 * - Redacted before entry ([Redactor.evidence]) and clipped per source; identical text keeps only the latest copy (its origin
 *   is updated along with it). After a takeover, input-field text that the assistant did not type (text the user filled in
 *   during the takeover) is not recorded; lines of command output that appear verbatim in the command itself (echo) are not
 *   recorded.
 * - Total size is capped ([capChars]): past it the earliest entries are dropped. This is a memory safety cap that normal
 *   tasks never reach.
 * - Entries handed to verification ([forAnswer]): every one containing a fact from the answer (none is dropped), the latest
 *   entry for each app or URL mentioned by those entries and by the answer (what was read later may contradict the answer),
 *   and the latest [RECENT] read. Ordered by when they were read. The code check looks at their full text; the verifier model
 *   only sees excerpts of them ([excerpt]: the lines matching facts plus a few around them, with a total length cap),
 *   together with their origin (which step, which kind of source, which app or URL, page title).
 *   Selection only decides what is shown; the verification rules ([Grounding]) stay the same: what was never read is not in
 *   the ledger, and no amount of selection can prove it.
 * - Text the assistant typed into an input field does not support the answer: that field's text, and suggestion lines
 *   starting with it while the field still has focus, are removed during selection; a result title that happens to equal the
 *   search term is still kept. When the input fields are unknown (older records), whole lines equal to typed text are removed.
 */
class EvidenceLedger(private val capChars: Int = STORE_CHARS) {
    private val entries = ArrayDeque<Observation>()
    private val typed = mutableSetOf<String>()
    private var total = 0
    private var seq = 0
    private var afterTakeover = false

    val all: List<Observation> get() = entries.toList()
    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * Record one observation; one that is blank after redaction and clipping is not recorded. Returns the recorded entry
     * (with id, step and timestamp filled in; [step] and [atMs] are the current ones).
     * The observation's [Observation.inputs] are the input-field text at that time; [Observation.authored] is what the
     * assistant itself wrote (commands it ran): lines appearing verbatim inside it are echoes and are not recorded.
     */
    fun add(o: Observation, step: Int = o.step, atMs: Long = o.atMs): Observation? {
        val ins = o.inputs?.names?.map(::norm)?.filter { it.isNotEmpty() }
        var lines = Redactor.evidence(o.text).lines()
        // Text the user filled in during the takeover (not typed by the assistant) is not kept
        if (afterTakeover && ins != null) lines = lines.filterNot { l -> norm(l).let { n -> n in ins && typed.none { it in n } } }
        o.authored?.let(::norm)?.takeIf { it.isNotEmpty() }?.let { a -> lines = lines.filterNot { l -> norm(l).let { it.isNotEmpty() && it in a } } }
        val t = clip(o.kind, lines.joinToString("\n"))
        if (t.isBlank()) return null
        val place = o.place?.let { it.copy(name = Redactor.evidence(it.name)) }
        // Echoes are already removed, so the recorded entry no longer carries what the assistant wrote itself
        return insert(o.copy(text = t, place = place, step = step, atMs = atMs, id = ++seq, authored = null,
            inputs = ins?.let { InputHints(it, o.inputs.focused?.let(::norm)?.takeIf { f -> f.isNotEmpty() }) },
            title = o.title?.let(Redactor::evidence)?.takeIf { it.isNotBlank() }))
    }

    /**
     * Put an observation already recorded at runtime back exactly as it is (the observed entry in the run log, already
     * redacted, clipped, echo-stripped, with its original id): no further filtering, only the same removal of an identical
     * older entry as on normal entry, and the total size cap. Used to rebuild the ledger offline, identical to the runtime
     * one.
     */
    fun restore(o: Observation): Observation {
        seq = maxOf(seq, o.id)
        return insert(o)
    }

    private fun insert(stored: Observation): Observation {
        entries.indexOfFirst { it.text == stored.text && it.place == stored.place }.takeIf { it >= 0 }?.let { total -= entries.removeAt(it).text.length }
        entries.addLast(stored)
        total += stored.text.length
        while (total > capChars && entries.size > 1) total -= entries.removeFirst().text.length
        return stored
    }

    /** This text (redacted and clipped the same way as on entry) is already in the ledger. */
    fun has(kind: ObsKind, text: String): Boolean = clip(kind, Redactor.evidence(text)).let { t -> entries.any { it.text == t } }

    /** Text the assistant typed with type_text; only non-password fields are recorded. */
    fun noteTyped(text: String) {
        norm(text).takeIf { it.isNotEmpty() }?.let { typed += it }
    }

    /** The user was handed control: input-field text read afterwards that the assistant did not type no longer enters the ledger. */
    fun noteTakeover() {
        afterTakeover = true
    }

    /** A copy of the same ledger (used when the runtime hands the ledger to the verifier; later screen reads do not affect this copy). */
    fun copy(): EvidenceLedger = EvidenceLedger(capChars).also { c ->
        c.entries.addAll(entries); c.typed.addAll(typed); c.total = total; c.seq = seq; c.afterTakeover = afterTakeover
    }

    /** Entries handed to verification: see the class doc. Each has input-field echoes removed; one with no text left is not selected. */
    fun forAnswer(goal: String, answer: String): List<Observation> {
        val cands = entries.mapNotNull(::withoutEcho)
        val holds = cands.indices.filter { i -> Grounding.check(goal, answer, listOf(cands[i].text)).let { it.claims.size > it.unmatched.size } }
        val said = (goal + "\n" + answer).lowercase()
        val places = holds.mapNotNull { cands[it].where } + cands.mapNotNull { it.where }.filter { w -> placeNames(w).any { it in said } }
        val latest = places.distinct().map { w -> cands.indexOfLast { it.where == w } }
        val recent = (cands.size - RECENT until cands.size).filter { it >= 0 }
        return (holds + latest + recent).distinct().sorted().map { cands[it] }
    }

    /** The id of the latest recorded entry (0 before anything is recorded); used with [readSince] to tell whether anything was read after a point in time. */
    val lastId: Int get() = seq

    /** Something was read after id [id] (screen reads, web pages, command output and files; facts reported by tools do not count as reads). */
    fun readSince(id: Int): Boolean = entries.any { it.id > id && it.kind != ObsKind.Fact }

    /** The entries whose facts match the answer (input-field echoes removed, in read order); empty when the answer has no checkable fact. Answer sources are found from these. */
    fun supporting(goal: String, answer: String): List<Observation> =
        entries.mapNotNull(::withoutEcho).filter { e -> Grounding.check(goal, answer, listOf(e.text)).let { it.claims.size > it.unmatched.size } }

    private fun withoutEcho(e: Observation): Observation? {
        if (typed.isEmpty() || e.kind != ObsKind.Read) return e
        val lines = e.text.lines()
        val ins = e.inputs?.names
        val kept = if (ins == null) lines.filterNot { norm(it) in typed } else {
            // One line is removed for each input field holding typed text (a result title with the same text is still kept)
            val left = ins.filter { n -> typed.any { it in n } }.groupingBy { it }.eachCount().toMutableMap()
            // A focused input field holds typed text: longer lines starting with it are suggestions
            val suggesting = e.inputs?.focused?.let { f -> typed.filter { it in f } }.orEmpty()
            lines.filterNot { l ->
                val n = norm(l)
                val k = left[n] ?: 0
                if (k > 0) { left[n] = k - 1; true } else n !in ins && suggesting.any { n.startsWith(it) && n.length > it.length }
            }
        }
        return if (kept.size == lines.size) e else kept.joinToString("\n").takeIf { it.isNotBlank() }?.let { e.copy(text = it) }
    }

    companion object {
        /** Safety cap on the ledger's total character count. */
        const val STORE_CHARS = 400_000
        /** Target cap on the total character count of excerpts handed to the verifier model (no entry containing an answer fact is dropped; when it does not fit, each is shortened further). */
        const val EXCERPT_CHARS = 6_000
        /** How many lines to keep before and after each matching line in an excerpt (reduced step by step to 0 when it does not fit). */
        const val EXCERPT_CONTEXT = 2
        /** Entries with no matched fact (the latest at a place, the latest read) only get this many characters from the start. */
        const val HEAD_CHARS = 600
        /** Minimum characters to keep per excerpt when it does not fit. */
        const val MIN_EXCERPT = 300
        /** How many of the latest entries are handed to verification. */
        const val RECENT = 1
        /** Maximum characters per entry for screen reads, web pages and other AIs' replies. */
        const val READ_CHARS = 6_000
        /** Maximum characters per entry for command output, file content and tool facts (clipped from both ends). */
        const val OUTPUT_CHARS = 4_000

        private val WS = Regex("\\s+")
        private fun norm(s: String) = s.replace(WS, "")
        private val PLACE_SPLIT = Regex("[^\\p{L}\\p{N}]+")
        private val PLACE_NOISE = setOf("com", "cn", "net", "org", "www", "http", "https", "android", "app", "html", "m")

        /** Words from an app name or URL that can be mentioned in the goal or answer (package-name and domain segments; too short or too common ones do not count). */
        private fun placeNames(where: String): List<String> =
            where.lowercase().split(PLACE_SPLIT).filter { it.length >= 3 && it !in PLACE_NOISE }

        /**
         * The excerpt shown to the verifier model: lines [hits] of [text] plus [context] lines before and after each, with a
         * "[N lines omitted]" line where text is skipped; when nothing matched (or with [withHead]) the first [HEAD_CHARS]
         * characters are given as well.
         * [cap] is the maximum character count for this entry (clipping happens on line boundaries and clipped text is marked
         * the same way).
         */
        fun excerpt(text: String, hits: Set<Int>, context: Int, cap: Int = Int.MAX_VALUE, withHead: Boolean = false): String {
            val ls = text.lines()
            var size = 0
            val head = ls.indices.takeWhile { i -> (size < HEAD_CHARS).also { size += ls[i].length + 1 } }.toSet()
            val near = hits.flatMap { h -> (h - context)..(h + context) }.filter { it in ls.indices }.toSet()
            val keep = if (hits.isEmpty() || withHead) head + near else near
            val out = StringBuilder()
            var skipped = 0
            fun flush() { if (skipped > 0) { out.append("[$skipped lines omitted]\n"); skipped = 0 } }
            var room = cap
            for (i in ls.indices) {
                if (i !in keep || room <= 0) { skipped++; continue }
                flush()
                val l = ls[i]
                if (l.length <= room) { out.append(l).append('\n'); room -= l.length + 1 }
                else { out.append(l.take(room)).append("[${l.length - room} characters omitted after this line]\n"); room = 0 }
            }
            flush()
            return out.toString().trimEnd()
        }

        private fun clip(kind: ObsKind, t: String): String = when (kind) {
            ObsKind.Read, ObsKind.Reply, ObsKind.UserSaid, ObsKind.Attachment -> t.take(READ_CHARS)
            ObsKind.Output, ObsKind.Fact ->
                if (t.length <= OUTPUT_CHARS) t else t.take(OUTPUT_CHARS * 3 / 4) + "\n…(中间省略)…\n" + t.takeLast(OUTPUT_CHARS / 4)
        }
    }
}
