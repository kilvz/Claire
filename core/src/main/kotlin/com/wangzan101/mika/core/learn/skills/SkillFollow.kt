package com.wangzan101.mika.core.learn.skills

import com.wangzan101.mika.core.learn.knowledge.SiteMatch
import com.wangzan101.mika.core.learn.knowledge.SkillPlaces
import com.wangzan101.mika.core.screen.observation.Place
import com.wangzan101.mika.core.screen.observation.PlaceKind
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.tool.parseArgs
import com.wangzan101.mika.core.tool.placeAfter
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject

/**
 * Which personal skills were actually followed in this run. Reading one with `skill_read` only means it was looked at; the result must be recorded on the skill that was really followed (#569):
 *
 * 1. Something was successfully done after the read (tap, type, open an app, open a web page, run a command..., see [ACTION_VERBS]); answering or giving up right after reading does not count.
 * 2. When the skill's front matter names an app or a site, the result of the same successful action must confirm that it was executed in one of those places.
 *
 * Everything is judged by what actually happened in the run record, not by what the model claims. Prefer under-counting: a miss only means it is not recorded this time, while a false match could get a good skill suspended.
 */
object SkillFollow {
    /** [places] gives the apps and sites a skill declares; it is null when the skill is not known, which counts as declaring nothing. */
    fun followed(events: List<RunEvent>, used: List<RunEvent.SkillUsed>, tools: ToolIndex, places: (String) -> SkillPlaces?): Set<String> =
        used.groupBy { it.name }.filter { (name, reads) ->
            val readAt = reads.minOf { it.atMs }
            actedAfter(events, readAt, tools, places(name) ?: SkillPlaces())
        }.keys

    private fun actedAfter(events: List<RunEvent>, at: Long, tools: ToolIndex, places: SkillPlaces): Boolean {
        val domains = places.domains.toList()
        return events.filterIsInstance<RunEvent.ToolResult>().any { result ->
            val verb = tools.verb(result.tool)
            if (result.atMs <= at || verb !in ACTION_VERBS) return@any false
            val content = parseArgs(result.resultJson)
            if (content["ok"] != JsonPrimitive(true)) return@any false
            if (!places.declared) return@any true
            val place = actionPlace(verb, content) ?: return@any false
            when (place.kind) {
                PlaceKind.App -> place.name in places.packages
                PlaceKind.Site -> SiteMatch.score(place.name, domains) != null
                else -> false
            }
        }
    }

    private fun actionPlace(verb: Verb?, content: JsonObject): Place? {
        content.argStr("actionApp")?.let { return Place.app(it) }
        content.argStr("actionSite")?.let { return Place.site(it) }
        if (verb == Verb.LaunchApp) {
            val opened = content["opened"] as? JsonObject
            opened?.takeUnless { it["picker"] == JsonPrimitive(true) || it["needsUser"] == JsonPrimitive(true) }
                ?.argStr("package")?.let { return Place.app(it) }
        }
        if (verb != Verb.LaunchApp && verb != Verb.Navigate &&
            (content["effect"] as? JsonObject)?.argStr("kind") == "place_changed") return null
        return placeAfter(verb, content)
    }
}
