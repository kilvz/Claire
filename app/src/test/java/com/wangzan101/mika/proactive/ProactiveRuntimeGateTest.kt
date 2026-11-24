package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.LearnLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class ProactiveRuntimeGateTest {
    // Tools that read notifications: granted to tasks only when the proactive capability and the "daily message digest" are both on, and the aggressiveness level is not the most conservative one
    @Test fun tasksReadNoticesOnlyWhenEverySwitchIsOnAndTheLevelAllowsIt() {
        for (proactive in listOf(true, false)) for (digest in listOf(true, false)) for (level in LearnLevel.entries) {
            val expected = proactive && digest && level != LearnLevel.Conservative
            assertEquals("$proactive $digest $level", expected, ProactiveRuntime.tasksMayReadNotices(proactive, digest, level))
        }
    }
}
