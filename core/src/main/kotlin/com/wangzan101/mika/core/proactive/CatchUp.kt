package com.wangzan101.mika.core.proactive

/**
 * Catch-up after unlock: of the notifications still sitting in the notification shade, only those posted after locking (and after the feature was turned on) are taken.
 * Those posted before locking were already received in real time while unlocked; none of the backlog that existed before the feature was turned on is taken.
 */
object CatchUp {
    fun select(active: List<NoticeRecord>, enabledAtMs: Long, lastLockedAtMs: Long): List<NoticeRecord> {
        val since = maxOf(enabledAtMs, lastLockedAtMs)
        return active.filter { it.postedAtMs > since }.sortedBy { it.postedAtMs }
    }
}
