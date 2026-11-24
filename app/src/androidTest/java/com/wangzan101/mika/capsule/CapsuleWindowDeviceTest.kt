package com.wangzan101.mika.capsule

import android.app.UiAutomation
import android.graphics.Bitmap
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.GestureTapTestActivity
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.settings.FeatureFlags
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** The real pair of overlay windows: it is not enough for the accessibility nodes to disappear after collapsing — no half a card may remain on the system canvas either. */
@RunWith(AndroidJUnit4::class)
class CapsuleWindowDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as MikaApp
    private val flags = FeatureFlags.get(app)

    @Test fun closedCardLeavesNoPixelsBehind() {
        val service = DeviceSupport.service()
        val oldMode = flags.capsuleMode()
        val oldQuit = flags.quitRequested()
        try {
            flags.setQuitRequested(false)
            flags.setCapsuleMode(CapsuleMode.Always)
            instrumentation.runOnMainSync { app.capsule.resume() } // The capsule controller only moves windows on the main thread
            DeviceSupport.startTestActivity(Display.DEFAULT_DISPLAY, GestureTapTestActivity::class.java)
            assertNotNull("test page did not open", pollUntil(5_000) { findText(service, "taps:0") })
            Thread.sleep(300) // Wait for the page's enter animation to finish, then record the static baseline used to detect residual frames
            assertNotNull("idle capsule did not appear", pollUntil(15_000) { find(service, CapsuleCopy.IDLE) })
            Thread.sleep(3_500) // Wait for the capsule's entrance animation (about 272ms) to finish and for the idle fade to complete (CapsuleFade.IDLE_MS=3s, alpha 1→0.4); otherwise the baseline is fully opaque while the capsule has already faded by the time the card takes >3s to close, which would be mistaken for residual frames
            val baseline = screenshot()

            assertTrue("idle capsule was not clickable", click(service, CapsuleCopy.IDLE))
            assertNotNull("card did not open", pollUntil(5_000) { find(service, CardCopy.COLLAPSE) })
            val opened = pollUntil(5_000) { changedFraction(baseline, screenshot()).takeIf { it > 0.002f } }
            assertNotNull("card did not visibly change the page", opened)

            assertTrue("card close button was not clickable", click(service, CardCopy.COLLAPSE))
            assertNotNull("card accessibility nodes did not close", pollUntil(5_000) {
                true.takeIf { find(service, CardCopy.COLLAPSE) == null }
            })
            val remaining = pollUntil(5_000) { changedFraction(baseline, screenshot()).takeIf { it <= 0.002f } }
            assertNotNull("card pixels remained after closing", remaining)
            Thread.sleep(300)
            assertTrue(
                "card pixels returned after closing",
                changedFraction(baseline, screenshot()) <= 0.002f,
            )
        } finally {
            find(service, CardCopy.COLLAPSE)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            flags.setCapsuleMode(oldMode)
            flags.setQuitRequested(oldQuit)
            instrumentation.runOnMainSync { app.capsule.resume() } // The capsule controller only moves windows on the main thread
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    /** The expanded card must settle on the full geometry: entirely on screen, horizontally centered, at the right height, never stuck in a half-open scale (#323 point 1). */
    @Test fun openedCardReachesFullCenteredGeometry() {
        val service = DeviceSupport.service()
        val oldMode = flags.capsuleMode()
        val oldQuit = flags.quitRequested()
        try {
            flags.setQuitRequested(false)
            flags.setCapsuleMode(CapsuleMode.Always)
            instrumentation.runOnMainSync { app.capsule.resume() } // The capsule controller only moves windows on the main thread
            DeviceSupport.startTestActivity(Display.DEFAULT_DISPLAY, GestureTapTestActivity::class.java)
            assertNotNull("test page did not open", pollUntil(5_000) { findText(service, "taps:0") })
            assertNotNull("idle capsule did not appear", pollUntil(15_000) { find(service, CapsuleCopy.IDLE) })
            assertTrue("idle capsule was not clickable", click(service, CapsuleCopy.IDLE))
            assertNotNull("card did not open", pollUntil(5_000) { find(service, CardCopy.COLLAPSE) })

            // Reference = the test page window (filling the main screen's usable area); card window = the overlay window that contains the collapse button (×)
            val screen = windowBounds(service) { it.text?.toString() == "taps:0" } ?: error("test page window not found")
            val card = pollUntil(5_000) { windowBounds(service) { it.contentDescription?.toString() == CardCopy.COLLAPSE } }
                ?: error("card window not found")
            assertTrue("card extends past the screen: $card vs $screen", card.left >= screen.left - 8 && card.right <= screen.right + 8)
            val drift = abs(card.centerX() - screen.centerX())
            assertTrue("card is not centered: drift ${drift}px of ${screen.width()}px", drift <= screen.width() * 0.06f)
            assertTrue(
                "card is shorter than a fully opened card: ${card.height()}px of ${screen.height()}px",
                card.height() >= screen.height() * 0.45f,
            )
        } finally {
            find(service, CardCopy.COLLAPSE)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            flags.setCapsuleMode(oldMode)
            flags.setQuitRequested(oldQuit)
            instrumentation.runOnMainSync { app.capsule.resume() } // The capsule controller only moves windows on the main thread
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    /** Screen bounds of the first window that has a node satisfying [match]; null if none. Overlay windows do not go through uiautomator, only our own service can see them. */
    private fun windowBounds(service: ScreenAccessibility, match: (AccessibilityNodeInfo) -> Boolean): android.graphics.Rect? {
        for (window in service.windowsOn(Display.DEFAULT_DISPLAY)) {
            val root = window.root ?: continue
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            var hit = false
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.isVisibleToUser && match(node)) {
                    hit = true
                    break
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            }
            if (hit) return android.graphics.Rect().also { window.getBoundsInScreen(it) }
        }
        return null
    }

    /** Our own service's overlays are in the window list too; only nodes visible to the user are considered, stale Compose trees in invisible windows do not count. */
    private fun find(service: ScreenAccessibility, description: String): AccessibilityNodeInfo? {
        for (window in service.windowsOn(Display.DEFAULT_DISPLAY)) {
            val root = window.root ?: continue
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.isVisibleToUser && node.contentDescription?.toString() == description) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            }
        }
        return null
    }

    private fun findText(service: ScreenAccessibility, text: String): AccessibilityNodeInfo? {
        for (window in service.windowsOn(Display.DEFAULT_DISPLAY)) {
            val root = window.root ?: continue
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.isVisibleToUser && node.text?.toString() == text) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            }
        }
        return null
    }

    private fun click(service: ScreenAccessibility, description: String): Boolean =
        find(service, description)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

    /** Pixels from the middle band of the page; avoids the status bar, the top capsule and the bottom navigation bar. */
    private fun screenshot(): IntArray {
        val bitmap = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        try {
            val x = bitmap.width / 10
            val y = bitmap.height / 3
            val width = bitmap.width * 8 / 10
            val height = bitmap.height / 3
            return IntArray(width * height).also { bitmap.getPixels(it, 0, width, x, y, width, height) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun changedFraction(before: IntArray, after: IntArray): Float {
        if (before.size != after.size) return 1f
        var changed = 0
        for (i in before.indices) {
            val a = before[i]
            val b = after[i]
            val difference = maxOf(
                abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff)),
                abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff)),
                abs((a and 0xff) - (b and 0xff)),
            )
            if (difference > 32) changed++
        }
        return changed.toFloat() / before.size
    }
}
