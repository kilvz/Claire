package com.wangzan101.mika.settings

import com.wangzan101.mika.core.workspace.AtomicFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Ask / autonomous (read when a new task starts), stored as a small JSON file; anything unreadable counts as ask.
 * It writes a temp file and then renames it, so a power loss halfway through a write leaves no corrupt file behind.
 */
class ApprovalStore(private val file: File) {
    private val _autonomous: MutableStateFlow<Boolean>

    init {
        val o = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()
        _autonomous = MutableStateFlow((o?.get("autonomous") as? JsonPrimitive)?.booleanOrNull ?: false)
    }

    /** Autonomous mode: when on, even payments are not asked about first (in ask mode only payments are asked about first). To change it go through [ApprovalControl.setAutonomous], so that running tasks follow. */
    val autonomous: StateFlow<Boolean> = _autonomous.asStateFlow()

    @Synchronized fun setAutonomous(on: Boolean) {
        _autonomous.value = on
        save()
    }

    @Synchronized private fun save() {
        file.parentFile?.mkdirs()
        AtomicFiles.write(file, buildJsonObject { put("autonomous", _autonomous.value) }.toString(), durable = false)
    }
}
