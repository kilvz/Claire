package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.net.ProxyFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Project 3 failover decision table: which errors switch to the next model and which do not, pinned down per category. */
class FailoverTest {
    private val tags = listOf("OpenRouter", "APInex", "Groq")

    @Test fun quotaSwitchesImmediately() {
        assertEquals(1, Failover.nextIndex(ModelError.QuotaExceeded(), 0, tags, 3))
    }

    @Test fun modelSideFailuresSwitch() {
        assertEquals(1, Failover.nextIndex(ModelError.Auth(), 0, tags, 3))
        assertEquals(1, Failover.nextIndex(ModelError.IllegalHeader("Authorization"), 0, tags, 3))
        assertEquals(1, Failover.nextIndex(ModelError.ModelNotFound("x"), 0, tags, 3))
        assertEquals(1, Failover.nextIndex(ModelError.ModelNotAvailable("x"), 0, tags, 3))
        assertEquals(1, Failover.nextIndex(ModelError.ToolsUnsupported("x"), 0, tags, 3))
        assertEquals(1, Failover.nextIndex(ModelError.SignInExpired(), 0, tags, 3))
        assertEquals(1, Failover.nextIndex(ModelError.AccountPlanNotAllowed("xAI"), 0, tags, 3))
        assertEquals(2, Failover.nextIndex(ModelError.ChatGptLimitReached(), 1, tags, 3))
        assertEquals(1, Failover.nextIndex(ModelError.ChatGptNotEligible(), 0, tags, 3))
    }

    @Test fun transientFailuresSwitchAfterRetryGivesUp() {
        assertEquals(1, Failover.nextIndex(ModelError.RateLimited(null), 0, tags, 3))
        assertEquals(1, Failover.nextIndex(ModelError.Server(503), 0, tags, 3))
        assertEquals(1, Failover.nextIndex(ModelError.Server(400), 0, tags, 3))
        assertEquals(1, Failover.nextIndex(ModelError.Network(), 0, tags, 3))
        assertEquals(1, Failover.nextIndex(ModelError.StreamCut(), 0, tags, 3))
        assertEquals(1, Failover.nextIndex(ModelError.Proxy(ProxyFailure.Unreachable), 0, tags, 3))
    }

    @Test fun groupUnavailableSwitchesToNextChainLink() {
        // No usable member is left in the group / the group is exhausted: must be able to advance to the next link when the chain still has a healthy model
        assertEquals(1, Failover.nextIndex(ModelError.GroupUnavailable("g"), 0, tags, 3))
        assertEquals(1, Failover.nextIndex(ModelError.GroupExhausted("g"), 0, tags, 3))
        assertNull(Failover.nextIndex(ModelError.GroupUnavailable("g"), 1, tags, 2))
    }

    @Test fun taskBudgetNeverSwitches() {
        assertNull(Failover.nextIndex(ModelError.TaskBudgetExceeded(100, 100), 0, tags, 3))
    }

    @Test fun dailyBudgetOnlySwitchesAcrossProviders() {
        val blown = ModelError.DailyBudgetExceeded("Gateway", 100, 100, providerId = "primary-id")
        // The next one is the same provider: do not switch
        assertNull(Failover.nextIndex(blown, 0, listOf("primary-id", "primary-id"), 2))
        // Skip same-provider entries and switch to a different provider
        assertEquals(2, Failover.nextIndex(blown, 0, listOf("primary-id", "primary-id", "backup-id"), 3))
        // Do not switch when the fallback's provider identity is unknown.
        assertNull(Failover.nextIndex(blown, 0, listOf("primary-id", null), 2))
    }

    @Test fun nonModelFailuresDoNotSwitch() {
        assertNull(Failover.nextIndex(ModelError.ContentRefused(), 0, tags, 3))
        assertNull(Failover.nextIndex(ModelError.BadResponse("x"), 0, tags, 3))
        assertNull(Failover.nextIndex(ModelError.ContextTooLong(), 0, tags, 3))
        assertNull(Failover.nextIndex(ModelError.ReasoningEffortRejected("x"), 0, tags, 3))
    }

    @Test fun chainEndDoesNotSwitch() {
        assertNull(Failover.nextIndex(ModelError.QuotaExceeded(), 2, tags, 3))
        assertNull(Failover.nextIndex(ModelError.QuotaExceeded(), 0, tags, 1))
    }
}
