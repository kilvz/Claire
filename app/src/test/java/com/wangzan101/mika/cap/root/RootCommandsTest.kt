package com.wangzan101.mika.cap.root

import com.wangzan101.mika.core.cap.root.RootRun
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RootCommandsTest {
    /** Turning the setting off after a task has already obtained its port must stop the old port from launching su again, and must certainly not record it as a denied authorization. */
    @Test
    fun turningTheSettingOffStopsAnExistingPort() = runBlocking {
        var suCalls = 0
        var refusalCalls = 0
        val commands = RootCommands(
            su = { suCalls++; "/system/xbin/su" },
            allowed = { false },
            refused = { refusalCalls++ },
        )

        assertEquals(RootRun.Denied, commands.run("id", 1_000))
        assertEquals(0, suCalls)
        assertEquals(0, refusalCalls)
    }
}
