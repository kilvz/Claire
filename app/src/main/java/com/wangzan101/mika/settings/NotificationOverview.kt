package com.wangzan101.mika.settings

import android.app.NotificationManager
import com.wangzan101.mika.task.TaskService

/** The current state of one notification channel (only looking at its importance in the system). */
enum class ChannelLevel {
    /** The channel has been turned off (or the master switch is off). */
    Off,
    /** Pops up on the screen. */
    HeadsUp,
    /** Only goes quietly into the notification shade. */
    Quiet,
}

/** One channel row on the notifications page: [warn] true means it differs from what is expected (something that should pop up does not), and it must be flagged. */
data class ChannelRow(val channelId: String, val level: ChannelLevel, val warn: Boolean)

/** Overall notification status, shown to the right of the "Notifications" row in settings. */
sealed interface NotificationSummary {
    data object Normal : NotificationSummary
    /** [count] channels should pop up but do not. */
    data class Lowered(val count: Int) : NotificationSummary
    /** The app's master notification switch is off. */
    data object AllOff : NotificationSummary
}

data class NotificationOverview(
    val appAllowed: Boolean,
    val attention: ChannelRow,
    val result: ChannelRow,
    val progress: ChannelRow,
    val summary: NotificationSummary,
) {
    companion object {
        /**
         * Derives each row's state and the overall state from the master switch and the importance of the three channels. A null importance means the channel has not been created yet, so the level it gets on creation is assumed.
         * "Needs your attention" and "Task results" are supposed to pop up, so anything other than "pops up" is flagged (being off counts too); "Task progress" being quiet or off does not affect tasks, so it is not flagged.
         * When the master switch is off all three rows read "off" and nothing is flagged (the problem is the master switch).
         */
        fun of(appAllowed: Boolean, attention: Int?, result: Int?, progress: Int?): NotificationOverview {
            fun row(id: String, importance: Int?, shouldPopUp: Boolean): ChannelRow {
                val level = if (!appAllowed) ChannelLevel.Off else levelOf(importance ?: initialImportance(id))
                return ChannelRow(id, level, warn = appAllowed && shouldPopUp && level != ChannelLevel.HeadsUp)
            }
            val a = row(TaskService.CHANNEL_ATTENTION, attention, shouldPopUp = true)
            val r = row(TaskService.CHANNEL_RESULT, result, shouldPopUp = true)
            val p = row(TaskService.CHANNEL_PROGRESS, progress, shouldPopUp = false)
            val lowered = listOf(a, r, p).count { it.warn }
            val summary = when {
                !appAllowed -> NotificationSummary.AllOff
                lowered > 0 -> NotificationSummary.Lowered(lowered)
                else -> NotificationSummary.Normal
            }
            return NotificationOverview(appAllowed, a, r, p, summary)
        }

        fun levelOf(importance: Int): ChannelLevel = when {
            importance == NotificationManager.IMPORTANCE_NONE -> ChannelLevel.Off
            importance >= NotificationManager.IMPORTANCE_HIGH -> ChannelLevel.HeadsUp
            else -> ChannelLevel.Quiet
        }

        private fun initialImportance(id: String): Int =
            TaskService.CHANNEL_IMPORTANCE[id] ?: NotificationManager.IMPORTANCE_DEFAULT
    }
}
