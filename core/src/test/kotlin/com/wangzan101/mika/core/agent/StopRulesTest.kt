package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.safety.guard.GuardVerdict
import com.wangzan101.mika.core.safety.guard.Severity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Outcome wording when injection does not work on self-drawn / anti-automation UIs (WeChat, the GitHub client, etc.):
 * tell the user "the app does not respond to automation" rather than only the symptom "the controls cannot be read".
 */
class StopRulesTest {
    @Test fun treeBlindNoProgressSaysAppUnresponsive() {
        val v = GuardVerdict("no_progress", Severity.Stop, buildJsonObject { put("treeBlind", true) })
        assertTrue(StopRules.guard(v).reason.contains("may not respond"))
    }

    @Test fun plainNoProgressKeepsGenericMessage() {
        val v = GuardVerdict("no_progress", Severity.Stop, buildJsonObject { put("unchanged", 3) })
        assertTrue(StopRules.guard(v).reason.contains("the screen did not change after several actions"))
    }
}
