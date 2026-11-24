package com.wangzan101.mika.cap.phone.setup

import android.content.Context
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.a11y.SystemPickers
import com.wangzan101.mika.core.device.shell.BuiltinShellControl
import com.wangzan101.mika.core.device.shell.PairingDiscovery
import com.wangzan101.mika.core.device.shell.PairingScreen
import com.wangzan101.mika.core.device.shell.PhoneSetupFlow
import com.wangzan101.mika.core.device.shell.PairingScreenReading
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * During pairing, borrow Claire's accessibility service to read the pairing code off the system pairing window, and pair right away once it is read, so the user does not have to type it from the notification.
 * It runs only while [PairingCoordinator] is active and looks only at system settings windows; a code that is read is never logged.
 * With accessibility off it does nothing, and pairing still goes through the notification.
 */
internal object PairingCodeReader {
    /** Window content often appears in several updates, so wait for it to settle before reading. */
    private const val SETTLE_MS = 300L
    private const val MAX_NODES = 400
    private const val TAG = "MikaPairing"

    suspend fun run(context: Context, control: BuiltinShellControl, coordinator: PairingCoordinator) {
        val settings = SystemPickers.settingsPackage(context.packageManager) ?: run {
            Log.w(TAG, "no system settings app found; not reading the code")
            return
        }
        val tried = mutableSetOf<String>()
        var last: String? = null
        ScreenAccessibility.instance.collectLatest { service ->
            if (service == null) {
                Log.i(TAG, "accessibility not running; the code goes through the notification")
                return@collectLatest
            }
            Log.i(TAG, "reading the pairing window of $settings")
            service.eventsFor(Display.DEFAULT_DISPLAY)
                .filter { it.packageName?.toString() == settings }
                .map { }
                .onStart { emit(Unit) }
                .conflate()
                .collect {
                    delay(SETTLE_MS)
                    val portFound = control.pairingDiscovery.value is PairingDiscovery.Found
                    val status = PhoneSetupFlow.effectiveStatus(control.status.value, coordinator.repairing.value)
                    val readings = readings(service, settings)
                    // Log a line only when what is seen differs from last time; the code itself is not logged, only whether one exists
                    val seen = "${readings.size} window(s), code ${readings.any { it.code != null }}, port ${readings.firstNotNullOfOrNull { it.port }}, port found $portFound, status $status"
                    if (seen != last) {
                        Log.i(TAG, "saw $seen")
                        last = seen
                    }
                    val attempt = readings.firstNotNullOfOrNull {
                        PairingScreen.attempt(it, portFound, status, tried)
                    } ?: return@collect
                    Log.i(TAG, "read a code from the screen; pairing")
                    tried += attempt.code
                    coordinator.pair(attempt.code, attempt.port)
                }
        }
    }

    /** Read each system settings window once: the pairing code and the port must come from the same window (the page below the pairing window also shows an "IP:port"). */
    private fun readings(service: ScreenAccessibility, settings: String): List<PairingScreenReading> =
        service.windowsOn(Display.DEFAULT_DISPLAY).mapNotNull { w ->
            val root = runCatching { w.root }.getOrNull() ?: return@mapNotNull null
            if (root.packageName?.toString() != settings) return@mapNotNull null
            PairingScreen.read(texts(root))
        }

    private fun texts(root: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        val queue = ArrayDeque(listOf(root))
        var seen = 0
        while (queue.isNotEmpty() && seen++ < MAX_NODES) {
            val n = queue.removeFirst()
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return out
    }
}
