package com.wangzan101.mika.core.eval

import org.junit.Assert.assertEquals
import org.junit.Test

class EvalPairingTest {
    private fun t(id: String, vararg apps: String) =
        EvalTask(id = id, goal = "g", apps = apps.toList(), category = EvalCategory.Read, expect = Expect.Succeeded, checks = emptyList())

    // Only two tasks whose apps are disjoint are paired; greedy in task order
    @Test fun pairsDisjointAppsGreedily() {
        val tasks = listOf(t("a", "settings"), t("b", "settings"), t("c", "clock"), t("d", "clock", "settings"), t("e", "calc"))
        assertEquals(listOf(listOf("a", "c"), listOf("b", "e"), listOf("d")), EvalPairing.groups(tasks, 2).map { g -> g.map { it.id } })
    }

    // A task that doesn't say which apps it uses may collide, so it runs alone; n=1 means serial
    @Test fun unknownAppsRunAlone() {
        val tasks = listOf(t("a"), t("b", "clock"), t("c", "calc"))
        assertEquals(listOf(listOf("a"), listOf("b", "c")), EvalPairing.groups(tasks, 2).map { g -> g.map { it.id } })
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c")), EvalPairing.groups(tasks, 1).map { g -> g.map { it.id } })
    }
}
