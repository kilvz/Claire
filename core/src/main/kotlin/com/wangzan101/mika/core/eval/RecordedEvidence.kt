package com.wangzan101.mika.core.eval

import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.safety.verify.EvidenceLedger
import com.wangzan101.mika.core.screen.observation.InputHints
import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.Observation
import com.wangzan101.mika.core.screen.observation.Place
import com.wangzan101.mika.core.screen.observation.PlaceKind
import com.wangzan101.mika.core.run.record.Redactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Rebuilds the evidence ledger from the run record. In a new record every observation that entered the ledger is recorded as an observed event (with an id): it is put back as-is ([EvidenceLedger.restore]), just like the runtime ledger.
 * Old records (with no numbered observed events) are rebuilt from the fields each tool recorded into the ledger at the time: the element text read from the screen (the legacy screen_text, which is substituted with an observed event with id 0 when read)
 * and the fields read from tool results; prompts, explanations and errors addressed to the working model in results do not count; things the assistant itself wrote (commands it ran, content written to a file and read back) are not evidence,
 * commands go into the place, and lines in the output that appear verbatim in the command (echo) are not recorded. For both kinds of records the text the assistant typed into the input box is added from the call arguments.
 */
internal object RecordedEvidence {
    fun ledger(events: List<RunEvent>, tools: ToolIndex): EvidenceLedger =
        if (events.any { it is RunEvent.Observed && it.id > 0 }) restored(events, tools) else legacy(events, tools)

    private fun restored(events: List<RunEvent>, tools: ToolIndex): EvidenceLedger {
        val l = EvidenceLedger()
        for (e in events) when (e) {
            is RunEvent.ModelReply -> typed(e, tools)?.let(l::noteTyped)
            is RunEvent.State -> if (e.name == TAKEOVER_STATE) l.noteTakeover()
            is RunEvent.Observed -> if (e.id > 0) l.restore(e.observation())
            else -> Unit
        }
        return l
    }

    /** The text this reply typed into the input box (text masked in the run record does not count). */
    private fun typed(e: RunEvent.ModelReply, tools: ToolIndex): String? =
        if (e.acted && tools.verb(e.tool) == Verb.TypeText) obj(e.argsJson.orEmpty())?.s("text")?.takeIf { it != Redactor.MASK } else null

    private fun legacy(events: List<RunEvent>, tools: ToolIndex): EvidenceLedger {
        val l = EvidenceLedger()
        val args = mutableMapOf<String, JsonObject>()
        val labels = mutableMapOf<String, String>()
        val written = mutableSetOf<String>()
        var site: String? = null
        // Title of the most recently opened or read web page: when no page-changing action follows, the screen read is of that page
        var title: Pair<String, Int>? = null
        for (e in events) when (e) {
            is RunEvent.ModelReply -> if (e.acted) {
                val a = obj(e.argsJson.orEmpty()) ?: JsonObject(emptyMap())
                e.tool?.let { args[it] = a }
                if (tools.verb(e.tool) == Verb.TypeText) a.s("text")?.let(l::noteTyped)
            }
            is RunEvent.State -> if (e.name == TAKEOVER_STATE) l.noteTakeover()
            is RunEvent.Observed -> {
                val onSite = e.place?.kind == PlaceKind.Site
                if (onSite) site = e.place?.name
                val head = title?.takeIf { onSite }?.first
                l.add(e.observation().copy(title = head), e.step, e.atMs)
            }
            is RunEvent.ToolResult -> {
                val r = obj(e.resultJson) ?: continue
                when (tools.verb(e.tool)) {
                    Verb.Navigate, Verb.ReadPage, Verb.Tabs -> title = r.s("title")?.takeIf { r["ok"] == JsonPrimitive(true) }?.let { it to e.step }
                    // After a tap, back, key press, typing or app switch, what is read may no longer be that page
                    in PAGE_CHANGING -> title = null
                    else -> Unit
                }
                note(l, e, tools, r, args[e.tool] ?: JsonObject(emptyMap()), labels, written, site)
            }
            else -> Unit
        }
        return l
    }

    private fun note(
        l: EvidenceLedger, e: RunEvent.ToolResult, tools: ToolIndex, r: JsonObject, a: JsonObject,
        labels: MutableMap<String, String>, written: MutableSet<String>, site: String?,
    ) {
        val ok = r["ok"] == JsonPrimitive(true)
        // In old records, tool names from before the rename are recorded under their current names, same as at runtime
        val name = tools[e.tool]?.name ?: e.tool
        fun add(kind: ObsKind, text: String, place: Place? = null, authored: String? = null, title: String? = null) =
            l.add(Observation(kind, text, place = place, authored = authored, title = title), e.step, e.atMs)
        fun page() = add(ObsKind.Fact, "$name: url ${r.s("url")}, page title \"${r.s("title").orEmpty()}\"" + (r.s("httpStatus")?.let { ", HTTP status $it" } ?: ""))
        when (tools.verb(e.tool)) {
            Verb.Navigate -> if (ok) page()
            Verb.ReadPage -> if (ok) {
                val links = r.list("links").joinToString("\n") { "link \"${it.s("text")}\" ${it.s("url")}" }
                val images = r.list("images").filter { !it.s("alt").isNullOrBlank() }.joinToString("\n") { "image \"${it.s("alt")}\" ${it.s("url")}" }
                val read = listOf(r.s("text").orEmpty(), links, images).filter { it.isNotBlank() }.joinToString("\n")
                if (read.isNotBlank()) add(ObsKind.Read, "page text (${r.s("url")}, from character ${a.s("offset") ?: "0"})\n$read", r.s("url")?.let(Place::page), title = r.s("title"))
                page()
            }
            Verb.Tabs -> if (ok) {
                val tabs = r.list("tabs").ifEmpty { listOf(r) }.filter { it.s("title") != null }
                if (tabs.isNotEmpty()) add(ObsKind.Fact, tabs.joinToString("\n", "$name:\n") { "tab ${it.s("tabId")}: url ${it.s("url")}, page title \"${it.s("title")}\"" })
            }
            Verb.EvaluateJs -> if (ok) r.s("result")?.let { add(ObsKind.Read, "page data script result (${site ?: "current page"})\n$it", site?.let(Place::site)) }
            Verb.SiteCommand -> if (ok) r.s("result")?.let { add(ObsKind.Read, "site command ${a.s("name")} result (${site ?: "current page"})\n$it", site?.let(Place::site)) }
            Verb.Script -> r.s("result")?.takeIf { it != "null" }?.let { add(ObsKind.Read, "page script result (${site ?: "current page"})\n$it", site?.let(Place::site)) }
            Verb.Downloads -> if (ok) r.list("downloads").takeIf { it.isNotEmpty() }?.let { list ->
                add(ObsKind.Read, list.joinToString("\n", "download list (file names come from the page):\n") { d ->
                    listOf("file", "status", "bytes", "error").mapNotNull { k -> d.s(k)?.let { "$k=$it" } }.joinToString(" ")
                })
            }
            Verb.Capture -> if (ok) r.s("body")?.let { add(ObsKind.Read, "network response (${r.s("url")}, HTTP ${r.s("status")})\n$it", r.s("url")?.let(Place::page)) }
            Verb.RunCommand -> r.s("output")?.let { add(ObsKind.Output, "exit code ${r.s("exitCode")}:\n$it", Place.command("run_command \"${a.s("command")?.take(200)}\""), a.s("command")) }
            Verb.RunBundledScript -> r.s("output")?.let { add(ObsKind.Output, "exit code ${r.s("exitCode")}:\n$it", Place.command("run_bundled_script ${a.s("source") ?: a.s("skill")}/${a.s("script")}"), a["args"]?.toString()) }
            Verb.ReadFile -> if (ok && r.s("path") !in written) r.s("content")?.let { add(ObsKind.Output, it, Place.file("read_file ${r.s("path")}")) }
            Verb.WriteFile -> if (ok) {
                r.s("path")?.let { written += it }
                add(ObsKind.Output, "write_file wrote ${r.s("path")} (${r.s("bytesWritten")} bytes; the file is now ${r.s("size")} bytes)")
            }
            Verb.SearchFiles -> if (ok) r.list("matches").let { m ->
                add(ObsKind.Output, m.joinToString("\n") { "${it.s("file")}:${it.s("line")}: ${it.s("text")}" }, Place.command("search_files \"${a.s("pattern")?.take(100)}\""))
            }
            Verb.ShareFile -> if (ok) add(ObsKind.Output, "share_file saved to ${r.s("savedAs")}")
            Verb.ListApps -> if (ok) r.list("apps").forEach { app -> app.s("package")?.let { p -> app.s("label")?.let { labels[p] = it } } }
            Verb.LaunchApp -> if (ok) a.s("package")?.let { p -> add(ObsKind.Fact, "$name opened $p" + (labels[p]?.let { " (app name \"$it\")" } ?: "")) }
            Verb.ShareText -> if (ok) a.s("package")?.let { p -> add(ObsKind.Fact, "$name opened the share sheet of $p (not sent yet)" + (labels[p]?.let { " (app name \"$it\")" } ?: "")) }
            else -> Unit
        }
    }

    private const val TAKEOVER_STATE = "waiting_takeover"
    private val PAGE_CHANGING = setOf(Verb.Tap, Verb.Back, Verb.PressKey, Verb.TypeText, Verb.Swipe, Verb.Interact, Verb.Script, Verb.OpenLink, Verb.LaunchApp, Verb.ShareText,
        Verb.ShowAlarms, Verb.AddCalendarEvent, Verb.SetWheel)

    private fun obj(s: String): JsonObject? = runCatching { Json.parseToJsonElement(s) as? JsonObject }.getOrNull()
    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.list(k: String): List<JsonObject> = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
}
