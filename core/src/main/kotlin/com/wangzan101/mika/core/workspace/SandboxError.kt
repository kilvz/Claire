package com.wangzan101.mika.core.workspace

/** A known error reported by a sandbox implementation to the tool layer; [code] becomes the tool result's error verbatim (e.g. sandbox_network_isolation_unavailable). */
class SandboxError(val code: String, message: String, val recoverable: Boolean = false) : Exception(message)
