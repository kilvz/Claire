package com.wangzan101.mika.cap.phone.setup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.wangzan101.mika.R
import com.wangzan101.mika.core.device.shell.BuiltinShellFix
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.core.device.shell.PairingChannelHealth
import com.wangzan101.mika.core.device.shell.PairingDiscovery
import com.wangzan101.mika.core.device.shell.PhoneSetupFlow
import com.wangzan101.mika.ui.MainActivity
import com.wangzan101.mika.settings.ui.PhoneSetupCopy
import com.wangzan101.mika.setup.PairingCodeReceiver

/** What the pairing notification should show right now. */
sealed interface PairingNotice {
    /**
     * "Enter the pairing code" with an input field; [error] is the reason the last attempt failed and what to do about it, and the input field stays.
     * When [quiet] is true it is only placed in the notification shade, without sound or pop-up: prepared ahead of time while the pairing window has not been found yet (see [of]).
     */
    data class EnterCode(val port: Int?, val error: String? = null, val quiet: Boolean = false) : PairingNotice
    data object Working : PairingNotice
    data object Done : PairingNotice
    /** Paired but could not connect afterwards: go back to Claire to see what to do. */
    data class Problem(val title: String) : PairingNotice

    companion object {
        /**
         * Used only while the user is pairing ([PairingCoordinator] active). The input field pops up only once the pairing window is found;
         * while still searching, one is placed quietly in the notification shade first (without a port, looked up again when pairing): the system may freeze a backgrounded Claire after switching to system settings,
         * and a notification sent later, once the window is found, could no longer get out, so the user must be able to find it by pulling the shade down;
         * failures during pairing (wrong code, expired, window closed) stay in the notification for re-entry; failures that need a new pairing (pairing expired) also get an input field once the pairing window is found,
         * recognized the same way as when accessibility reads the code (PairingScreen.attempt), and are likewise prepared quietly while still searching; other failures ask the user to return to Claire.
         */
        fun of(status: BuiltinShellStatus, discovery: PairingDiscovery): PairingNotice? {
            val port = (discovery as? PairingDiscovery.Found)?.port
            return when (status) {
                BuiltinShellStatus.Running -> Done
                BuiltinShellStatus.Connecting -> Working
                // 用户暂停了 Mika:不弹配对码通知。
                BuiltinShellStatus.Paused -> null
                is BuiltinShellStatus.Failed -> when {
                    status.reason.duringPairing -> EnterCode(port, PhoneSetupCopy.notifyRetry(status.reason))
                    status.fix == BuiltinShellFix.RePair && port != null -> EnterCode(port)
                    status.fix == BuiltinShellFix.RePair && discovery == PairingDiscovery.Searching -> EnterCode(null, quiet = true)
                    else -> Problem(PhoneSetupCopy.failure(status.reason).title)
                }
                BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff -> when (discovery) {
                    is PairingDiscovery.Found -> EnterCode(discovery.port)
                    PairingDiscovery.Searching -> EnterCode(null, quiet = true)
                    PairingDiscovery.Idle -> null
                }
            }
        }

        /** While accessibility is reading the pairing code, the code-entry notification pops up this much later, by which point pairing has usually succeeded, so it does not intrude. */
        const val AUTO_READ_GRACE_MS = 5_000L
        /** If nobody enters a code after the notification pops up, pop it again after each of these delays; at most [MAX_REALERTS] times, so it does not spam. */
        val REALERT_MS = listOf(8_000L, 20_000L, 40_000L)
        val MAX_REALERTS = REALERT_MS.size

        /** How long to wait before posting [notice]. With accessibility off it pops up as soon as the pairing window is found, with no wait; the quiet one does not wait either (it must get out before the process is frozen). */
        fun delayBefore(notice: PairingNotice, autoRead: Boolean): Long =
            if (notice is EnterCode && autoRead && !notice.quiet) AUTO_READ_GRACE_MS else 0L

        /** After [sent] re-alerts, how long until the next one; null means no more re-alerts. */
        fun realertAfter(notice: PairingNotice, sent: Int): Long? = if (notice is EnterCode && !notice.quiet) REALERT_MS.getOrNull(sent) else null

        /**
         * One evaluation when [PairingCoordinator] follows status updates with a notification: which notice to show now, and whether to stop following after showing it.
         * While a code has been submitted but pairing has not started ([submitted]) it is always "pairing", and following does not end at this point either (the same when the helper was already running, since the status still changes once pairing starts);
         * during "pair again" a stale running status does not count as paired ([PhoneSetupFlow.effectiveStatus]), and following ends only when the helper is running and not re-pairing.
         */
        fun follow(
            status: BuiltinShellStatus,
            discovery: PairingDiscovery,
            repairing: Boolean,
            submitted: Boolean,
            paused: Boolean = false,
        ): Pair<PairingNotice?, Boolean> =
            if (paused) null to true
            else if (submitted) Working to false
            else of(PhoneSetupFlow.effectiveStatus(status, repairing), discovery) to (status == BuiltinShellStatus.Running && !repairing)
    }
}

/** An existing pairing channel in the system; [userSet] means the user changed its importance themselves. */
data class ChannelSnapshot(val id: String, val importance: Int, val userSet: Boolean)

/** What to do with the pairing channel: [Create] makes a new [id] (high importance); [Use] uses the existing [id], with [health] describing its current state. */
sealed interface ChannelPlan {
    val id: String
    val health: PairingChannelHealth

    data class Create(override val id: String) : ChannelPlan {
        override val health: PairingChannelHealth get() = PairingChannelHealth.Ready
    }

    data class Use(override val id: String, override val health: PairingChannelHealth) : ChannelPlan
}

/**
 * Choosing the pairing channel (pure functions, easy to test). An app cannot change a channel's importance once it is created:
 * if an older version created it too low and the user never touched it, recreate it under a new id; what the user lowered or turned off is not changed back, the guide page just points it out.
 */
object PairingChannel {
    /** Channel ids used so far, later ones newer; a fresh install uses the last one directly. */
    val IDS = listOf("pairing_code", "pairing_alert")
    /** Channels from earlier versions; delete them on sight so settings does not gain extra entries. */
    const val LEGACY = "pairing"

    fun plan(existing: List<ChannelSnapshot>): ChannelPlan {
        val current = existing.filter { it.id in IDS }.maxByOrNull { IDS.indexOf(it.id) }
            ?: return ChannelPlan.Create(IDS.last())
        val newer = IDS.getOrNull(IDS.indexOf(current.id) + 1)
        return when {
            current.importance == NotificationManager.IMPORTANCE_NONE -> ChannelPlan.Use(current.id, PairingChannelHealth.Off)
            current.importance >= NotificationManager.IMPORTANCE_HIGH -> ChannelPlan.Use(current.id, PairingChannelHealth.Ready)
            current.userSet -> ChannelPlan.Use(current.id, PairingChannelHealth.Lowered)
            newer != null -> ChannelPlan.Create(newer)
            else -> ChannelPlan.Use(current.id, PairingChannelHealth.Lowered)
        }
    }
}

/**
 * The pairing notification: high importance, with sound and vibration (deferring to the system in Do Not Disturb), popping up above the pairing window in system settings;
 * the input field uses RemoteInput so the user does not have to leave the pairing window. The entered code goes to [PairingCodeInput].
 */
object PairingNotifier {
    const val KEY_CODE = "pairing_code"
    const val EXTRA_PORT = "pairing_port"
    private const val ID = 4401
    private const val DONE_TIMEOUT_MS = 8_000L
    /** The code-entry notification stays at most this long (the same as the pairing window search), so it does not linger even if the App is killed. */
    private const val INPUT_TIMEOUT_MS = 10 * 60_000L
    private const val TAG = "MikaPairing"
    /**
     * The quietly prepared code-entry notification is put in a group of its own with only the group summary allowed to alert: with no summary in the group it neither rings nor pops up (the mute trick at minSdk 30,
     * the same approach AndroidX's setSilent takes on older systems). No separate low-importance channel is created: some systems fold low-importance notifications into "less important notifications", where they are hard to find.
     */
    private const val QUIET_GROUP = "pairing_quiet"

    /** Make sure a pairing channel exists and return the one to use (the guide page uses it to hint and to open its settings page). */
    fun ensureChannel(context: Context): ChannelPlan {
        val nm = context.getSystemService(NotificationManager::class.java)
        val existing = PairingChannel.IDS.mapNotNull { id ->
            nm.getNotificationChannel(id)?.let { ChannelSnapshot(id, it.importance, it.hasUserSetImportance()) }
        }
        val plan = PairingChannel.plan(existing)
        if (plan is ChannelPlan.Create) {
            nm.createNotificationChannel(
                NotificationChannel(plan.id, PhoneSetupCopy.CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    setShowBadge(false)
                },
            )
        }
        (PairingChannel.IDS + PairingChannel.LEGACY).filter { it != plan.id }.forEach { id ->
            if (nm.getNotificationChannel(id) != null) nm.deleteNotificationChannel(id)
        }
        return plan
    }

    fun show(context: Context, notice: PairingNotice) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = ensureChannel(context).id
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(open)
        when (notice) {
            is PairingNotice.EnterCode -> {
                val input = RemoteInput.Builder(KEY_CODE).setLabel(PhoneSetupCopy.NOTIFY_INPUT_LABEL).setAllowFreeFormInput(true).build()
                // RemoteInput has to put its result into the Intent, so the Intent must be mutable
                val reply = PendingIntent.getBroadcast(
                    context, 1,
                    Intent(context, PairingCodeReceiver::class.java).apply { notice.port?.let { putExtra(EXTRA_PORT, it) } },
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val action = Notification.Action.Builder(null, PhoneSetupCopy.NOTIFY_ACTION, reply)
                    .addRemoteInput(input)
                    .setAllowGeneratedReplies(false)
                    .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
                // Can be filled in even while the screen is locked, without asking for an unlock first
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) action.setAuthenticationRequired(false)
                val text = notice.error ?: PhoneSetupCopy.NOTIFY_ENTER_TEXT
                b.setContentTitle(PhoneSetupCopy.NOTIFY_ENTER_TITLE)
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setOngoing(true)
                    // Every post must ring and pop up, including re-alerts when nobody enters a code and the hint after a wrong code
                    .setOnlyAlertOnce(false)
                    .setTimeoutAfter(INPUT_TIMEOUT_MS)
                    .addAction(action.build())
                if (notice.port != null) b.setSubText(PhoneSetupCopy.NOTIFY_WINDOW_FOUND)
            }
            PairingNotice.Working -> b.setContentTitle(PhoneSetupCopy.NOTIFY_WORKING).setOngoing(true).setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
            PairingNotice.Done -> b.setContentTitle(PhoneSetupCopy.NOTIFY_DONE_TITLE).setContentText(PhoneSetupCopy.NOTIFY_DONE_TEXT)
                .setAutoCancel(true).setOnlyAlertOnce(true).setTimeoutAfter(DONE_TIMEOUT_MS)
            is PairingNotice.Problem -> b.setContentTitle(notice.title).setContentText(PhoneSetupCopy.NOTIFY_PROBLEM_TEXT).setAutoCancel(true)
        }
        // The quiet one: placed in the shade without ringing or popping up (even on a high-importance channel; minSdk 30 has no setSilent)
        if (notice is PairingNotice.EnterCode && notice.quiet) b.setOnlyAlertOnce(true).setGroup(QUIET_GROUP).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        // Without notification permission the system drops it; the guide page asks the user to allow it first at the pairing step
        runCatching { nm.notify(ID, b.build()) }.onFailure { Log.w(TAG, "pairing notification not posted", it) }
        // It may be posted and still not pop up: log both the master switch and the channel importance (vendor-specific "floating notification" switches cannot be read)
        if (!nm.areNotificationsEnabled()) Log.w(TAG, "notifications are off for Claire; the pairing notice is dropped")
        else nm.getNotificationChannel(channel)?.let { if (it.importance < NotificationManager.IMPORTANCE_HIGH) Log.i(TAG, "pairing channel importance ${it.importance}; it won't pop up") }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }
}
