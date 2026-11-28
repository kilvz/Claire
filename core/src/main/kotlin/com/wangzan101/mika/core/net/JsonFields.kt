package com.wangzan101.mika.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Returns the field when it is a JSON string; numbers, booleans, null, objects and arrays all count as absent. */
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Returns the field's text when it is any JSON primitive (numbers and booleans too, without checking whether it is a string); null, objects and arrays count as absent. */
internal fun JsonObject.scalar(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** Parses into a JSON object with this [Json] configuration; returns null when it is not valid JSON or the top level is not an object. */
internal fun Json.jsonObjectOrNull(text: String): JsonObject? =
    try { parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null }
