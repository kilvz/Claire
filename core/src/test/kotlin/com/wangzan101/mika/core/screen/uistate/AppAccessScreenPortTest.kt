package com.wangzan101.mika.core.screen.uistate

import com.wangzan101.mika.core.device.access.AppAccessDenied
import com.wangzan101.mika.core.device.access.AppAccessPolicy
import com.wangzan101.mika.core.device.access.AppAccessState
import com.wangzan101.mika.core.tool.FakeScreen
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AppAccessScreenPortTest {
    @Test fun emptyBlocklistAllowsUnknownPackageWithoutProbe() = runTest {
        assertNull(AppAccessState().denial(null))
        val screen = AppAccessScreenPort(FakeScreen(), AppAccessPolicy.ALLOW,
            packages = { error("empty blocklist must not query windows") })
        screen.snapshot()
    }

    @Test fun unknownPackageIsReadOnceMore() = runTest {
        var probes = 0
        val screen = AppAccessScreenPort(FakeScreen(), blocked(), packages = {
            if (++probes == 1) listOf(null) else listOf("allowed")
        })
        screen.checkAppAccess()
        assertEquals(2, probes)
    }

    @Test fun stillUnknownPackageIsRefusedAfterOneRetry() = runTest {
        var probes = 0
        val screen = AppAccessScreenPort(FakeScreen(), blocked(), packages = { probes++; listOf(null) })
        denied("app_access_unverified") { screen.checkAppAccess() }
        assertEquals(2, probes)
    }

    @Test fun retryCanIdentifyBlockedPackage() = runTest {
        var probes = 0
        val screen = AppAccessScreenPort(FakeScreen(), blocked(), packages = {
            if (++probes == 1) listOf(null) else listOf("bank")
        })
        denied("app_blocked") { screen.checkAppAccess() }
        assertEquals(2, probes)
    }

    @Test fun unavailableSettingsDenyEvenWhenListIsEmpty() = runTest {
        val screen = AppAccessScreenPort(FakeScreen(), AppAccessPolicy { AppAccessState(available = false) },
            packages = { error("must reject before probing") })
        denied("app_access_unavailable") { screen.checkAppAccess() }
    }

    @Test fun uncreatedDisplayDoesNotNeedAnApp() = runTest {
        val screen = AppAccessScreenPort(FakeScreen(), blocked(), packages = { emptyList() })
        screen.checkAppAccess()
    }

    @Test fun sensitiveOwnUiIsProtectedEvenWithEmptyList() = runTest {
        val screen = AppAccessScreenPort(FakeScreen(), AppAccessPolicy.ALLOW, packages = { listOf("mika") },
            protectedUi = { true }, ownPackage = "mika")
        denied("app_access_protected") { screen.snapshot() }
    }

    @Test fun blocklistIsRereadAfterWaiting() = runTest {
        var state = AppAccessState(blocked = setOf("bank"))
        var probes = 0
        val screen = AppAccessScreenPort(FakeScreen(), AppAccessPolicy { state }, packages = {
            probes++
            if (probes == 2) state = AppAccessState()
            listOf(null)
        })
        screen.checkAppAccess()
        assertEquals(2, probes)
    }

    @Test fun runtimeBlockStopsNextReadAndAllowRestoresIt() = runTest {
        var state = AppAccessState()
        var reads = 0
        val inner = object : ScreenPort by FakeScreen() {
            override suspend fun screenshotPng(): ByteArray { reads++; return byteArrayOf(1) }
        }
        val screen = AppAccessScreenPort(inner, AppAccessPolicy { state }, packages = { listOf("bank") })
        assertArrayEquals(byteArrayOf(1), screen.screenshotPng())
        state = AppAccessState(blocked = setOf("bank"), revision = 1)
        denied("app_blocked") { screen.screenshotPng() }
        assertEquals(1, reads)
        state = AppAccessState(revision = 2)
        assertArrayEquals(byteArrayOf(1), screen.screenshotPng())
    }

    @Test fun readThatCrossesIntoBlockedAppDoesNotReturnContent() = runTest {
        var pkg = "allowed"
        val inner = object : ScreenPort by FakeScreen() {
            override suspend fun screenshotPng(): ByteArray { pkg = "bank"; return byteArrayOf(1) }
        }
        val screen = AppAccessScreenPort(inner, blocked(), packages = { listOf(pkg) })
        denied("app_blocked") { screen.screenshotPng() }
    }

    @Test fun canLeaveBlockedAppButCannotLaunchBlockedTarget() = runTest {
        val screen = AppAccessScreenPort(FakeScreen(), blocked(), packages = { listOf("bank") })
        screen.openApp("allowed")
        denied("app_blocked") { screen.openApp("bank") }
        denied("app_blocked") { screen.openAppCopy("bank", 10) }
        denied("app_blocked") { screen.shareText("bank", "text") }
        denied("app_blocked") { screen.awaitAppFree("bank", null, 1_000) }
    }

    // ---------- every port member goes through this boundary ----------

    /** Every port member is forwarded explicitly to the inner port inside the boundary: one that is forgotten falls through to the interface's default implementation and never reaches the inner port. */
    @Test fun everyPortMemberIsForwardedExplicitly() {
        val reached = mutableSetOf<String>()
        val screen = AppAccessScreenPort(recordingPort(reached), AppAccessPolicy.ALLOW, packages = { listOf("bank") })
        for (m in portMembers()) {
            reached.clear()
            invoke(screen, m)
            assertTrue("${m.name} is not forwarded explicitly by AppAccessScreenPort", m.name in reached)
        }
    }

    /** While a blocked app is in the foreground, every port member is rejected except the few that explicitly do not touch the app UI; a newly added member is assumed to need checking. */
    @Test fun everyPortMemberExceptPassThroughIsChecked() {
        val screen = AppAccessScreenPort(recordingPort(mutableSetOf()), blocked(), packages = { listOf("bank") })
        for (m in portMembers()) {
            val error = runCatching { invoke(screen, m) }.exceptionOrNull()
            if (m.name in PASS_THROUGH) assertNull("${m.name} should pass through", error)
            else assertTrue("${m.name} is not checked by AppAccessScreenPort (got $error)", error is AppAccessDenied)
        }
    }

    private fun portMembers(): List<Method> = ScreenPort::class.java.methods.filter { it.name !in OWN }

    /** A port that records only names; screen reads return one screen whose foreground is bank. */
    private fun recordingPort(reached: MutableSet<String>): ScreenPort =
        Proxy.newProxyInstance(ScreenPort::class.java.classLoader, arrayOf(ScreenPort::class.java)) { _, m, _ ->
            reached += m.name
            when {
                m.name == "snapshot" || m.name == "peek" -> ScreenSnapshot("s", "bank", emptyList(), 1, 1)
                m.returnType == Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        } as ScreenPort

    /** Invokes once with a sample value per parameter type (a suspend function's last parameter is a continuation, and none of the calls here really suspend); a thrown exception is rethrown as-is. */
    private fun invoke(screen: ScreenPort, m: Method) {
        val args = m.parameterTypes.map { t ->
            when (t) {
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Double::class.javaPrimitiveType -> 0.0
                Boolean::class.javaPrimitiveType -> false
                String::class.java -> ""
                Int::class.javaObjectType -> null
                List::class.java -> emptyList<Any>()
                SystemAction::class.java -> SystemAction.ShowAlarms
                Continuation::class.java -> Continuation<Any?>(EmptyCoroutineContext) {}
                else -> error("no sample for ${t.name} in ${m.name}: add one")
            }
        }
        try {
            m.invoke(screen, *args.toTypedArray())
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    // ---------- probe the windows less ----------

    @Test fun snapshotIsCheckedAgainstItsOwnWindowsWithoutProbing() = runTest {
        val inner = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = withWindows("allowed")
        }
        val screen = AppAccessScreenPort(inner, blocked(), packages = { error("snapshot carries its windows") })
        assertEquals("allowed", screen.snapshot().packageName)
        val bank = AppAccessScreenPort(object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = withWindows("bank")
        }, blocked(), packages = { error("snapshot carries its windows") })
        denied("app_blocked") { bank.snapshot() }
    }

    @Test fun snapshotWithUnreadableWindowStillRetriesOnce() = runTest {
        var probes = 0
        val inner = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = withWindows(null)
        }
        val screen = AppAccessScreenPort(inner, blocked(), packages = { probes++; listOf("bank") })
        denied("app_blocked") { screen.snapshot() }
        assertEquals(1, probes)
    }

    @Test fun readsRightAfterASnapshotReuseItsCheckWhileWindowsStay() = runTest {
        var token = 0L
        var now = 0L
        var probes = 0
        val inner = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = withWindows("allowed")
        }
        val screen = AppAccessScreenPort(inner, blocked(), packages = { probes++; listOf("allowed") },
            windowToken = { token }, clock = { now })
        screen.snapshot()
        screen.screenshotPng()
        screen.frameGrid()
        repeat(10) { screen.checkAppAccess() }
        assertEquals(0, probes)
        // The windows changed: probe again, and reuse the result afterwards
        token++
        screen.screenshotPng()
        screen.screenshotPng()
        assertEquals(1, probes)
        // Past the reuse time limit: probe again
        now += AppAccessScreenPort.CHECK_TTL_MS + 1
        screen.checkAppAccess()
        assertEquals(2, probes)
    }

    @Test fun reusedCheckIsDroppedWhenTheListChanges() = runTest {
        var state = AppAccessState(blocked = setOf("other"), revision = 1)
        val screen = AppAccessScreenPort(FakeScreen(), AppAccessPolicy { state }, packages = { listOf("bank") },
            windowToken = { 0L }, clock = { 0L })
        screen.checkAppAccess()
        state = AppAccessState(blocked = setOf("bank"), revision = 2)
        denied("app_blocked") { screen.checkAppAccess() }
    }

    @Test fun emptyListDoesNoExtraWorkOnAnyRead() = runTest {
        val screen = AppAccessScreenPort(FakeScreen(), AppAccessPolicy.ALLOW,
            packages = { error("empty list must not query windows") }, windowToken = { error("empty list must not read the window count") })
        screen.snapshot()
        screen.peek()
        screen.screenshotPng()
        screen.tapXY(1, 1)
        screen.checkAppAccess()
    }

    private fun withWindows(vararg packages: String?) = ScreenSnapshot("s", packages.firstOrNull(), emptyList(), 1, 1,
        windows = packages.mapIndexed { i, p -> UiWindowTree(i, "application", i, null, p, i == 0, i == 0, emptyList()) })

    private fun blocked() = AppAccessPolicy { AppAccessState(blocked = setOf("bank")) }

    private suspend fun denied(code: String, body: suspend () -> Unit) {
        try {
            body()
            fail("expected $code")
        } catch (e: AppAccessDenied) {
            assertEquals(code, e.code)
        }
    }

    private companion object {
        /** The boundary's own members. */
        val OWN = setOf("getAppAccess", "checkAppAccess")

        /** Members that touch no app UI and are passed through unchanged; system actions that pick an app by target are checked against the list by the port itself. Adding one here requires a stated reason. */
        val PASS_THROUGH = setOf("systemAction", "systemCommand", "bulkCalendarInsert", "listApps", "otherProfiles", "appCopies", "getAfterTask", "getCanShareAudio", "getCanShareImage")
    }
}
