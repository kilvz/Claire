package com.wangzan101.mika.ui.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wangzan101.mika.ui.FIND_LIMIT
import com.wangzan101.mika.ui.MdBlock
import com.wangzan101.mika.ui.findRanges

/**
 * Find state for the viewer screen: whether it is open, what is being searched, how many hits there are, and which one is current.
 * The hits are computed by the view currently being displayed and then reported back via [report]; the input box and counter bar only read from here.
 */
internal class FindState {
    var active by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var count by mutableStateOf(0)
        private set
    var index by mutableStateOf(0)
        private set

    /** Too many hits; only the first [FIND_LIMIT] were counted. */
    var more by mutableStateOf(false)
        private set

    fun open() { active = true }

    fun close() {
        active = false
        query = ""
        count = 0
        index = 0
        more = false
    }

    /** A new search term starts from the first hit; the count is filled in later by the view via [report]. */
    fun updateQuery(value: String) {
        if (value == query) return
        query = value
        index = 0
    }

    fun next() { if (count > 0) index = (index + 1) % count }

    fun prev() { if (count > 0) index = (index + count - 1) % count }

    /** Report back once the view has computed this round's hits. */
    fun report(hits: Int, more: Boolean) {
        count = hits
        this.more = more
        if (index >= count) index = 0
    }
}

/** One hit inside a text-like file: [chunk] is which chunk, range is the position within that chunk. */
internal data class TextHit(val chunk: Int, val range: IntRange)

/** All hits in a text-like file; also records whether they were truncated for being too many. */
internal class TextFinds(val hits: List<TextHit>, val more: Boolean) {
    /** The positions to mark within each chunk, grouped by chunk; the list only covers the chunks that are visible. */
    val byChunk: Map<Int, List<IntRange>> = hits.groupBy({ it.chunk }, { it.range })
}

/** Find [query] in chunked text; positions are per chunk, so jumping can go straight to that chunk. */
internal fun findInChunks(chunks: List<String>, query: String): TextFinds {
    if (query.isEmpty()) return TextFinds(emptyList(), more = false)
    val hits = ArrayList<TextHit>()
    chunks.forEachIndexed { index, chunk ->
        findRanges(chunk, query, FIND_LIMIT + 1 - hits.size).forEach { hits += TextHit(index, it) }
        if (hits.size > FIND_LIMIT) return TextFinds(hits.take(FIND_LIMIT), more = true)
    }
    return TextFinds(hits, more = false)
}

/**
 * One hit in Markdown: [block] is the top-level block index, [text] is the matched piece of text itself,
 * and [occurrence] is which occurrence it is within that piece — these two identify "the current hit" when displaying.
 */
internal data class BlockHit(val block: Int, val text: CharSequence, val occurrence: Int)

/** All hits in Markdown; also records whether they were truncated for being too many. */
internal class BlockFinds(val hits: List<BlockHit>, val more: Boolean)

/** Find [query] across the text pieces of Markdown; hits are recorded on top-level blocks, so jumping scrolls to that block. */
internal fun findInBlocks(blocks: List<MdBlock>, query: String): BlockFinds {
    if (query.isEmpty()) return BlockFinds(emptyList(), more = false)
    val hits = ArrayList<BlockHit>()
    val pieces = ArrayList<CharSequence>()
    blocks.forEachIndexed { index, block ->
        pieces.clear()
        block.textPieces(pieces)
        for (piece in pieces) {
            findRanges(piece, query, FIND_LIMIT + 1 - hits.size).forEachIndexed { occurrence, _ ->
                hits += BlockHit(index, piece, occurrence)
            }
            if (hits.size > FIND_LIMIT) return BlockFinds(hits.take(FIND_LIMIT), more = true)
        }
    }
    return BlockFinds(hits, more = false)
}

/** The text pieces of Markdown laid out in display order: paragraphs, headings, code, blocks inside quotes and lists, and table cells. */
internal fun MdBlock.textPieces(out: MutableList<CharSequence>) {
    when (this) {
        is MdBlock.Para -> out += text
        is MdBlock.Heading -> out += text
        is MdBlock.Code -> out += code
        is MdBlock.Quote -> blocks.forEach { it.textPieces(out) }
        is MdBlock.ListBlock -> items.forEach { item -> item.forEach { it.textPieces(out) } }
        is MdBlock.Table -> {
            out += header
            rows.forEach { row -> out += row }
        }
        MdBlock.Rule -> Unit
    }
}
