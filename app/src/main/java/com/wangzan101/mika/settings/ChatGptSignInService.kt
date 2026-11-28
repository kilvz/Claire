package com.wangzan101.mika.settings

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.enterForeground
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.task.TaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The foreground service used while signing in with ChatGPT: it holds the process up while Claire is in the background and the user signs in in the browser, so the system does not freeze it,
 * the local callback has someone to receive it, and the token exchange is not delayed. It only posts one quiet notification, which can "cancel" this sign-in.
 * It does no work itself: the sign-in runs in [ChatGptSignIns], and this service watches its running flag and stops as soon as the sign-in ends (success, failure, cancellation or timeout).
 */
class ChatGptSignInService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val signIns get() = (application as MikaApp).chatGptSignIn
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TaskService.ensureChannels(this)
        // After startForegroundService we must enter the foreground quickly, even if we are about to stop; when that fails, stop here and sign-in proceeds (just without this protection)
        val foreground = enterForeground(
            NOTIFICATION_ID,
            onFailure = { Log.w(TAG, "sign-in service could not enter the foreground", it) },
        ) { notification() }
        if (intent?.action == ACTION_CANCEL) signIns.cancel()
        if (FeatureFlags.get(this).quitRequested()) signIns.cancel()
        if (!foreground || FeatureFlags.get(this).quitRequested()) {
            stopSelf()
            return START_NOT_STICKY
        }
        watch?.cancel()
        watch = scope.launch {
            signIns.running.first { !it }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        // If the process is gone, the sign-in is gone too, so there is no need to recreate it
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val cancel = PendingIntent.getService(this, 1, Intent(this, ChatGptSignInService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, TaskService.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(signIns.noticeTitle ?: tr(R.string.chatgpt_signin_notif_title))
            .setContentText(signIns.noticeBody ?: tr(R.string.chatgpt_signin_notif_body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(TaskService.openApp(this))
            .addAction(Notification.Action.Builder(null, tr(R.string.cancel), cancel).build())
            .build()
    }

    companion object {
        private const val TAG = "MikaChatGpt"
        private const val ACTION_CANCEL = "com.wangzan101.mika.chatgpt.CANCEL_SIGN_IN"
        /** Offset from the notifications used by tasks and by the computer connection. */
        private const val NOTIFICATION_ID = 30_002

        /** Called when a sign-in starts (the user has just tapped sign-in, Claire is in the foreground, so a foreground service may be started); if it cannot start it throws, and the caller records that. */
        fun start(context: Context) {
            if (FeatureFlags.get(context).quitRequested()) return
            context.startForegroundService(Intent(context, ChatGptSignInService::class.java))
        }
    }
}
