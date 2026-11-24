package com.wangzan101.mika.core.safety.verify

import com.wangzan101.mika.core.screen.observation.InputHints
import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.Observation
import com.wangzan101.mika.core.screen.observation.Place

/** For tests: records the given screen reads, command outputs and tool facts into one ledger in that order (steps are numbered sequentially). */
fun ledgerOf(screens: List<String> = emptyList(), outputs: List<String> = emptyList(), facts: List<String> = emptyList()): EvidenceLedger =
    EvidenceLedger().also { l ->
        var step = 0
        screens.forEach { l.add(++step, ObsKind.Read, null, 0, it) }
        outputs.forEach { l.add(++step, ObsKind.Output, null, 0, it) }
        facts.forEach { l.add(++step, ObsKind.Fact, null, 0, it) }
    }

/** In tests a URL or a package name stands for the place: anything starting with http is a web page, the rest is an app. */
fun placeOf(where: String?): Place? = where?.let { if (it.startsWith("http")) Place.page(it) else Place.app(it) }

/** Test shorthand: step [step] read [text] at [where]. */
fun EvidenceLedger.add(
    step: Int, kind: ObsKind, where: String?, atMs: Long, text: String,
    inputs: List<String>? = null, focused: String? = null, authored: String? = null, title: String? = null,
): Observation? = add(obs(step, kind, where, text, inputs, focused, authored, title), step, atMs)

/** For tests: a single observation. */
fun obs(
    step: Int, kind: ObsKind, where: String?, text: String,
    inputs: List<String>? = null, focused: String? = null, authored: String? = null, title: String? = null,
) = Observation(kind, text, place = placeOf(where), title = title, authored = authored, inputs = inputs?.let { InputHints(it, focused) }, step = step)

/** For tests: the text seen on a fresh look (an empty string means nothing was read either). */
fun freshOf(text: String): List<Observation> = if (text.isEmpty()) emptyList() else listOf(Observation(ObsKind.Read, text, inputs = InputHints(emptyList())))
