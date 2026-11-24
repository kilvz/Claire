package com.wangzan101.mika

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.sandbox.RuntimePreparer
import com.wangzan101.mika.mcp.McpService
import com.wangzan101.mika.sandbox.SandboxService
import com.wangzan101.mika.settings.ChatGptSignInService
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.task.TriggerRuntime
import com.wangzan101.mika.wechat.WechatService

/**
 * Fully quit Claire: when the user swipes away the UI or the process is reclaimed by the system, the accessibility
 * service is still running, so the floating capsule and background tasks would stay alive.
 * This does a real exit when the user explicitly taps "Quit":
 * it records the quit flag ([FeatureFlags.quitRequested]), hides the overlay, stops tasks, the computer connection and
 * the several foreground services, clears notifications, and finally kills the process.
 * The accessibility service itself is not turned off (that is the user's own system setting, and it will be needed next
 * time Claire is opened); when the system rebinds it, the process stays quiet according to the quit flag,
 * not drawing the capsule, not re-establishing the computer connection, and not restoring on the user's behalf, until
 * the user opens Claire again ([resume]).
 */
object MikaQuit {
    private val handler = Handler(Looper.getMainLooper())
    private var exit: Runnable? = null

    /**
     * Quit from here. Pass an Activity when the UI is in the foreground (closing that screen as well); from places
     * without a UI, such as the floating capsule, pass the application context.
     * First hide the capsule immediately and stop the services, then after [EXIT_DELAY_MS] clear notifications once more
     * and kill the process, leaving a little time for the services' shutdown cleanup to finish
     * (the task service marks interruptions and closes the page left in the background when it is destroyed).
     */
    fun quit(context: Context) {
        val app = context.applicationContext as MikaApp
        FeatureFlags.get(app).setQuitRequested(true)
        // The overlay is attached to the accessibility service and may still be alive before the process ends:
        // tear it down on the spot instead of waiting for the process
        runCatching { app.capsule.stopNow() }
        // Remove system alarms and in-process long tasks first, then stop the foreground services; when the system
        // re-delivers start requests to the services, they also refuse work according to the quit flag
        runCatching { TriggerRuntime.suspendAlarms(app) }
        runCatching { RuntimePreparer.stop(app) }
        runCatching { app.wechat.stop() }
        // When proactive capabilities are on, stop reading notifications: ask the system to unbind the listener,
        // so the system does not pull the process back up because of it
        runCatching { if (FeatureFlags.get(app).proactive()) app.proactive.pause() }
        runCatching { app.mcp.stopServer() }
        runCatching { app.chatGptSignIn.cancel() }
        runCatching { app.stopService(Intent(app, TaskService::class.java)) }
        runCatching { app.stopService(Intent(app, McpService::class.java)) }
        runCatching { app.stopService(Intent(app, WechatService::class.java)) }
        runCatching { app.stopService(Intent(app, SandboxService::class.java)) }
        runCatching { app.stopService(Intent(app, ChatGptSignInService::class.java)) }
        (context as? Activity)?.finishAndRemoveTask()
        runCatching { app.getSystemService(NotificationManager::class.java)?.cancelAll() }
        scheduleExit(app)
    }

    /**
     * The user opened Claire again: cancel the not-yet-executed exit, clear the quit flag, and let the capsule and the
     * things skipped at startup (cleanup of unfinished runs, runtime environment preparation) proceed as usual.
     * This is also the path taken when the process was already killed and the system pulls it up again.
     */
    fun resume(context: Context) {
        exit?.let(handler::removeCallbacks)
        exit = null
        val app = context.applicationContext as MikaApp
        val flags = FeatureFlags.get(app)
        if (!flags.quitRequested()) return
        flags.setQuitRequested(false)
        app.capsule.resume()
        app.resumeAfterQuit()
    }

    private fun scheduleExit(app: MikaApp) {
        exit?.let(handler::removeCallbacks)
        val r = Runnable {
            exit = null
            // Stopping the services may have posted another "task interrupted" notification; clear them all before leaving
            app.getSystemService(NotificationManager::class.java)?.cancelAll()
            Process.killProcess(Process.myPid())
        }
        exit = r
        handler.postDelayed(r, EXIT_DELAY_MS)
    }

    /** Leaves a little time for stopping services and writing conversations; to the user it looks like quitting on tap, with no noticeable pause. */
    private const val EXIT_DELAY_MS = 800L
}
