package com.wangzan101.mika.core.safety.gate

import com.wangzan101.mika.core.safety.gate.GateTarget
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.serialization.json.JsonObject
import com.wangzan101.mika.core.assembly.toolDispatcher

/** Judges by tool name in tests: the target is the one the tool declares (the same one the execution entry passes to the detector). */
private val declarations by lazy { toolDispatcher(FakeScreen()) }

internal fun gateTargetOf(tool: String): GateTarget? = declarations.gateTarget(tool)

internal fun IrreversibleDetector.needsCheck(tool: String, args: JsonObject) = needsCheck(gateTargetOf(tool), args)

internal fun IrreversibleDetector.classify(tool: String, args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot? = null) =
    classify(gateTargetOf(tool), args, snapshot, fresh)

internal fun IrreversibleDetector.unresolved(tool: String, args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot? = null) =
    unresolved(gateTargetOf(tool), args, snapshot, fresh)
