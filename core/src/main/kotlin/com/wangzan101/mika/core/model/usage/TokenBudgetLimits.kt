package com.wangzan101.mika.core.model.usage

/** The token budget range a user can set; null at the settings layer means unlimited. */
object TokenBudgetLimits {
    const val MIN = 10_000L
    const val MAX = 100_000_000L
    val PRESETS = listOf(500_000L, 1_000_000L, 5_000_000L, 10_000_000L)

    fun valid(value: Long): Boolean = value in MIN..MAX
}
