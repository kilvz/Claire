package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalPolicyTest {
    @Test fun onlyPaymentAsks() {
        for (level in ActionRisk.entries) {
            assertEquals("$level", Approval.Allow, ApprovalPolicy.decide(Risk(level)))
            assertEquals("$level payment", Approval.Ask, ApprovalPolicy.decide(Risk(level, payment = true)))
        }
    }

    @Test fun everyOriginAndAttendance() {
        for (origin in RunOrigin.entries) for (attendance in Attendance.entries) for (level in ActionRisk.entries) {
            val run = RunProvenance(origin, attendance)
            // Without money: irreversible actions are not done unattended / off the phone; the rest (read, change, outward but revocable) is done whoever starts it
            val unattended = origin != RunOrigin.Ui || attendance == Attendance.Unattended
            val noMoney = if (level == ActionRisk.Irreversible && unattended) Approval.Deny else Approval.Allow
            assertEquals("$origin $attendance $level", noMoney, ApprovalPolicy.decide(Risk(level), run))
            // With money: ask only when started on the phone with a person present; when no one can be asked (channel, scheduled, MCP, subtask, unattended), don't do it
            val expected = if (origin == RunOrigin.Ui && attendance != Attendance.Unattended) Approval.Ask else Approval.Deny
            assertEquals("$origin $attendance $level payment", expected, ApprovalPolicy.decide(Risk(level, payment = true), run))
        }
    }
}
