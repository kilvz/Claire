package com.wangzan101.mika.core.agent

/** The explicit all-off value for the speed flags; the App's initial defaults are decided by FeatureFlags. */
data class SpeedFlags(
    val inlineScreen: Boolean = false,
    val batchSteps: Boolean = false,
    val replay: Boolean = false,
    val deepLinks: Boolean = false,
    val lightSteps: Boolean = false,
    val parallelTasks: Boolean = false,
    /** During compaction, write the summary as four structured sections and calibrate the estimate with real usage (changes what the model sees; decide after an evaluation comparison). */
    val structuredCompaction: Boolean = false,
    /** Elements first; give the primary model screen frames only when reading is unclear or the model explicitly asks. */
    val adaptiveVision: Boolean = false,
    val tablesAtBirth: Boolean = false,
) {
    private fun on(): Map<String, Boolean> = mapOf(
        "inlineScreen" to inlineScreen, "batchSteps" to batchSteps,
        "replay" to replay, "deepLinks" to deepLinks, "lightSteps" to lightSteps, "parallelTasks" to parallelTasks,
        "structuredCompaction" to structuredCompaction,
        "adaptiveVision" to adaptiveVision, "tablesAtBirth" to tablesAtBirth,
    )

    /** Names of the enabled flags joined by commas in alphabetical order; "none" when all are off. [parse] can read it back. */
    fun describe(): String = on().filterValues { it }.keys.sorted().joinToString(",").ifEmpty { NONE }

    companion object {
        private const val NONE = "none"
        val OFF = SpeedFlags()
        private val KEYS = OFF.on().keys
        /** Flags that were promoted or removed: stored settings and evaluation parameters may still contain them, so they are skipped when read. */
        private val RETIRED = setOf("cacheFriendlyPrune")

        /** "none" (or empty) = all off; otherwise comma-separated `key=on|off`, or just the key name (= on). An unknown key or value throws [IllegalArgumentException]. */
        fun parse(spec: String): SpeedFlags {
            val s = spec.trim()
            if (s.isEmpty() || s == NONE) return OFF
            val set = mutableMapOf<String, Boolean>()
            for (item in s.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                val key = item.substringBefore('=').trim()
                val value = if ('=' in item) item.substringAfter('=').trim() else "on"
                if (key in RETIRED) continue
                require(key in KEYS) { "unknown speed flag: $key" }
                set[key] = when (value) {
                    "on" -> true
                    "off" -> false
                    else -> throw IllegalArgumentException("speed flag $key must be on or off, got $value")
                }
            }
            fun f(k: String) = set[k] ?: false
            return SpeedFlags(f("inlineScreen"), f("batchSteps"), f("replay"), f("deepLinks"), f("lightSteps"), f("parallelTasks"), f("structuredCompaction"), f("adaptiveVision"), f("tablesAtBirth"))
        }
    }
}
