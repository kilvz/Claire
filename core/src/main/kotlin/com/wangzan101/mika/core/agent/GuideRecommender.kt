package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.learn.knowledge.KNOWLEDGE_KINDS
import com.wangzan101.mika.core.learn.knowledge.KnowledgeIndex
import com.wangzan101.mika.core.learn.knowledge.PERSONAL_KIND
import com.wangzan101.mika.core.learn.knowledge.RECOMMEND_MIN_SCORE
import com.wangzan101.mika.core.learn.knowledge.SKILL_KINDS
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.learn.knowledge.GuideInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.wangzan101.mika.core.tool.Verb

internal const val SKILL_INDEX_HEADER = "All skills you have, one line each (when one fits this task, read its full text with skill_read before acting on it):"
internal const val KNOWLEDGE_INDEX_HEADER = "App and website knowledge you can refer to (read the full text with knowledge_read when needed):"
private const val RECOMMEND_COUNT = 3

/**
 * The index at task start (written once into the opening context, not changed mid-task):
 * app/site knowledge is retrieved by the task text, using the previous approach (take the top 3 together with skills,
 * then keep only knowledge);
 * skills are not filtered — all are listed: built-in first, personal skills after, each sorted by name, one line per
 * entry (id, display name, description). The model decides from the descriptions whether and which to use, and reads
 * the full text with skill_read when it wants to use one.
 */
internal object GuideRecommender {
    fun id(info: GuideInfo) = "${info.kind}:${info.name}"
    fun line(info: GuideInfo) = "- ${id(info)}(${info.display}):${info.description}"

    /** Knowledge and skills are each listed under their own header; null when there is neither. Personal skills only include those in use and on trial (see [GuideCatalog.items]). */
    fun index(catalog: GuideCatalog, task: String): String? {
        val knowledge = catalog.find(task, limit = RECOMMEND_COUNT, minScore = RECOMMEND_MIN_SCORE).filter { it.kind in KNOWLEDGE_KINDS }
        val skills = catalog.items.filter { it.kind in SKILL_KINDS }.sortedWith(compareBy({ it.kind != KnowledgeIndex.SKILL }, { it.name }))
        return listOf(KNOWLEDGE_INDEX_HEADER to knowledge, SKILL_INDEX_HEADER to skills).filter { it.second.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { (header, items) -> header + "\n" + items.joinToString("\n", transform = ::line) }
    }

    /** Returns the skill name when skill_read successfully read a personal skill. */
    fun viewedPersonal(verb: Verb?, content: JsonObject): String? {
        if (verb != Verb.SkillRead || content["ok"] != JsonPrimitive(true)) return null
        if ((content["kind"] as? JsonPrimitive)?.contentOrNull != PERSONAL_KIND) return null
        return (content["name"] as? JsonPrimitive)?.contentOrNull
    }
}
