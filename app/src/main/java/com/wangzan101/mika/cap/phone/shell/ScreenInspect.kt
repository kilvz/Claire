package com.wangzan101.mika.cap.phone.shell

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.UiAutomation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.Keep
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Read-only screen inspection shell entry point: a computer runs it over authorized ADB
 * `CLASSPATH=<Mika base.apk> app_process /system/bin com.wangzan101.mika.shell.ScreenInspectMain`
 * (stable entry point, only forwards here; the CLI only recognises that full name),
 * standard output prints one line of JSON (windows and control trees of every display, each window with `display_id`).
 *
 * Runs only under shell identity (uid 2000). `UiAutomation` always connects with [UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES]:
 * the default connection (flags = 0, i.e. the system's built-in `uiautomator dump`) disconnects other accessibility services such as Claire's.
 * When a connection method with flags is unavailable it reports `unsupported` and never falls back to the default connection. No taps, no input, no settings changes;
 * password fields output no text or description, and control count and depth are capped (see [ScreenInspectTree]). The whole process takes no longer than [WATCHDOG_MS].
 * Writes no logs: screen content appears only in this single line of output.
 */
@Keep
object ScreenInspect {
    private const val WATCHDOG_MS = 15_000L
    private const val IDLE_MS = 300L
    private const val IDLE_TIMEOUT_MS = 2_000L

    @JvmStatic
    fun main(args: Array<String>) {
        if (Process.myUid() != ShellContext.SHELL_UID) {
            finish(failure("shell_required"), 2)
        }
        val done = AtomicBoolean(false)
        // Exits on time even when a binder call hangs (system busy, app not responding), leaving no process behind
        Thread {
            try {
                Thread.sleep(WATCHDOG_MS)
            } catch (e: InterruptedException) {
                return@Thread
            }
            if (done.compareAndSet(false, true)) finish(failure("timeout"), 3)
        }.apply { isDaemon = true; name = "mika-screen-inspect-watchdog" }.start()
        val thread = HandlerThread("mika-screen-inspect").apply { start() }
        val result = try {
            inspect(thread.looper)
        } catch (e: Throwable) {
            // Exception text may carry screen content, so report only the class; when a method is missing on some systems it is a LinkageError, which also counts as unreadable
            failure("screen_unavailable")
        } finally {
            thread.quitSafely()
        }
        val ok = (result["status"] as? JsonPrimitive)?.content == "ok"
        if (done.compareAndSet(false, true)) finish(result, if (ok) 0 else 1)
        // The watchdog has already printed first and we are exiting
        Thread.sleep(Long.MAX_VALUE)
    }

    private fun inspect(looper: Looper): JsonObject {
        val access = UiAutomationAccess.detect() ?: return failure("unsupported")
        val automation = try {
            access.create(looper)
        } catch (e: Throwable) {
            return failure("unsupported")
        }
        try {
            try {
                access.connect(automation)
            } catch (e: Exception) {
                // Another UiAutomation is connected, or the system refused; do not retry with the default connection that would suppress accessibility services
                return failure("connect_failed")
            }
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            try {
                automation.waitForIdle(IDLE_MS, IDLE_TIMEOUT_MS)
            } catch (e: TimeoutException) {
                // Even a constantly changing UI is read as it currently looks
            }
            val budget = InspectBudget()
            val displays = windowsByDisplay(automation)
            val windows = buildJsonArray {
                // Focused and active windows are read first; when the total budget is not enough, the remaining windows are what gets cut
                val all = displays.flatMap { (displayId, list) -> list.map { displayId to it } }
                for ((displayId, window) in ScreenInspectTree.windowOrder(all, { it.second.isFocused }, { it.second.isActive })) {
                    add(window(displayId, window, budget))
                }
            }
            return buildJsonObject {
                put("status", "ok")
                put("display_ids", buildJsonArray { for ((id, _) in displays) add(id) })
                put("accessibility_services_suppressed", false)
                put("captured_at_uptime_ms", SystemClock.uptimeMillis())
                put("node_count", budget.nodes)
                put("truncated", budget.truncated)
                put("truncated_by", buildJsonArray { for (reason in budget.truncatedBy) add(reason) })
                put("windows", windows)
            }
        } finally {
            access.disconnect(automation)
        }
    }

    /** Android 11 (API 30) and later have public getWindowsOnAllDisplays, which can read Claire's virtual display; earlier versions only have the default display. */
    private fun windowsByDisplay(automation: UiAutomation): List<Pair<Int, List<AccessibilityWindowInfo>>> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val all = automation.windowsOnAllDisplays
            (0 until all.size()).map { all.keyAt(it) to all.valueAt(it).orEmpty() }
        } else {
            listOf(Display.DEFAULT_DISPLAY to automation.windows.orEmpty())
        }

    private fun window(displayId: Int, window: AccessibilityWindowInfo, budget: InspectBudget): JsonObject {
        val rect = Rect().also(window::getBoundsInScreen)
        return buildJsonObject {
            put("display_id", displayId)
            put("id", window.id)
            put("type", window.type)
            val title = ScreenInspectTree.plain(window.title, budget)
            put("title", title)
            put("bounds", ScreenInspectTree.bounds(intArrayOf(rect.left, rect.top, rect.right, rect.bottom)))
            put("active", window.isActive)
            put("focused", window.isFocused)
            // The window itself also counts toward the total output; when exceeded, keep only window info and do not read the control tree
            val room = budget.charge(ScreenInspectTree.NODE_OVERHEAD + ((title as? JsonPrimitive)?.contentOrNull?.length ?: 0))
            val tree = if (room) window.root?.let { ScreenInspectTree.node(A11yInspectNode(it), budget) } else null
            put("root", tree ?: JsonNull)
        }
    }

    private fun failure(code: String) = buildJsonObject {
        put("status", "error")
        put("code", code)
    }

    private fun finish(result: JsonObject, code: Int): Nothing {
        println(result.toString())
        System.out.flush()
        // app_process binder threads and the HandlerThread do not end by themselves
        exitProcess(code)
    }
}

/**
 * Creates a [UiAutomation] in the shell process and connects it with flags. The system provides no public constructor for the shell process,
 * so these are all hidden APIs, probed one by one by capability; if any is missing the whole thing is unavailable (the caller reports `unsupported`) and it does not switch to another connection method:
 * - `android.app.UiAutomationConnection` no-arg constructor, `android.app.IUiAutomationConnection`: Android 4.3 (API 18) and later;
 * - `UiAutomation(Looper, IUiAutomationConnection)`: API 18 and later; in AOSP the shell wrapper of `uiautomator` is built this way (present in Android 10–15 sources,
 *   still needs acceptance on real devices of each version); when not found, try `UiAutomation(Context, IUiAutomationConnection)` added in Android 14 (API 34),
 *   with the Context's main thread replaced by our [Looper] (connection callbacks are posted to this thread; a main thread that is not running cannot be used);
 * - `connect(int flags)`: Android 7.0 (API 24) and later; Claire's minimum is API 29, so it is normally present; when absent, never call the no-arg `connect()` (it would suppress accessibility services);
 * - `disconnect()`: API 18 and later.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi")
private class UiAutomationAccess(
    private val connection: Class<*>,
    private val connectionInterface: Class<*>,
    private val connect: java.lang.reflect.Method,
    private val disconnect: java.lang.reflect.Method?,
) {
    fun create(looper: Looper): UiAutomation {
        val instance = connection.getConstructor().newInstance()
        runCatching { UiAutomation::class.java.getConstructor(Looper::class.java, connectionInterface) }.getOrNull()
            ?.let { return it.newInstance(looper, instance) as UiAutomation }
        val byContext = UiAutomation::class.java.getConstructor(Context::class.java, connectionInterface)
        // ShellContext needs a main-thread Looper when it creates its own ActivityThread; here we are on the main thread
        @Suppress("DEPRECATION")
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val context = object : ContextWrapper(ShellContext.create()) {
            override fun getMainLooper(): Looper = looper
        }
        return byContext.newInstance(context, instance) as UiAutomation
    }

    fun connect(automation: UiAutomation) {
        try {
            connect.invoke(automation, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw (e.cause as? Exception) ?: e
        }
    }

    fun disconnect(automation: UiAutomation) {
        runCatching { disconnect?.invoke(automation) }
    }

    companion object {
        fun detect(): UiAutomationAccess? = runCatching {
            val connection = Class.forName("android.app.UiAutomationConnection")
            val connectionInterface = Class.forName("android.app.IUiAutomationConnection")
            val connect = UiAutomation::class.java.getMethod("connect", Int::class.javaPrimitiveType)
            val disconnect = runCatching { UiAutomation::class.java.getMethod("disconnect") }.getOrNull()
            UiAutomationAccess(connection, connectionInterface, connect, disconnect)
        }.getOrNull()
    }
}

/** Hands [AccessibilityNodeInfo] to [ScreenInspectTree]. */
internal class A11yInspectNode(private val info: AccessibilityNodeInfo) : InspectNode {
    override val packageName: CharSequence? get() = info.packageName
    override val className: CharSequence? get() = info.className
    override val resourceId: String? get() = info.viewIdResourceName
    override val text: CharSequence? get() = info.text
    override val description: CharSequence? get() = info.contentDescription
    override val isPassword: Boolean get() = info.isPassword
    override val bounds: IntArray get() = Rect().also(info::getBoundsInScreen).let { intArrayOf(it.left, it.top, it.right, it.bottom) }
    override val isClickable: Boolean get() = info.isClickable
    override val isEditable: Boolean get() = info.isEditable
    override val isEnabled: Boolean get() = info.isEnabled
    override val isVisible: Boolean get() = info.isVisibleToUser
    override val childCount: Int get() = info.childCount
    override fun child(index: Int): InspectNode? = info.getChild(index)?.let(::A11yInspectNode)

    @Suppress("DEPRECATION") // Since API 33 recycling is a no-op; older systems must recycle
    override fun release() = info.recycle()
}
