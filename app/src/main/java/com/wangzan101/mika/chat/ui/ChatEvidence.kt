package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.core.run.record.RunEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The answer and evidence text on result cards comes only from here. New answers carry no evidence section (code finds the source from the evidence ledger, see [evidenceSource]);
 * in old run records and conversation files the model's own trailing "Evidence:" section is still split out as before. When [evidence] is given it is used directly.
 */
fun answerParts(answer: String, evidence: String? = null): Pair<String, String?> =
    evidence?.trim()?.takeIf { it.isNotEmpty() }?.let { answer.trim() to it } ?: splitEvidence(answer)

/**
 * Source of the answer: the app or website it is in ([place], null when unknown), the related screenshot ids, and a per-item description ([items], "step 3 · 12306 · train search").
 * Taken from the run record: when there is a source recorded by code (a sources event) it is used; old records use the step where screenshots were last saved.
 */
data class EvidenceSource(val place: String?, val screenshots: List<Int>, val items: List<String> = emptyList())

/** See [EvidenceSource]; [appLabel] turns package names into app names, and websites and URLs show their domain. */
fun evidenceSource(events: List<RunEvent>, appLabel: (String) -> String = { it }): EvidenceSource {
    events.filterIsInstance<RunEvent.Sources>().lastOrNull()?.items?.takeIf { it.isNotEmpty() }?.let { list ->
        fun where(p: String?) = p?.let { placeLabel(it, appLabel) }
        val items = list.map { s -> listOfNotNull(tr(R.string.step_number, s.step), where(s.place), s.title).joinToString(" · ") }
        return EvidenceSource(list.asReversed().firstNotNullOfOrNull { where(it.place) }, list.flatMap { it.mediaIds }.distinct(), items)
    }
    val shots = events.filterIsInstance<RunEvent.Media>()
    val lastStep = shots.maxOfOrNull { it.step } ?: return EvidenceSource(null, emptyList())
    val final = shots.filter { it.step == lastStep }
    val place = shots.lastOrNull { it.place != null }?.place?.let { if (it.startsWith(SITE_PREFIX)) it.removePrefix(SITE_PREFIX) else appLabel(it) }
    return EvidenceSource(place, final.map { it.mediaId }.distinct())
}

/** In run records the "place" of the web screen is written as "website <domain>". */
internal const val SITE_PREFIX = "网站 "

/** The place in a source: "website domain" and URLs show their domain, package names become app names, and anything else (commands, files) stays as is. */
private fun placeLabel(place: String, appLabel: (String) -> String): String = when {
    place.startsWith(SITE_PREFIX) -> place.removePrefix(SITE_PREFIX)
    place.startsWith("http://") || place.startsWith("https://") -> runCatching { java.net.URI(place).host }.getOrNull() ?: place
    com.wangzan101.mika.core.tool.PACKAGE_NAME_RE.matches(place) -> appLabel(place)
    else -> place
}

/** The "source" line: "Source · 12306 · 2 screenshots"; a part is omitted when there is no place or no screenshot. */
fun sourceLabel(source: EvidenceSource?): String = listOfNotNull(
    tr(R.string.source_label), source?.place, source?.screenshots?.size?.takeIf { it > 0 }?.let { trPlural(R.plurals.screenshots, it) },
).joinToString(" · ")

/**
 * Splits an answer into "conclusion" and "evidence". In old run records and conversation files the answer's last section starts with "Evidence:";
 * the model does not always comply, so "proof / source" written after the end of a sentence is recognized too, and everything after it counts as evidence.
 * When nothing is recognized the whole text is the conclusion and no "source" is shown under the answer.
 */
fun splitEvidence(answer: String): Pair<String, String?> {
    val m = EVIDENCE.find(answer) ?: return answer.trim() to null
    val evidence = answer.substring(m.range.last + 1).trim()
    val main = answer.substring(0, m.range.first + m.groupValues[1].length).trim()
    if (evidence.isEmpty() || main.isEmpty()) return answer.trim() to null
    return main to evidence
}

// At a paragraph start or after end-of-sentence punctuation, optionally preceded by markdown prefixes such as **, #, -
private val EVIDENCE = Regex("""(^|\n|[。.!！?？]\s*)[\s*#>\-]*\**(?:依据|证据|来源|Basis|Evidence|Sources|Source)\**\s*[:：]\**""")
