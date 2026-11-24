package com.wangzan101.mika.cap.sandbox

import android.content.Context
import android.content.Intent
import android.util.Log
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.sandbox.SandboxService
import com.wangzan101.mika.settings.FeatureFlags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Prepare the runtime in the background: at App startup, after a Claire upgrade, after a reset, and when a task needs it but it is not ready yet.
 * Invisible to the user; only a low-priority neutral notification ([SandboxForeground]) is shown while preparing. No network access.
 */
object RuntimePreparer {
    private const val TAG = "RuntimePreparer"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    /** Preparation has failed in this process: no more automatic retries at App startup; retry only when a task needs it (retryFailed in [ensure]). */
    @Volatile private var failed = false

    /** The preparation currently in progress; null if none. */
    val current: Job? get() = job?.takeIf { it.isActive }

    /** Start preparing if needed and return this preparation (the existing one if already preparing); return null if not needed. */
    @Synchronized
    fun ensure(context: Context, retryFailed: Boolean = false): Job? {
        if (FeatureFlags.get(context).quitRequested()) return null
        current?.let { return it }
        val app = context.applicationContext
        val state = SandboxState.get(app)
        if (!needsWork(state.status.value, state.installedVersion, BundledRuntime.VERSION, skipBroken = failed && !retryFailed)) return null
        val j = scope.launch {
            try {
                RootfsInstaller.create(app).install { _, _ -> }
                failed = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                Log.w(TAG, "runtime preparation failed", e)
            }
        }
        job = j
        // The foreground service keeps the process from being reclaimed while preparing and shows the neutral notification; when in the background the system does not allow starting a foreground service, so prepare in-process only
        try {
            SandboxForeground.start(app)
        } catch (e: Exception) {
            Log.i(TAG, "preparing without the foreground notification: $e")
        }
        return j
    }

    /** Full exit: cancel unpacking and dismiss the preparation notification; the progress file is still kept by the installer under its atomic-write rules, so the next launch can prepare again. */
    @Synchronized
    fun stop(context: Context) {
        job?.cancel()
        job = null
        context.applicationContext.stopService(Intent(context.applicationContext, SandboxService::class.java))
    }

    /** Whether preparation is needed: not installed, an old version installed (Claire was upgraded), or an error occurred (unless [skipBroken]). Unsupported or in-progress states are left alone. */
    internal fun needsWork(status: SandboxStatus, installed: String?, bundled: String, skipBroken: Boolean): Boolean = when (status) {
        is SandboxStatus.Unsupported, is SandboxStatus.Installing -> false
        is SandboxStatus.Ready -> installed != bundled
        SandboxStatus.NotInstalled -> true
        is SandboxStatus.Broken -> !skipBroken
    }
}
