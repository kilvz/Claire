package com.wangzan101.mika.cap.phone.shell

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * destroy gets a class of its own: it terminates the shell process. Only the traces it can leave behind by itself are checked — clipboard restore, audio unmute, virtual display release, binder invalidation;
 * at teardown, wait for the connection to obtain a live new shell again (automatic recovery after the process dies) so that later test classes are unaffected.
 */
@RunWith(AndroidJUnit4::class)
class ShellDestroyParityDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var probe: ShellProbe
    private var screen: ParityScreen? = null
    private var dead: IBinder? = null
    private var audioBefore = "default"
    private var clipBefore: String? = null

    @Before
    fun setUp() {
        probe = ShellProbe(context)
        DeviceSupport.shell().restoreClip()
        audioBefore = ParityAudio.mode(SETTINGS)
        clipBefore = ParityClip.text(context)
    }

    @After
    fun tearDown() {
        runCatching { probe.close() }
        // releaseDisplay throws once the shell is dead; this only closes the local ImageReader
        runCatching { screen?.close() }
        ParityAudio.set(SETTINGS, audioBefore)
        ParityClip.put(context, clipBefore)
        val old = dead ?: return
        val fresh = pollUntil(RECOVER_MS) {
            runCatching { DeviceSupport.shell() }.getOrNull()?.takeIf { it.asBinder() != old }
        }
        assertNotNull("no live shell came back after destroy", fresh)
        ParityScreen(fresh!!, 720, 1600, 320).close()
    }

    @Test
    fun destroyUndoesEverythingAndEndsTheProcess() {
        val shell = DeviceSupport.shell()
        val s = ParityScreen(shell, 720, 1600, 320).also { screen = it }
        sh("am start --display ${s.id} -f 0x18000000 -n ${ShellProbe.COMPONENT}")
        assertNotNull(probe.await { it.kind == ShellProbe.RESUMED && it.display == s.id })
        val baseline = ParityAudio.mode(SETTINGS)
        assertEquals(true, shell.setAudioMuted(SETTINGS, true))
        assertEquals("ignore", ParityAudio.mode(SETTINGS))
        ParityClip.put(context, "parity-user")
        assertEquals(ShellService.CLIP_SET, shell.setClipText("parity-ours"))

        val binder = shell.asBinder().also { dead = it }
        // The process exits before replying, so the caller usually gets a DeadObjectException; a normal return also counts
        runCatching { shell.destroy() }

        assertNotNull("binder still alive", pollUntil(5_000) { true.takeIf { !binder.isBinderAlive } })
        val dm = context.getSystemService(DisplayManager::class.java)
        assertNotNull("display ${s.id} not released", pollUntil(5_000) { true.takeIf { dm.getDisplay(s.id) == null } })
        assertEquals("audio not unmuted", baseline, ParityAudio.mode(SETTINGS))
        assertEquals("clipboard not restored", "parity-user", ParityClip.text(context))
    }

    private companion object {
        // The new backend is brought up by the watchdog, with a longer interval than the old backend's immediate rebind
        const val RECOVER_MS = 60_000L
    }
}
