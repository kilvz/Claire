package com.wangzan101.mika.core.run

/** Who started this run; kept completely separate from the Invoker of an individual action. */
enum class RunOrigin { Ui, Channel, Trigger, Mcp, Child }

/** Whether the user can handle questions or confirmations promptly during this run. */
enum class Attendance { Present, Reachable, Unattended }

data class RunProvenance(
    val origin: RunOrigin = RunOrigin.Ui,
    val attendance: Attendance = Attendance.Present,
)
