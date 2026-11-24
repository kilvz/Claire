package com.wangzan101.mika.core.safety.guard

import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Screen reads, image views, screen diagnosis and the like: the guard counts repeats per tool, not per argument. */
internal val DIAGNOSTIC_VERBS = setOf(Verb.Observe, Verb.ListElements, Verb.Screenshot, Verb.Describe, Verb.Diagnose, Verb.ViewPastScreens)
/** Read-only page-viewing tools: repeatedly reading with them while the page is unchanged is handled by [OperationGuards.read]. */
internal val PAGE_READ_VERBS = DIAGNOSTIC_VERBS + setOf(Verb.ReadPage, Verb.EvaluateJs, Verb.SiteCommand)

/** Fingerprint of a tool result (without the screen-read id that changes every time). */
internal fun resultFingerprint(content: JsonObject): Long {
    val text = JsonObject(content - "snapshotId" - "guard" - "advice").toString()
    var h = -0x340d631b7bdddcdbL
    for (b in text.toByteArray(Charsets.UTF_8)) h = (h xor (b.toLong() and 0xff)) * 0x100000001b3L
    return h
}

/** Screen fingerprint: a 64-bit FNV-1a of the element-list text. A text-only model without screenshots can still tell that the screen did not change. */
internal fun elementsHash(elements: JsonArray?): Long? {
    elements ?: return null
    // Excludes refs: with stable ids enabled, repeated same-name elements on the same screen get new refs on every read, which should not count as a screen change
    val text = JsonArray(elements.map { e -> (e as? JsonObject)?.let { JsonObject(it - "ref") } ?: e }).toString()
    var h = -0x340d631b7bdddcdbL
    for (b in text.toByteArray(Charsets.UTF_8)) {
        h = (h xor (b.toLong() and 0xff)) * 0x100000001b3L
    }
    return h
}
