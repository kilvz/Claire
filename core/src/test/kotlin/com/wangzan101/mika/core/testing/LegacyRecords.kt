package com.wangzan101.mika.core.testing

import com.wangzan101.mika.core.run.record.RunEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Test helper: one screen_text entry from a legacy run record (the element text handed to the model when reading a screen),
 * decoded through the same path used for old records (the result is an Observed numbered 0).
 */
fun legacyScreenText(
    atMs: Long, step: Int, app: String?, names: List<String>, site: String? = null, inputs: List<String>? = null, focused: String? = null,
): RunEvent.Observed = RunEvent.decode(buildJsonObject {
    put("type", "screen_text"); put("atMs", atMs); put("step", step)
    app?.let { put("app", it) }
    put("names", JsonArray(names.map(::JsonPrimitive)))
    site?.let { put("site", it) }
    inputs?.let { put("inputs", JsonArray(it.map(::JsonPrimitive))) }
    focused?.let { put("focused", it) }
}.toString()) as RunEvent.Observed
