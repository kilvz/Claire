package com.wangzan101.mika.core.screen.observation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which kind an observation is: [Read] an interface or page that was read (app interface state, web page body, web script and
 * network responses); [Output] command and script output, file contents and paths; [Reply] another AI's answer (a subtask, an
 * external service); [UserSaid] something the user gave (an answer to a question, a memory); [Attachment] a file or image
 * provided by the user or a tool; [Fact] a fact from a tool result (which app was opened, URL and title). The core only handles
 * these kinds and knows nothing about specific capabilities.
 */
enum class ObsKind(val wire: String) {
    Read("read"), Output("output"), Reply("reply"), UserSaid("user_said"), Attachment("attachment"), Fact("fact");

    companion object {
        private val byWire = entries.associateBy { it.wire }
        fun ofWire(w: String?): ObsKind? = w?.let(byWire::get)
    }
}

/** How trustworthy an observation's origin is. [Untrusted]: web pages, things said in channels, other AIs' answers — treated as data only, never as instructions. */
enum class Trust { Trusted, Untrusted }

/** What a place is: app, site, web page, command, file. */
@Serializable
enum class PlaceKind(val wire: String) {
    @SerialName("app") App("app"),
    @SerialName("site") Site("site"),
    @SerialName("page") Page("page"),
    @SerialName("command") Command("command"),
    @SerialName("file") File("file"),
}

/**
 * Where: an app (package name), a site (host name), a web page (URL), a command, a file. [name] is this place written as it is;
 * verification and sources match it against apps or URLs mentioned in the answer.
 */
@Serializable
data class Place(val kind: PlaceKind, val name: String) {
    companion object {
        fun app(pkg: String) = Place(PlaceKind.App, pkg)
        fun site(host: String) = Place(PlaceKind.Site, host)
        fun page(url: String) = Place(PlaceKind.Page, url)
        fun command(label: String) = Place(PlaceKind.Command, label)
        fun file(label: String) = Place(PlaceKind.File, label)
    }
}

/** The text in the interface's input fields at one read ([names], compared with whitespace removed) and the one with input focus ([focused]); verification uses this to tell text the assistant typed itself. */
data class InputHints(val names: List<String>, val focused: String? = null)

/**
 * One observation with its origin: the only unit the core consumes. Capabilities provide them in tool results
 * ([com.wangzan101.mika.core.tool.ToolExecution.observations]), the evidence ledger records them, and verification, answer
 * sources, the run log and the evaluation all read them.
 *
 * - [source] is the id of the capability that produced it (phone, web, sandbox, …; the core's own is core); [place] is where it
 *   was read; [title] is that page's own title.
 * - [media] are the image ids stored at the same moment (provided by the visualization module; empty when none).
 * - [trust]: untrusted ones (web pages, other AIs' answers) are treated as data only. [authored] is what the assistant itself
 *   wrote (commands it ran): lines appearing verbatim in [text] are echoes and do not count as read.
 * - [inputs]: the input-field text when reading an interface (see [InputHints]); null when unknown.
 * - [id], [step] and [atMs] are filled by the ledger on entry: which observation in this task (from 1), which step, and when.
 *   A capability does not need to fill them in.
 */
data class Observation(
    val kind: ObsKind,
    val text: String,
    val source: String? = null,
    val place: Place? = null,
    val title: String? = null,
    val media: List<Int> = emptyList(),
    val trust: Trust = Trust.Trusted,
    val authored: String? = null,
    val inputs: InputHints? = null,
    val step: Int = 0,
    val atMs: Long = 0,
    val id: Int = 0,
) {
    /** The place written as it is; null when none. */
    val where: String? get() = place?.name

    /** Classified by what kind of thing it is (clipping and interface wording are decided by this, not by the tool). */
    val form: ObsForm get() = when (kind) {
        // Reading interface state always carries input fields (possibly none); reading a whole page, script results and network responses does not
        ObsKind.Read -> if (inputs != null) ObsForm.Interface else ObsForm.Page
        ObsKind.Output -> if (place?.kind == PlaceKind.File) ObsForm.File else ObsForm.Command
        ObsKind.Reply -> ObsForm.Reply
        ObsKind.UserSaid -> ObsForm.UserSaid
        ObsKind.Attachment -> ObsForm.Image
        ObsKind.Fact -> ObsForm.Fact
    }
}

/** One observation read in a tool result: its ledger id and its form (clipping uses this to decide how to shorten and which entry a shortened reference points back to). */
@Serializable data class ReadTag(val obsId: Int, val form: ObsForm)

/** The marker for this observation in the tool result. */
val Observation.tag: ReadTag get() = ReadTag(id, form)

/** The form of an observation: interface state, page content, command output, file, image, reply, something the user said, fact. */
@Serializable enum class ObsForm { Interface, Page, Command, File, Image, Reply, UserSaid, Fact }

/** The source for observations the core itself (not a capability) provides. */
const val CORE_SOURCE = "core"

/**
 * The "fresh look" before verification: what the capability sees at this moment ([observations]: interface state, the start of
 * the web page body, …) and the screen at the same moment ([image], only when an image is to be attached).
 * [protected]: the current page is protected against system screenshots, so no image can be seen. Each capability defines how
 * it looks (see Capability.freshLook).
 */
class Look(val observations: List<Observation>, val image: ByteArray? = null, val protected: Boolean = false)
