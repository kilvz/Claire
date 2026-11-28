package com.wangzan101.mika.mcp

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.enterForeground
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.task.TaskService

/**
 * Foreground service that runs while computer access is on: it only keeps the process alive in the background and the port open; incoming tasks are still handed to [TaskService] to run.
 * The task service exits by itself when it has no tasks, so listening does not live inside it. The ongoing notification can turn computer access off directly (turning both kinds off).
 * It is recreated after being reclaimed by the system (START_STICKY); when all switches are off it exits on the spot.
 *
 * Turning the USB mode on and off from the computer over USB debugging also starts this service: it is exported in the manifest, but the caller must hold
 * INTERACT_ACROSS_USERS_FULL. The shell has this permission, ordinary apps cannot get it (it is only granted to system signatures and the installer, and adb cannot grant it either),
 * so only a start issued as the shell, such as `adb shell am start-foreground-service`, can bring it up; Claire starting it itself is not restricted.
 * Started as a foreground service: since Android 12 background apps generally cannot start foreground services themselves, but starts issued by the shell are exempt.
 */
class McpService : Service() {
    private val runtime get() = (application as MikaApp).mcp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TaskService.ensureChannels(this)
        val quitting = FeatureFlags.get(this).quitRequested()
        val applied = !quitting && runCatching { McpModes.apply(intent?.action, runtime.settings, runtime.usb, runtime.relaySettings) }
            .onFailure { Log.w("MikaMcp", "could not update computer access settings", it) }.isSuccess
        // After startForegroundService we must enter the foreground quickly, even if we are about to exit; when the system refuses, exit here instead of crashing the process
        val foreground = enterForeground(
            NOTIFICATION_ID,
            onFailure = { Log.w("MikaMcp", "computer access service could not enter the foreground", it) },
        ) { notification() }
        val ready = foreground && !FeatureFlags.get(this).quitRequested() && applied && runCatching { runtime.sync() }
            .onFailure { Log.w("MikaMcp", "could not start computer access", it) }.getOrDefault(false)
        if (!ready) {
            runtime.stopServer()
            runtime.usb.unpublish()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runtime.stopServer()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val port = runtime.settings.port
        val body = when {
            runtime.relaySettings.config.value.enabled -> R.string.relay_notif_body
            runtime.settings.enabled.value && runtime.usb.enabled.value -> R.string.mcp_notif_body_both
            runtime.usb.enabled.value -> R.string.mcp_notif_body_usb
            else -> R.string.mcp_notif_body
        }
        val open = TaskService.openApp(this)
        val off = PendingIntent.getService(this, 1, Intent(this, McpService::class.java).setAction(McpModes.ACTION_TURN_OFF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, TaskService.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.mcp_notif_title))
            .setContentText(tr(body, port))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, tr(R.string.mcp_turn_off), off).build())
            .build()
    }

    companion object {
        /** Kept clear of the task notifications (slot numbers, result ID ranges). */
        private const val NOTIFICATION_ID = 30_001
    }
}
