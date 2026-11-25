package com.wangzan101.mika.core.safety.gate

/** The target app cannot be restored to Claire's screen; hand the reason to the model. */
class TargetAppRestoreFailed(val packageName: String, message: String) : Exception(message) {
    internal companion object { const val CODE = "target_app_restore_failed" }
}
