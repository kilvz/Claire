package com.wangzan101.mika.selfcheck

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.about.Diagnostics
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.core.selfcheck.CheckStatus
import com.wangzan101.mika.core.selfcheck.SelfCheckReport
import com.wangzan101.mika.core.selfcheck.SelfCheckRunner
import com.wangzan101.mika.core.selfcheck.SelfCheckState
import com.wangzan101.mika.settings.SystemStatusMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On a real device: run the compatibility self-check end to end, through the same [AndroidSelfCheck] used by the start-self-check action in settings.
 * Requires the built-in assistant to be running and Claire's accessibility service to be on; the report is written to logcat (tag MikaSelfCheck). By default it only requires the run to finish with a conclusion for every item;
 * with `-e selfcheck.strict true` it additionally requires that no item failed.
 */
@RunWith(AndroidJUnit4::class)
class SelfCheckDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val strict = InstrumentationRegistry.getArguments().getString("selfcheck.strict") == "true"

    @Test
    fun wholeSelfCheckCompletes() = runBlocking<Unit> {
        // Like the other device tests, first claim the shared assistant connection and force an accessibility rebind (starting the test force-stops the app)
        DeviceSupport.shell()
        DeviceSupport.service()
        val monitor = SystemStatusMonitor(context, PhoneServices.of(context).shellControl)
        val runner = SelfCheckRunner(AndroidSelfCheck(context) { monitor.refresh(); monitor.status.value })
        val s = withContext(Dispatchers.Default) { runner.run() }
        val report = SelfCheckReport.render(s, Diagnostics::sanitize)
        report.lines().forEach { Log.i(TAG, it) }

        assertEquals(SelfCheckState.Phase.Done, s.phase)
        assertTrue(report, s.results.all { it.status.final })
        assertTrue(report, s.elapsedMs < SelfCheckRunner.TOTAL_BUDGET_MS + 30_000)
        if (strict) assertTrue(report, s.results.none { it.status == CheckStatus.Fail })
    }

    private companion object {
        const val TAG = "MikaSelfCheck"
    }
}
