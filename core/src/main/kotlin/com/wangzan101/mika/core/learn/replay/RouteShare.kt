package com.wangzan101.mika.core.learn.replay

import com.wangzan101.mika.core.tool.ToolIndex
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/**
 * A route being shared out: the local route as it is ([route]), plus a human-readable title and summary.
 * The route format itself carries no personal data (see [RouteAnchors]: input fields store only the hint text, the
 * variable parts of the utterance are parameterized into slots, and anchors exclude numbers and amounts); before
 * sharing, the UI shows the whole content to the user for confirmation; on import it goes through [RouteShareLint]
 * first, so anything that can be rejected at import time (unknown or non-replayable tools, fingerprint mismatch, over
 * the limits) is not left until run time.
 */
@Serializable
data class RouteShare(
    /** The version of the sharing format; import accepts only versions it recognizes. */
    val schema: Int = SCHEMA,
    val route: Route,
    val title: String,
    val summary: String = "",
    /**
     * The path fingerprint computed by [RouteFingerprint]: the publishing side deduplicates by it (no need to upload
     * again if someone has already shared it), and the importing side checks that the content has not been altered.
     */
    val fingerprint: String,
) {
    init {
        require(schema == SCHEMA) { "unsupported route share schema: $schema" }
    }

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        const val SCHEMA = 1
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun of(route: Route, title: String, summary: String = "") =
            RouteShare(route = route, title = title, summary = summary, fingerprint = RouteFingerprint.of(route))

        fun decode(s: String): RouteShare = json.decodeFromString(serializer(), s)
    }
}

/**
 * Path fingerprint: a hash of the [samePath] semantics — each step's tool, arguments and target, plus the template,
 * the slot count and the apps involved.
 * Anchors are not part of the fingerprint (each screen read differs slightly and that does not count as a different
 * path; consistent with the trade-off in [samePath]), so the same route recorded on different devices or systems gets
 * the same fingerprint, and publishers deduplicate by it. The encoding is hard-coded field by field instead of relying
 * on the data class's toString, keeping it stable across versions.
 */
object RouteFingerprint {
    fun of(r: Route): String {
        val d = MessageDigest.getInstance("SHA-256")
        fun line(vararg parts: String) {
            d.update(parts.joinToString("\u0000").toByteArray())
            d.update(LINE)
        }
        line(*r.packages.sorted().toTypedArray())
        line(r.template, r.slots.toString())
        r.steps.forEach { s -> line(s.tool, s.argsJson, s.target?.encoded().orEmpty()) }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    private fun Selector.encoded(): String =
        listOf(role, name, resourceId.orEmpty(), bounds.joinToString(","), ordinal.toString(), uniqueAtRecord.toString())
            .joinToString("\u0000")

    private val LINE = byteArrayOf(0x0a)
}

/**
 * Import validation: structural hard constraints are rejected here; soft ones (anchors no longer matching because the
 * UI was redesigned) are handled by the mismatch fallback at run time and by version evolution where "whoever gets it
 * working updates it", and are not blocked here. An empty problem list means the share can be accepted.
 */
object RouteShareLint {
    fun check(share: RouteShare, tools: ToolIndex): List<String> {
        val problems = mutableListOf<String>()
        val r = share.route
        if (share.title.isBlank()) problems += "title is blank"
        if (r.template.isBlank()) problems += "template is blank"
        if (r.slots < 0) problems += "negative slot count"
        if (r.disabled) problems += "route is disabled"
        if (r.steps.size > MAX_ROUTE_STEPS) problems += "too many steps: ${r.steps.size} > $MAX_ROUTE_STEPS"
        if (share.encode().toByteArray(Charsets.UTF_8).size > MAX_ROUTE_BYTES) problems += "share exceeds $MAX_ROUTE_BYTES bytes"
        if (RouteFingerprint.of(r) != share.fingerprint) problems += "fingerprint does not match content"
        r.steps.forEachIndexed { i, s ->
            val info = tools[s.tool]
            when {
                info == null -> problems += "step ${i + 1}: unknown tool ${s.tool}"
                !info.routable -> problems += "step ${i + 1}: ${s.tool} is not a replayable action"
            }
            if (jsonObject(s.argsJson) == null) problems += "step ${i + 1}: args are not a JSON object"
        }
        return problems
    }

    /**
     * The apps the route will actually touch (the package names recorded in the anchors): when reviewing "is this
     * really the declared task", the apps written in the declaration must cover them, and a step that reaches an app
     * outside the declaration should be blocked. The package names of the screens both before and after each action
     * count (the same as the anchor package names kept in [Route.packages] when recording a route): when a tap jumps
     * into another app, that app appears only in that step's after (there is no next step's before after the last step).
     */
    fun touchedPackages(share: RouteShare): Set<String> =
        share.route.steps.flatMap { listOfNotNull(it.before.packageName, it.after.packageName) }.toSet()

    private val json = Json { ignoreUnknownKeys = true }

    private fun jsonObject(s: String): JsonObject? = try {
        Json.parseToJsonElement(s.ifBlank { "{}" }) as? JsonObject
    } catch (e: SerializationException) {
        null
    }
}
