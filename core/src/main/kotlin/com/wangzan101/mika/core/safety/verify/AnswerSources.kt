package com.wangzan101.mika.core.safety.verify

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * One source of an answer: points back to an observation in the evidence ledger ([obsId], see
 * [com.wangzan101.mika.core.screen.observation.Observation.id]), whatever it is (an interface, a page, a file, a piece of
 * command output, a reply).
 * [kind] is the observation kind ([com.wangzan101.mika.core.screen.observation.ObsKind.wire]; older records use
 * screen / web / app_ai / output / fact); [source] is the capability that produced it (absent in older records); [place] is
 * where it was read (app package name, "site domain" or URL, command or file) and [title] is that page's own title;
 * [step] is which step it was read at; [mediaIds] are the image ids stored for that step (empty when none).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AnswerSource(
    @JsonNames("entryId") val obsId: Int, val kind: String, val place: String?, val title: String?, val step: Int,
    val mediaIds: List<Int> = emptyList(), val source: String? = null,
)

/** Answer sources are found by code, not written by the model: the observations in the evidence ledger whose facts match the answer. */
object AnswerSources {
    /** List at most this many sources. */
    const val MAX = 6

    /**
     * [media] maps each step to the image ids stored for it (step → ids). When the answer has facts that match, those
     * observations are listed; when the answer has no checkable fact ("Bluetooth is now on"), the last observation is used,
     * since that is where the result the answer describes lives. Empty when nothing was read.
     */
    fun of(ledger: EvidenceLedger, goal: String, answer: String, media: Map<Int, List<Int>>): List<AnswerSource> {
        val hits = ledger.supporting(goal, answer).ifEmpty { listOfNotNull(ledger.all.lastOrNull()) }
        return hits.map { AnswerSource(it.id, it.kind.wire, it.where, it.title, it.step, media[it.step].orEmpty(), it.source) }
            .takeLast(MAX)
    }
}
