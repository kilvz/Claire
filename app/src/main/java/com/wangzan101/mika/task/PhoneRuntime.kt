package com.wangzan101.mika.task

import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.webkit.MimeTypeMap
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen
import com.wangzan101.mika.cap.phone.a11y.AppCopyReader
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.LazyAgentDisplay
import com.wangzan101.mika.cap.phone.display.PipInput
import com.wangzan101.mika.cap.phone.shell.AppAudioKey
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.core.agent.RunProgress
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.workspace.SandboxPaths
import com.wangzan101.mika.media.AudioShareExporter
import com.wangzan101.mika.media.ShareableFile
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.takeover.Takeover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The connected phone side: shell, accessibility, virtual display, muting. */
internal class PhoneSide(
    val shell: IMikaShell, val a11y: ScreenAccessibility, val screen: AndroidScreen,
    val display: LazyAgentDisplay, val audio: AppMuter,
)

/**
 * Entry point for direct manipulation from the small window: converts normalized coordinates on the preview into virtual-display logical coordinates and hands them to the built-in helper for injection.
 * Injection runs on a background thread: injectStroke sleeps point by point according to the stroke duration, so it must not occupy the UI thread.
 */
internal class PipInputImpl(
    private val shell: IMikaShell,
    private val display: AgentDisplay,
) : PipInput {
    override suspend fun tap(fx: Float, fy: Float) {
        withContext(Dispatchers.IO) {
            runCatching { shell.injectTap(display.displayId, (fx * display.width).toInt(), (fy * display.height).toInt()) }
        }
    }

    override suspend fun swipe(points: List<Pair<Float, Float>>, durationMs: Long) {
        if (points.size < 2) {
            if (points.size == 1) tap(points[0].first, points[0].second)
            return
        }
        withContext(Dispatchers.IO) {
            val n = points.size
            runCatching {
                shell.injectStroke(
                    display.displayId,
                    IntArray(n) { (points[it].first * display.width).toInt() },
                    IntArray(n) { (points[it].second * display.height).toInt() },
                    LongArray(n) { durationMs * it / (n - 1) },
                )
            }
        }
    }

    override suspend fun back() {
        withContext(Dispatchers.IO) {
            runCatching { shell.injectKey(display.displayId, KeyEvent.KEYCODE_BACK) }
        }
    }
}

/**
 * Phone-side runtime: overlay sound, muting rules, target app monitoring, pausing on browser page touches, plus several phone-side helpers.
 * The corresponding functions from the former TaskRun were moved here wholesale; transient TaskRun fields that need reading/writing are injected via lambdas.
 */
internal class PhoneRuntime(
    private val service: TaskService,
    private val conversationId: String,
    private val scope: CoroutineScope,
    private val state: TaskRunState,
    private val publish: (TaskState) -> Unit,
    private val phone: () -> PhoneSide?,
    private val takeover: () -> Takeover?,
    private val gate: () -> TaskGate?,
    private val handoverRef: () -> Takeover?,
    private val sideScope: () -> CoroutineScope?,
    private val sandbox: () -> AndroidSandbox,
    private val setMuteFailedFor: (String?) -> Unit,
    private val workspaceKey: () -> WorkspaceKey = { WorkspaceKey.Conversation(conversationId) },
) {
    /** Sound switch on the overlay: if sound was restored, pressing again mutes it back; if not, pressing restores it (repeated taps retry the real action); does nothing without a phone side or a target app. */
    fun togglePipSound() {
        scope.launch(Dispatchers.IO) {
            val restored = pipSoundRestored()
            val targetPkg = phone()?.screen?.target?.value?.packageName
            val handed = takeover()?.handedOverPackage
            Log.i(TAG, "togglePipSound restored=$restored target=$targetPkg handed=$handed unmuted=${phone()?.audio?.userUnmuted?.value}")
            if (restored) mutePipSound(takeover()) {
                val p = phone() ?: return@mutePipSound
                val target = p.screen.target.value ?: return@mutePipSound
                if (!p.audio.muteAgain(target.packageName, copyUser(target.userId))) {
                    Log.w(TAG, "could not mute again ${target.packageName} from pip")
                }
            } else restorePipSound(takeover()) {
                val p = phone() ?: return@restorePipSound
                val target = p.screen.target.value ?: return@restorePipSound
                if (!p.audio.unmute(target.packageName, copyUser(target.userId))) {
                    Log.w(TAG, "could not restore sound of ${target.packageName} from pip")
                }
            }
        }
    }

    /** Whether the sound of the small window's current target has already been restored by the user: during takeover look at the handed-over package, otherwise at the virtual display's target; the key is derived the same way as in unmute/muteAgain. */
    fun pipSoundRestored(): Boolean {
        val p = phone() ?: return false
        val target = p.screen.target.value
        val pkg = takeover()?.handedOverPackage ?: target?.packageName ?: return false
        val user = target?.takeIf { it.packageName == pkg }?.let { copyUser(it.userId) }
        return AppAudioKey.of(pkg, user) in p.audio.userUnmuted.value
    }

    internal suspend fun shareWorkspaceAudio(path: String): ShareableFile? = withContext(Dispatchers.IO) {
        val trimmed = path.trim()
        val prefix = SandboxPaths.WORKSPACE + "/"
        if (!trimmed.startsWith(prefix)) return@withContext null
        val rel = trimmed.removePrefix(prefix)
        if (rel.isEmpty() || rel.split('/').any { it.isEmpty() || it == "." || it == ".." }) return@withContext null
        val dirs = SandboxDirs(service)
        val bytes = dirs.tree.shared {
            dirs.gate.openRead(dirs.workspace(workspaceKey()), rel).use { g ->
                if (g.size > 20L * 1024 * 1024) null else g.read(0, g.size.toInt())
            }
        } ?: return@withContext null
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(trimmed.substringAfterLast('.', "").lowercase())
            ?.takeIf { it.startsWith("audio/") } ?: "audio/wav"
        AudioShareExporter(service).save(bytes, mime, trimmed.substringAfterLast('/'))
    }

    /**
     * Muting rules for this task (see [AppMuter]): Claire itself is never muted; system packages with no launcher entry are not muted (system UI, choosers and the like);
     * apps the user can see on the main screen are not muted; anything that could not be muted is recorded and flagged in task progress as possibly making sound.
     */
    fun appMuter(shell: IMikaShell, audio: AudioMute): AppMuter {
        val pm = service.packageManager
        val own = service.packageName
        fun system(pkg: String) = runCatching {
            pm.getApplicationInfo(pkg, 0).flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }.getOrElse { runCatching { shell.isSystemPackage(pkg) }.getOrDefault(false) }
        return AppMuter(
            audio,
            enabled = { FeatureFlags.get(service).muteTarget() },
            mutable = { pkg -> pkg != own && (runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() != null || !system(pkg)) },
            onMainScreen = { pkg, user -> runCatching { shell.visibleOnMainScreen(pkg, user ?: AppCopyReader.myUser) }.getOrDefault(true) },
            failed = { pkg ->
                setMuteFailedFor(pkg)
                publish(state._state.value)
            },
        )
    }

    /** Task's user -> which copy to mute: null means the primary copy (or it could not be read); a user id means the copy in that other user. */
    fun copyUser(userId: Int): Int? = userId.takeIf { it >= 0 && it != AppCopyReader.myUser }

    /**
     * The system asks on the user's main screen which one to open (see [AndroidScreen.userPick]): hold here and ask the user to pick on screen from the capsule, then continue automatically once picked;
     * if the picked app needs the user to handle it personally, stay on the main screen and ask the user to press continue when done. Returns immediately when not waiting.
     */
    suspend fun awaitUserPick(screen: AndroidScreen, gate: TaskGate, setWaiting: (TaskState?) -> Unit) {
        val pick = screen.userPick.value ?: return
        setWaiting(TaskState.Held(TaskGate.userStep(TaskGate.pickCopy(pick.app))))
        val r = screen.awaitUserPick() as? AndroidScreen.PickResult.NeedsUser ?: return
        gate.pauseForUser(TaskGate.finishCopy(r.app), setWaiting)
        screen.afterUserStep(r)
    }

    /**
     * Mute whenever the target app changes (it is mostly already muted before launch; this covers what was missed), and watch whether it gets moved to the main screen by the user.
     */
    fun watchTarget(p: PhoneSide, states: DeviceStateWatcher) {
        val side = sideScope() ?: return
        side.launch(Dispatchers.IO) {
            p.screen.foregroundApps().collect { t ->
                if (handoverRef()?.handedOver != true) p.audio.opened(t.packageName, copyUser(t.userId))
                delay(FOREGROUND_GAP_MS)
            }
        }
        side.launch(Dispatchers.IO) {
            val stage = takeover()?.stage ?: MutableStateFlow(Takeover.Stage.None)
            combine(p.screen.target, p.audio.userUnmuted, stage) { _, _, _ -> }.collect {
                state._pipSoundOn.value = pipSoundRestored()
            }
        }
        side.launch(Dispatchers.IO) {
            p.screen.target.filterNotNull().collectLatest { t ->
                state._appShown.value = true
                if (handoverRef()?.handedOver != true) p.audio.opened(t.packageName, copyUser(t.userId))
                val displayId = p.display.current?.displayId
                if (t.taskId >= 0 && displayId != null) {
                    states.taskMovedAway(displayId, t.taskId).collect {
                        if (handoverRef()?.handedOver == true) return@collect
                        gate()?.onTaskMovedAway()
                        publish(state._state.value)
                    }
                }
            }
        }
    }

    /**
     * The user personally touched the page lent to this task on the browser page: show paused immediately while working, stop before the next tool that would change the screen,
     * and resume when the user presses "Continue" (see [TaskGate.onUserOnPage]). Other states such as waiting for an answer or during takeover are left alone. Main thread.
     */
    fun userOnPage() {
        if (state._state.value !is TaskState.Running) return
        val g = gate() ?: return
        g.onUserOnPage()
        publish(state._state.value)
    }

    /** Title of the topmost window of that package on the main screen; windows are ordered from top to bottom. */
    fun mainWindowTitle(a11y: ScreenAccessibility, packageName: String): String? = runCatching {
        a11y.windowsOn(Display.DEFAULT_DISPLAY).firstNotNullOfOrNull { w ->
            w.title?.toString()?.takeIf { it.isNotBlank() && w.root?.packageName?.toString() == packageName }
        }
    }.getOrNull()

    /** Write the sandbox step's description onto the task card (the current step's note). */
    fun sandboxNote(text: String) {
        val s = state._state.value as? TaskState.Running ?: return
        publish(s.copy(note = text))
    }

    fun sandboxOnline() = (sandbox().status() as? SandboxStatus.Ready)?.network == true

    /**
     * The stretch while waiting for another task to release [app]: report outward progress as "waiting for another task to finish using [app]", still Running, not paused, and nothing for you to handle;
     * once the wait is over (released, timed out, stopped), restore the line from before the wait. If it was not working before the wait (e.g. already stopped for you to handle), leave it unchanged.
     */
    suspend fun <T> waitingForApp(app: String, wait: suspend () -> T): T {
        val before = state._state.value as? TaskState.Running ?: return wait()
        val waiting = before.copy(progress = RunProgress.WaitingForApp(app))
        publish(waiting)
        try {
            return wait()
        } finally {
            if (state._state.value == waiting) publish(before)
        }
    }

    @Suppress("DEPRECATION")
    fun mainScreenMetrics(): DisplayMetrics = DisplayMetrics().also {
        service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
    }

    private companion object {
        const val TAG = "MikaTask"
        /** Minimum gap between two re-reads of the foreground app on the Claire display. */
        const val FOREGROUND_GAP_MS = 300L
    }
}
