package com.wangzan101.mika.proactive

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.wangzan101.mika.MikaApp

/**
 * The proactive capability's notification listener. The component is disabled by default and only enabled by [ProactiveRuntime] once the
 * feature is on (only then does Claire appear in the system's "notification access" list). All this does is forward system callbacks to
 * [ProactiveRuntime]; before it connects it does nothing.
 */
class NoticeListenerService : NotificationListenerService() {
    private val runtime get() = (application as MikaApp).proactive

    override fun onListenerConnected() {
        runtime.onConnected(this)
    }

    override fun onListenerDisconnected() {
        // After disconnecting, no method of this listener may be called; from then on only [ProactiveRuntime] can ask the system to rebind via the static requestRebind
        runtime.onDisconnected(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        sbn?.let(runtime::onPosted)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        // The user opened, dismissed or cleared: it has been seen, remove it from the inbox; an App withdrawing it, a timeout and the like do not count
        if (sbn != null && reason in SEEN_REASONS) runtime.onSeen(sbn.key, reason)
    }

    /** Ask the system to unbind; only valid while connected, calling it otherwise throws, and that is swallowed here. Returns whether the request went out. */
    fun unbindQuietly(): Boolean = runCatching { requestUnbind() }.isSuccess

    /**
     * The notifications currently in the shade; null when unreadable — the system may return null or throw (not connected yet, just
     * disconnected). Unreadable must be kept apart from "the shade is empty": for the former, this catch-up capture did nothing and must be retried.
     */
    fun activeOrNull(): Array<StatusBarNotification>? = runCatching { activeNotifications }.getOrNull()

    /** The importance the system assigned to this notification (NotificationManager.IMPORTANCE_*); null when unreadable (not connected yet, or the system did not provide it). */
    fun importanceOf(key: String): Int? = runCatching {
        val ranking = Ranking()
        if (currentRanking.getRanking(key, ranking)) ranking.importance else null
    }.getOrNull()

    companion object {
        val SEEN_REASONS = setOf(REASON_CLICK, REASON_CANCEL, REASON_CANCEL_ALL)
    }
}
