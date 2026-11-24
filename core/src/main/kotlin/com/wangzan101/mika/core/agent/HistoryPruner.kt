package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.screen.observation.ObsForm
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.Verb

/**
 * Trims stale content during compaction: an old interface is long out of date, and an old large result can be re-read
 * by position — keeping them only bloats the context.
 * Only in the single call where [ContextCompressor] compacts (normally, messages already sent are resent as-is); it only
 * returns a copy, so the full history held by the caller is unchanged; call and result ids stay paired and all other
 * messages are kept as-is.
 *
 * Trimming follows one cut line ([Cut]): rounds after the line are kept as-is, and those before it are shortened
 * according to what kind of thing the observation read in the result was ([ObsForm], see [BY_FORM]):
 * interface state (interface reads, the interface brought back by an action, the element text of a recall) is always
 * replaced with [OMITTED]; page content, command output, files and replies have their long fields replaced with
 * [OMITTED_READ] only when the result is large (over [BIG_RESULT_TOKENS] tokens); what the user said (memory) and
 * reference material are not trimmed. Results without observations are identified by the action.
 * A shortened result keeps its outcome (ok, error and reason) and the observation IDs pointing back to the evidence
 * ledger ([OBS_IDS]). The most recent full interface read before the line is not trimmed: later actions and diffs can
 * only be understood with it.
 * Images before the line are replaced with placeholders (a screen image is recallable via [olderImage], an image file
 * that was read is [OMITTED_IMAGE]); the user's words together with their photos are not trimmed.
 *
 * When the provider has a definite image limit ([limitImages]), placeholders replace images starting from the earliest
 * assistant image, and only once that limit is exceeded.
 */
object HistoryPruner {
    const val OMITTED = "(screen read earlier, omitted; read it again if needed)"
    const val OMITTED_IMAGE = "(old image omitted)"

    /** Placeholder for an earlier screen image. */
    fun olderImage(imageId: Int) = "(earlier screen image omitted; to see it again use view_past_screens with ids [$imageId])"
    const val OMITTED_READ = "(content read earlier, omitted; read it again with nextOffset, path and so on if needed)"
    /** Observation IDs in a shortened result that point back to the evidence ledger. */
    const val OBS_IDS = "obsIds"
    /**
     * A tool result over this many tokens counts as a "large result". Tool results are mostly English and JSON (about 4
     * characters per token), so this takes the token count corresponding to the old 4000-character line, leaving how
     * English results are trimmed unchanged; Chinese results are counted by their real usage and therefore count as
     * large earlier than before.
     */
    const val BIG_RESULT_TOKENS = 1_000

    /** How each kind of observation before the line is shortened. */
    private enum class Shrink { Interface, IfBig, Never }

    /** Shortening is chosen by the observation's form: interface state is always shortened, pages, command output, files, replies and facts only when large, and what the user said not at all. */
    private val BY_FORM = mapOf(
        ObsForm.Interface to Shrink.Interface,
        ObsForm.Page to Shrink.IfBig, ObsForm.Command to Shrink.IfBig, ObsForm.File to Shrink.IfBig,
        ObsForm.Reply to Shrink.IfBig, ObsForm.Fact to Shrink.IfBig, ObsForm.Image to Shrink.IfBig,
        ObsForm.UserSaid to Shrink.Never,
    )

    /** Reference material the model follows: not trimmed by size. */
    private val REFERENCE_VERBS = setOf(Verb.KnowledgeSearch, Verb.KnowledgeRead, Verb.SkillSearch, Verb.SkillRead, Verb.MemorySearch)
    /** Results without observations: interface-reading actions are still shortened as interface state. */
    private val INTERFACE_VERBS = setOf(Verb.Observe, Verb.ListElements, Verb.Diagnose)
    private const val LONG_TEXT = 300
    /** Full interface reads (the element text in a recall result also goes stale; the recall itself is not counted as a read). */
    private val FULL_VERBS = setOf(Verb.Observe, Verb.ListElements)
    private const val SCREEN = "screen"
    /** Outcome fields always kept when shortening. */
    private val OUTCOME = setOf("ok", "error", "message", "recoverable", "exitCode")

    /** The cut line: the most recent [window] tool round trips are kept as-is, earlier ones are shortened. */
    data class Cut(val window: Int) {
        init { require(window >= 1) { "need window >= 1" } }

        /** How many round trips come before the line given a total of [rounds]. */
        fun before(rounds: Int): Int = (rounds - window).coerceAtLeast(0)
    }

    /** Still over the limit after compaction: keep only the most recent round trip as-is. */
    val LAST = Cut(1)

    /** [tools]: looks up the action performed by a called tool name. */
    fun prune(input: List<Message>, tools: ToolIndex, cut: Cut): List<Message> {
        val starts = input.indices.filter { i -> input[i].role == Role.Assistant && input[i].parts.any { it is Part.ToolCall } }
        val k = cut.before(starts.size)
        if (k == 0) return input
        val line = starts[k]
        val names = callVerbs(input, tools)
        val screens = screenResults(input)
        // The most recent full interface read before the line
        val anchor = (0 until line).lastOrNull { i ->
            input[i].parts.any { it is Part.ToolResult && (names[it.callId] in FULL_VERBS || screens[it.callId] == true) }
        }
        // What the user said (together with photos) is not trimmed
        return input.mapIndexed { i, m -> if (i >= line || i == anchor || m.author == Author.User) m else shrinkMessage(m, names, screens) }
    }

    /**
     * Fallback for when even the hardest compaction does not fit: starting from the newest, replace the long fields of
     * tool results (of any kind) over [BIG_RESULT_TOKENS] tokens with [OMITTED_READ], keeping the outcome fields and
     * observation IDs, until [fits]. The user's words are left untouched.
     */
    fun shrinkOversized(messages: List<Message>, fits: (List<Message>) -> Boolean): List<Message> {
        val out = messages.toMutableList()
        for (i in out.indices.reversed()) {
            if (fits(out)) break
            val m = out[i]
            if (m.author == Author.User || m.parts.none { it is Part.ToolResult && TokenEstimate.of(it.content) > BIG_RESULT_TOKENS }) continue
            out[i] = m.copy(parts = m.parts.map { p ->
                if (p is Part.ToolResult && TokenEstimate.of(p.content) > BIG_RESULT_TOKENS) withObsIds(p, shrinkRead(p.content)) else p
            })
        }
        return out
    }

    /** [maxImages]: the confirmed per-request image limit of the provider; when exceeded, images are replaced with placeholders starting from the earliest assistant image, while the user's own words and attachments stay as-is. */
    fun limitImages(messages: List<Message>, maxImages: Int): List<Message> {
        require(maxImages >= 0)
        var excess = messages.sumOf { m -> m.parts.count { it is Part.Image } } - maxImages
        if (excess <= 0) return messages
        return messages.map { m ->
            if (m.author == Author.User || excess <= 0) m else {
                val parts = m.parts.map { p ->
                    if (p is Part.Image && excess > 0) {
                        excess--
                        Part.Text(p.screenshotId?.let(::olderImage) ?: OMITTED_IMAGE)
                    } else p
                }
                if (parts == m.parts) m else m.copy(parts = parts)
            }
        }
    }

    private fun shrinkMessage(m: Message, names: Map<String, Verb?>, screens: Map<String, Boolean>): Message {
        val parts = m.parts.map { p ->
            when (p) {
                is Part.Image -> Part.Text(p.screenshotId?.let(::olderImage) ?: OMITTED_IMAGE)
                is Part.ToolResult -> shrinkResult(p, names[p.callId], screens)
                else -> p
            }
        }
        return if (parts == m.parts) m else m.copy(parts = parts)
    }

    private fun shrinkResult(p: Part.ToolResult, verb: Verb?, screens: Map<String, Boolean>): Part.ToolResult {
        val ways = p.reads.mapNotNull { BY_FORM[it.form] }.toSet()
        val content = when {
            Shrink.Never in ways || verb in REFERENCE_VERBS -> p.content
            p.callId in screens -> shrinkScreen(p.content)
            Shrink.Interface in ways || verb in INTERFACE_VERBS -> shrinkInterface(p.content)
            verb == Verb.ViewPastScreens -> shrinkRecall(p.content)
            TokenEstimate.of(p.content) > BIG_RESULT_TOKENS -> shrinkRead(p.content)
            else -> p.content
        }
        return if (content == p.content) p else withObsIds(p, content)
    }

    /** The shortened result points back to those observations in the evidence ledger. */
    private fun withObsIds(p: Part.ToolResult, content: String): Part.ToolResult {
        val ids = p.reads.map { it.obsId }.distinct()
        val withIds = if (ids.isEmpty()) content else parse(content)?.let { o ->
            JsonObject(o + (OBS_IDS to JsonArray(ids.map(::JsonPrimitive)))).toString()
        } ?: content
        return p.copy(content = withIds)
    }

    /** Replace long fields (long strings, large arrays/objects) with [OMITTED_READ] and keep the outcome fields; content that is not JSON is replaced wholesale. */
    private fun shrinkRead(content: String): String {
        val obj = parse(content) ?: return OMITTED_READ
        return JsonObject(obj.mapValues { (k, v) ->
            val long = if (v is JsonPrimitive) v.isString && v.content.length > LONG_TEXT else v.toString().length > LONG_TEXT
            if (long && k !in OUTCOME) JsonPrimitive(OMITTED_READ) else v
        }).toString()
    }

    private fun callVerbs(messages: List<Message>, tools: ToolIndex): Map<String, Verb?> {
        val verbs = HashMap<String, Verb?>()
        messages.forEach { m -> m.parts.filterIsInstance<Part.ToolCall>().forEach { verbs[it.id] = tools.verb(it.name) } }
        return verbs
    }

    /** Tool results containing a screen object: callId → whether the screen is full. */
    private fun screenResults(messages: List<Message>): Map<String, Boolean> {
        val out = HashMap<String, Boolean>()
        messages.forEach { m ->
            m.parts.filterIsInstance<Part.ToolResult>().filter { "\"$SCREEN\":{" in it.content }.forEach { r ->
                (parse(r.content)?.get(SCREEN) as? JsonObject)?.let { out[r.callId] = it["full"] == JsonPrimitive(true) }
            }
        }
        return out
    }

    private fun shrinkRecall(content: String): String {
        val obj = parse(content) ?: return content
        val shots = obj["screenshots"] as? JsonArray ?: return content
        val small = JsonArray(shots.map { e ->
            val o = e as? JsonObject
            if (o == null || "elementsText" !in o) e else JsonObject(o + ("elementsText" to JsonPrimitive(OMITTED)))
        })
        return JsonObject(obj + ("screenshots" to small)).toString()
    }

    private fun shrinkScreen(content: String): String {
        val obj = parse(content) ?: return content
        return JsonObject(obj + (SCREEN to JsonPrimitive(OMITTED))).toString()
    }

    private fun parse(content: String): JsonObject? = try {
        Json.parseToJsonElement(content) as? JsonObject
    } catch (e: SerializationException) {
        null
    }

    /** Interface state: the element table, window tree and long fields are replaced with [OMITTED], outcome fields are kept. */
    private fun shrinkInterface(content: String): String {
        val obj = parse(content) ?: return content
        val small = obj.mapValues { (k, v) ->
            val long = if (v is JsonPrimitive) v.isString && v.content.length > LONG_TEXT else v.toString().length > LONG_TEXT
            if (k == "elements" || k == ScreenElementTable.KEY || k == "tree" || (long && k !in OUTCOME)) JsonPrimitive(OMITTED) else v
        }
        return JsonObject(small).toString()
    }
}
