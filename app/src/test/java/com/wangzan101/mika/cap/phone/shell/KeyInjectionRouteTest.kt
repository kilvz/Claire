package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Routing of key injection: when the uid of the virtual display's top task resolves, use targeted injection (dispatched straight to that window, unaffected by the global input focus,
 * otherwise the back key would go to the main display's focus and the app on the virtual display would never receive it); when it does not resolve, fall back to untargeted injection.
 *
 * What this fixes is "back does not work on the virtual display": injectKey never resolved a target uid and always used untargeted injection.
 */
class KeyInjectionRouteTest {
    @Test fun resolvesUidOnAndroid13Plus() {
        assertEquals(10086, ShellService.resolveTargetUid(sdkInt = 33, packageName = "com.foo", userId = 0) { _, _ -> 10086 })
    }

    @Test fun belowAndroid13HasNoTargetedRoute() {
        assertNull(ShellService.resolveTargetUid(sdkInt = 32, packageName = "com.foo", userId = 0) { _, _ -> 10086 })
    }

    @Test fun nullPackageHasNoTargetedRoute() {
        assertNull(ShellService.resolveTargetUid(sdkInt = 33, packageName = null, userId = 0) { _, _ -> 10086 })
    }

    @Test fun missingPackageHasNoTargetedRoute() {
        assertNull(ShellService.resolveTargetUid(sdkInt = 33, packageName = "com.foo", userId = 0) { _, _ -> -1 })
    }
}
