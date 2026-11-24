package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import java.lang.reflect.Method

/**
 * The shell process accesses the system clipboard directly. On some systems ClipboardManager reads Settings before
 * reading the clipboard, but app_process has no process registration like a normal app and cannot get a
 * ContentProvider through ActivityThread. Binder still checks permissions by package name, uid, user and device; this
 * only bypasses the Java wrapper's extra Settings query. An unrecognized interface (for example an earlier signature)
 * falls back to ClipboardManager and behaves as before.
 */
@SuppressLint("PrivateApi")
internal class ShellClipboard(private val context: Context) {
    private val manager: ClipboardManager by lazy {
        ClipboardManager::class.java.getDeclaredConstructor(Context::class.java, Handler::class.java)
            .apply { isAccessible = true }.newInstance(context, Handler(Looper.getMainLooper() ?: Looper.myLooper()!!))
    }
    private val service: Any? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "clipboard") as IBinder
            Class.forName("android.content.IClipboard\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        }.getOrNull()
    }
    private val getClip by lazy { method("getPrimaryClip") }
    private val getDescription by lazy { method("getPrimaryClipDescription") }
    private val hasClip by lazy { method("hasPrimaryClip") }
    private val callerLabel by lazy {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(ShellContext.SHELL_PACKAGE, 0)).toString()
        }.getOrDefault(ShellContext.SHELL_PACKAGE)
    }

    val primaryClip: ClipData?
        get() = getClip.let { if (it == null) manager.primaryClip else call(it) as ClipData? }
    val primaryClipDescription: ClipDescription?
        get() = getDescription.let { if (it == null) manager.primaryClipDescription else call(it) as ClipDescription? }
    fun hasPrimaryClip(): Boolean = hasClip.let { if (it == null) manager.hasPrimaryClip() else call(it) as Boolean }
    // Writes still go through the framework's original ClipData preparation and clearing flow; only the read path that does the extra Settings query is replaced.
    fun setPrimaryClip(clip: ClipData) = manager.setPrimaryClip(clip)
    fun clearPrimaryClip() = manager.clearPrimaryClip()

    /** Finds the interface by capability: try the original signature first, then the known extended ones; if neither matches, return null and let the caller fall back to ClipboardManager. */
    private fun method(name: String): Method? {
        if (service == null) return null
        val type = runCatching { Class.forName("android.content.IClipboard") }.getOrNull() ?: return null
        val s = String::class.java
        val i = Int::class.javaPrimitiveType!!
        val candidates = listOf(
            // Android 14+: package name, attributionTag, userId, deviceId
            arrayOf(s, s, i, i),
            // Android 12–13: package name, attributionTag, userId
            arrayOf(s, s, i),
            // Some systems extend the read to: package name, app name, privacy stack, attributionTag, userId, deviceId, whether it is a user action
            arrayOf(s, s, s, s, i, i, Boolean::class.javaPrimitiveType!!),
        )
        return candidates.firstNotNullOfOrNull { runCatching { type.getMethod(name, *it) }.getOrNull() }
    }

    private fun call(method: Method): Any? {
        val userId = Process.myUid() / 100_000
        val args: Array<Any?> = when (method.parameterTypes.size) {
            // Background save/restore is not the user tapping "paste", so no user action is faked; the privacy stack is empty like the framework default.
            7 -> arrayOf(ShellContext.SHELL_PACKAGE, callerLabel, "", null, userId, 0, false)
            // ShellContext is the system context of the default device, the same device the original ClipboardManager uses.
            4 -> arrayOf(ShellContext.SHELL_PACKAGE, null, userId, 0)
            else -> arrayOf(ShellContext.SHELL_PACKAGE, null, userId)
        }
        return method.invoke(service, *args)
    }
}
