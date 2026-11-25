package com.wangzan101.mika.proactive

import com.wangzan101.mika.proactive.ListenerReconnect.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class ListenerReconnectTest {
    private fun next(
        watching: Boolean = true, locked: Boolean = false, connected: Boolean = false, authorized: Boolean = true, sdk: Int = 36,
    ) = ListenerReconnect.next(watching, locked, connected, authorized, sdk)

    // Granted, should be connected, still not connected after asking to rebind: make the system register it once more
    @Test fun authorizedButStillNotConnectedReregisters() = assertEquals(Step.Reregister, next())

    @Test fun connectedIsLeftAlone() = assertEquals(Step.None, next(connected = true))

    // The screen locked during the wait: it should not be connected while locked anyway
    @Test fun lockedIsLeftAlone() = assertEquals(Step.None, next(locked = true))

    // The feature was turned off, or the app fully exited
    @Test fun notWatchingIsLeftAlone() = assertEquals(Step.None, next(watching = false))

    // Notification access is gone: the user must grant it, and re-registering cannot fix that
    @Test fun unauthorizedIsLeftAlone() = assertEquals(Step.None, next(authorized = false))

    @Test fun onlyWhereTheSystemCanUnbindByName() {
        assertEquals(Step.None, next(sdk = 33))
        assertEquals(Step.Reregister, next(sdk = 34))
    }

    @Test fun theVersionMatchesThePlatformConstant() =
        assertEquals(android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE, ListenerReconnect.SDK_BY_NAME)
}
