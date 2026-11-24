package com.wangzan101.mika.voicecall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * The microphone-type foreground service for voice calls: keeps the process alive during a call so the microphone is not cut off in the background; the ongoing notification (CATEGORY_CALL)
 * carries a hang-up action. It does not open the call page when the screen is locked — tapping the notification only brings the app to the foreground.
 *
 * Single-session exclusivity: only one [VoiceCallSession] is hosted at a time. When there is already an active session (not Ended), [attach] **rejects** a second one
 * (returns false, and the caller must destroy the new session itself) — this "reject" semantics guarantees that only one session holds the call audio at any moment
 * (the multi-session exclusivity of VoiceCallAudio.requestCallAudio/abandonCallAudio relies on this as a backstop).
 *
 * Assembly (call page, Task 7): create a session → `attach(context, session)`, and only a true return means it has claimed the slot; on false destroy the new session immediately.
 * On Android 14+ a microphone-type foreground service can only be started while the app is in the foreground, and RECORD_AUDIO must already be granted at startForeground —
 * the call page creates the session only after the permission request is approved, so the order naturally holds.
 */
class VoiceCallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hangupRequests = Channel<Unit>(Channel.CONFLATED)
    private var raceJob: Job? = null
    private var session: VoiceCallSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANGUP) {
            hangupRequests.trySend(Unit)
            if (session == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val call = synchronized(companionLock) {
            val pending = pendingSession
            pendingSession = null
            pending
        }
        if (call == null || session != null) {
            // Duplicate start / no session to host: nothing to do; if a session is already running, keep the status quo
            if (session == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val promoted = runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        }.isSuccess
        if (!promoted) {
            // If it cannot be promoted to the foreground it has no right to stay alive: hand the session back to the caller's lifecycle (the call goes on while in the foreground; going to the background is covered by the system's muting path raising MicSilent)
            synchronized(companionLock) { if (activeSession === call) activeSession = null }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        session = call
        // Clear the leftover hang-up taps from the previous call so an old signal does not hang up the new session
        while (hangupRequests.tryReceive().isSuccess) Unit
        raceSessionEnd(call)
        return START_NOT_STICKY
    }

    /** select race: whichever comes first wins — the call coroutine reaching its terminal state itself, or a hang-up from the notification. */
    private fun raceSessionEnd(call: VoiceCallSession) {
        raceJob = scope.launch {
            val endedNaturally = async { call.state.first { it is VoiceCallState.Ended } }
            val notified = async { hangupRequests.receive() }
            val fromNotification = select {
                endedNaturally.onAwait { false }
                notified.onAwait { true }
            }
            endedNaturally.cancel()
            notified.cancel()
            if (fromNotification) call.end(EndReason.Notification)
            detach(call)
        }
    }

    private fun detach(call: VoiceCallSession) {
        synchronized(companionLock) { if (activeSession === call) activeSession = null }
        // The old session winds down after a new session has taken over: the foreground state and notification already belong to the new session, so the old one only frees its slot and leaves the service alone
        if (session !== call) return
        session = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        raceJob?.cancel()
        val call = session
        session = null
        synchronized(companionLock) { if (activeSession === call) activeSession = null }
        // The service was killed by the system while the call is still alive: reach a terminal state to release the audio, leaving no orphaned call behind
        if (call != null && call.state.value !is VoiceCallState.Ended) call.end(EndReason.Hangup)
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = checkNotNull(getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.voicecall_notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
        // Do not open the call page from the lock screen: tapping only brings the app to the foreground, and the call page's visibility is decided by in-app state itself
        val open = PendingIntent.getActivity(
            this, NOTIFICATION_ID, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hangup = PendingIntent.getService(
            this, NOTIFICATION_ID, Intent(this, VoiceCallService::class.java).setAction(ACTION_HANGUP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.voicecall_notification_ongoing))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .apply {
                if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                addAction(Notification.Action.Builder(null, getString(R.string.voicecall_hangup), hangup).build())
            }
            .build()
    }

    companion object {
        const val CHANNEL = "mika_voice_call"
        private const val ACTION_START = "com.wangzan101.mika.voicecall.START"
        private const val ACTION_HANGUP = "com.wangzan101.mika.voicecall.HANGUP"
        private const val NOTIFICATION_ID = 90_119

        private val companionLock = Any()

        /** The active session that has claimed the slot (which may not have been taken over by the service yet). */
        @Volatile private var activeSession: VoiceCallSession? = null

        /** The session waiting to be taken over by the service's onStartCommand; the handover box between attach and the service start. */
        @Volatile private var pendingSession: VoiceCallSession? = null

        /**
         * Claim the session slot and bring up the foreground service. When there is already an active session (not Ended), the second call is rejected and false is returned,
         * and the caller must immediately destroy the session it just created (end(EndReason.Hangup) or let it wind down).
         * If startForegroundService throws (e.g. a background start is denied), the claim is rolled back and the exception is rethrown as-is.
         */
        fun attach(context: Context, session: VoiceCallSession): Boolean {
            synchronized(companionLock) {
                val current = activeSession
                if (current != null && current.state.value !is VoiceCallState.Ended) return false
                activeSession = session
                pendingSession = session
            }
            try {
                context.startForegroundService(Intent(context, VoiceCallService::class.java).setAction(ACTION_START))
            } catch (error: RuntimeException) {
                synchronized(companionLock) {
                    if (activeSession === session) activeSession = null
                    if (pendingSession === session) pendingSession = null
                }
                throw error
            }
            return true
        }
    }
}
