package com.wangzan101.mika.core.tool

/**
 * The pass for executing a tool that changes the outside world: [ToolDispatcher.execute] requires it. The constructor is internal, so the app module cannot create one;
 * in core only the execution entry point (core.action.ActionExecutor) creates it, guarded by SingleEntryTest. Read-only calls go through [ToolDispatcher.executeRead].
 */
class ActionPass internal constructor()
