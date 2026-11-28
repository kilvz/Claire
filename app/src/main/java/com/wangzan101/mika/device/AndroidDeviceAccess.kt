package com.wangzan101.mika.device

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import android.text.InputType
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.device.CredentialKind
import com.wangzan101.mika.core.device.DeviceAccess
import com.wangzan101.mika.core.device.DeviceAccessPort
import com.wangzan101.mika.core.device.DeviceCredential
import com.wangzan101.mika.core.device.DeviceState
import com.wangzan101.mika.settings.DeviceAccessSettings
import com.wangzan101.mika.settings.KeystoreKeyCipher
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** App-level singleton: shared by the settings page and authenticated MCP, never routed through the model, the screen tools or the run record. */
class AndroidDeviceAccess(context: Context, helper: () -> IMikaShell?) {
    val settings = DeviceAccessSettings(File(context.filesDir, "settings"), KeystoreKeyCipher("mika-device-access"))
    val access = DeviceAccess(AndroidDeviceAccessPort(context, helper), settings)
}

internal class AndroidDeviceAccessPort(context: Context, private val helper: () -> IMikaShell?) : DeviceAccessPort {
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val user = context.getSystemService(UserManager::class.java)

    override suspend fun state() = withContext(Dispatchers.IO) {
        DeviceState(power.isInteractive, keyguard.isKeyguardLocked, keyguard.isDeviceLocked,
            keyguard.isDeviceSecure, helper() != null, user.isUserUnlocked)
    }

    override suspend fun wake(): Boolean = withContext(Dispatchers.IO) { runCatching { helper()?.wakeDevice() == true }.getOrDefault(false) }
    override suspend fun dismiss(): Boolean = withContext(Dispatchers.IO) { runCatching { helper()?.dismissDeviceKeyguard() == true }.getOrDefault(false) }
    override suspend fun revealCredential(): Boolean = withContext(Dispatchers.IO) {
        runCatching { helper()?.revealDeviceCredential() == true }.getOrDefault(false)
    }

    /**
     * When, after the swipe-up, it is still the biometric-first page and there is no credential field, tap the largest clickable non-digit target in SystemUI once (a big button such as "unlock with PIN").
     * Only large-area targets are accepted; small text links such as "cancel" and "emergency call" are excluded; if nothing qualifies, nothing is tapped.
     */
    override suspend fun revealCredentialFallback(): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@withContext false
        if (!power.isInteractive || !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked) return@withContext false
        val service = ScreenAccessibility.instance.value ?: return@withContext false
        for (keyguardRoot in keyguardRoots(service).roots) {
            val root = keyguardRoot.root
            val windowBounds = keyguardRoot.bounds
            val candidates = mutableListOf<KeyguardReveal.Candidate>()
            fun visit(n: AccessibilityNodeInfo, depth: Int) {
                if (depth > 40) return
                val sim = "sim" in n.viewIdResourceName.orEmpty().lowercase()
                if (n.isVisibleToUser && !sim && n.isEnabled && n.isClickable) {
                    val digit = KeyguardFields.digitKey(n.text?.toString(), n.contentDescription?.toString()) != null
                    val b = Rect().also(n::getBoundsInScreen)
                    candidates += KeyguardReveal.Candidate(
                        KeyguardReveal.Bounds(b.left, b.top, b.right, b.bottom), clickable = true, digit = digit)
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1) }
            }
            visit(root, 0)
            val target = KeyguardReveal.fallbackTarget(candidates,
                KeyguardReveal.Bounds(windowBounds.left, windowBounds.top, windowBounds.right, windowBounds.bottom),
                MIN_FALLBACK_AREA_FRACTION)
            if (target != null) {
                val (cx, cy) = KeyguardReveal.center(target)
                val ok = runCatching { helper()?.revealDeviceCredentialTap(cx, cy) == true }.getOrDefault(false)
                Log.i(TAG, "keyguard: credential fallback tap at ($cx,$cy) sent=$ok")
                return@withContext ok
            }
        }
        false
    }

    /**
     * Only accepts the single, type-matching password field in the main display's SystemUI. SIM PIN / unrecognizable pages are not attempted.
     * When there is no password node, a complete and consistently laid-out numeric keypad can serve as the PIN input target; ordinary password and pattern pages are not downgraded.
     * Credential key events must be injected only into the SystemUI window, relying on the targeted injection available from Android 13; older systems report unsupported before anything is held down, leaving no pause behind.
     * When the input field does not have focus, ask it to focus first, and click it once if that fails; for a pure numeric keypad the container is not clicked, and only an already-focused SystemUI window is accepted.
     * When it cannot be recognized, one line recording the reason is logged, containing no on-screen text.
     */
    override suspend fun credentialReady(kind: CredentialKind): Boolean = withContext(Dispatchers.IO) {
        val fields = credentialFields().filter { it.kind == kind }
        val target = fields.singleOrNull() ?: return@withContext false.also {
            Log.i(TAG, "keyguard: ${fields.size} matching field(s) for $kind")
        }
        val (field, window) = target
        val ready = if (field == null) window.focused else field.isFocused ||
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
            field.performAction(AccessibilityNodeInfo.ACTION_CLICK) || window.focused
        if (!ready) Log.i(TAG, "keyguard: target found but its SystemUI window is not focused")
        ready
    }

    /** Read-only identification of the single current system credential input target: no focusing, no input, and no guessing the lock screen type from the container's name. */
    override suspend fun detectedCredentialKind(): CredentialKind? = withContext(Dispatchers.IO) {
        scanCredentialFields().targets.singleOrNull()?.kind
    }

    /** The restricted diagnostics MCP can return directly: only technical attributes of SystemUI windows, password nodes and the numeric keypad, with no node text. */
    override suspend fun credentialDiagnostics(): List<String> = withContext(Dispatchers.IO) {
        scanCredentialFields().diagnostics.also { Log.i(TAG, "keyguard diagnostics: ${it.joinToString()}") }
    }

    private data class CredentialTarget(val node: AccessibilityNodeInfo?, val window: KeyguardRoot, val kind: CredentialKind)

    /** One readable piece of SystemUI lock screen content. When falling back to the active window root, the window object may be unavailable, so bounds and focus are taken from the root. */
    private class KeyguardRoot(val window: AccessibilityWindowInfo?, val root: AccessibilityNodeInfo) {
        val bounds: Rect = Rect().also { if (window != null) window.getBoundsInScreen(it) else root.getBoundsInScreen(it) }
        val focused: Boolean get() = window?.isFocused ?: false
    }

    private class KeyguardRootScan(val roots: List<KeyguardRoot>, val displayWindows: List<AccessibilityWindowInfo>,
        val diagnostic: String)

    /** See [KeyguardRoots]: when the main display's window list has no SystemUI, try the legacy window list and the active window root in order. */
    private fun keyguardRoots(service: ScreenAccessibility): KeyguardRootScan {
        val displayWindows = service.windowsOn(Display.DEFAULT_DISPLAY)
        val displayIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { service.windowsOnAllDisplays.let { all -> (0 until all.size()).map(all::keyAt) } }.getOrDefault(emptyList())
        } else listOf(Display.DEFAULT_DISPLAY)
        var legacyCount = 0
        var activePackage: CharSequence? = null
        fun rootsOf(windows: List<AccessibilityWindowInfo>) = windows.mapNotNull { w -> w.root?.let { KeyguardRoot(w, it) } }
        val pick = KeyguardRoots.pick(
            display = rootsOf(displayWindows),
            legacy = {
                @Suppress("DEPRECATION")
                val legacy = runCatching { service.windows.orEmpty() }.getOrDefault(emptyList())
                legacyCount = legacy.size
                rootsOf(legacy)
            },
            activeRoot = {
                runCatching { service.rootInActiveWindow }.getOrNull()?.let { root ->
                    activePackage = root.packageName
                    KeyguardRoot(runCatching { root.window }.getOrNull(), root)
                }
            },
            systemUi = { it.root.packageName?.toString() == SYSTEM_UI },
        )
        val diagnostic = KeyguardDiagnostics.source(pick.source.tag, displayIds, legacyCount, activePackage)
        if (pick.source != KeyguardRoots.Source.Display) Log.i(TAG, "keyguard: $diagnostic")
        return KeyguardRootScan(pick.roots, displayWindows, diagnostic)
    }
    private data class CredentialScan(val targets: List<CredentialTarget>, val diagnostics: List<String>)

    private fun credentialFields(): List<CredentialTarget> = scanCredentialFields().targets

    private fun scanCredentialFields(): CredentialScan {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return CredentialScan(emptyList(), listOf("api_below_33"))
        }
        if (!power.isInteractive || !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked) {
            return CredentialScan(emptyList(), listOf("keyguard_not_active"))
        }
        val service = ScreenAccessibility.instance.value
            ?: return CredentialScan(emptyList(), listOf("accessibility_unavailable")).also {
                Log.i(TAG, "keyguard: accessibility off")
            }
        val fields = mutableListOf<CredentialTarget>()
        val pinPads = mutableListOf<CredentialTarget>()
        val diagnostics = mutableListOf<String>()
        var passwordNodes = 0
        val scan = keyguardRoots(service)
        val windows = scan.displayWindows
        diagnostics += "display_windows=${windows.size}"
        windows.take(MAX_WINDOW_DIAGNOSTICS).forEachIndexed { index, window ->
            diagnostics += KeyguardDiagnostics.windowInfo(index, window.type, window.root?.packageName,
                window.isFocused, window.isActive)
        }
        val systemUi = scan.roots
        diagnostics += "systemui_windows=${systemUi.size}"
        diagnostics += scan.diagnostic
        for ((windowIndex, window) in systemUi.withIndex()) {
            val root = window.root
            val digits = mutableSetOf<Char>()
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            val padKeys = mutableListOf<KeyguardPinPad.DigitKey>()
            fun visit(n: AccessibilityNodeInfo, depth: Int, sim: Boolean = false) {
                if (depth > 40) return
                val id = n.viewIdResourceName.orEmpty()
                val simChallenge = sim || "sim" in id.lowercase()
                if (n.isVisibleToUser && !simChallenge) {
                    if (n.isPassword) candidates += n
                    else KeyguardFields.digitKey(n.text?.toString(), n.contentDescription?.toString())?.let { digit ->
                        digits += digit
                        if (n.isEnabled && (n.isClickable || n.isFocusable)) {
                            val bounds = Rect().also(n::getBoundsInScreen)
                            padKeys += KeyguardPinPad.DigitKey(digit,
                                KeyguardPinPad.Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom))
                        }
                    }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1, simChallenge) }
            }
            visit(root, 0)
            passwordNodes += candidates.size
            val pad = KeyguardFields.isDigitPad(digits)
            val bounds = window.bounds
            val pinLayout = KeyguardPinPad.matches(padKeys,
                KeyguardPinPad.Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom))
            diagnostics += KeyguardDiagnostics.window(windowIndex, window.focused, candidates.size,
                digits.size, padKeys.size, pinLayout)
            for (n in candidates) {
                val cls = n.inputType and InputType.TYPE_MASK_CLASS
                val numeric = cls == InputType.TYPE_CLASS_NUMBER
                val untyped = n.inputType == InputType.TYPE_NULL
                val kind = CredentialKind.entries.singleOrNull { kind ->
                    KeyguardFields.matches(kind, n.viewIdResourceName.orEmpty(), n.className?.toString().orEmpty(), n.isPassword,
                        n.isEditable, numeric = numeric, untyped = untyped, digitPad = pad)
                }
                if (diagnostics.count { it.startsWith("field[") } < MAX_FIELD_DIAGNOSTICS) {
                    diagnostics += KeyguardDiagnostics.field(n.viewIdResourceName, n.className, n.isEditable,
                        numeric, untyped, pad, n.isFocused, kind)
                }
                if (kind != null) fields += CredentialTarget(n, window, kind)
            }
            if (pinLayout) pinPads += CredentialTarget(null, window, CredentialKind.Pin)
        }
        // With password nodes present, stay on the original path: even if they cannot be recognized, nothing is guessed; only when there are none is the pure numeric PIN page considered.
        val targets = if (passwordNodes == 0) pinPads else fields
        return CredentialScan(targets, diagnostics.take(MAX_DIAGNOSTICS))
    }

    override suspend fun enter(credential: DeviceCredential): Boolean {
        if (!credentialReady(credential.kind)) return false
        return withContext(Dispatchers.IO) {
            val clear = residualClear(credential.kind)
            val bytes = ByteArray(credential.value.size) { credential.value[it].code.toByte() }
            try { runCatching { helper()?.enterDeviceCredential(credential.kind.ordinal, bytes, clear) == true }.getOrDefault(false) }
            finally { bytes.fill(0) }
        }
    }

    /** When the credential field already has input, backspace as many times as its current length; an empty field (the most common case, and also the biometric-first page) is not cleared, so a DEL on an empty field cannot dismiss the password page. */
    private fun residualClear(kind: CredentialKind): Int {
        val target = credentialFields().singleOrNull { it.kind == kind } ?: return 0
        val node = target.node ?: return 0
        val length = runCatching { node.text?.length ?: 0 }.getOrDefault(0)
        return length.coerceIn(0, MAX_CLEAR)
    }

    private companion object {
        const val TAG = "MikaDeviceAccess"
        const val SYSTEM_UI = "com.android.systemui"
        const val MAX_WINDOW_DIAGNOSTICS = 6
        const val MAX_FIELD_DIAGNOSTICS = 8
        const val MAX_DIAGNOSTICS = 20
        const val MAX_CLEAR = 16
        const val MIN_FALLBACK_AREA_FRACTION = 0.08f
    }
}
