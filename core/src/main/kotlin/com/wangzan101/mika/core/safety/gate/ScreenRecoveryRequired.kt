package com.wangzan101.mika.core.safety.gate

/** A screen-lock cycle happened during the wait: prepared write actions were planned against the old screen, so re-read the current screen before deciding what to do. */
class ScreenRecoveryRequired : Exception()
