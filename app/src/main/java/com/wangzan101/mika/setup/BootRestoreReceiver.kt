package com.wangzan101.mika.setup

import com.wangzan101.mika.cap.phone.setup.BootRestoreHandler
import com.wangzan101.mika.task.TriggerRuntime
import android.content.Context
import android.content.Intent
import android.app.AlarmManager

/** Entry point for the boot broadcast: this fully qualified name is registered in the manifest; the implementation lives in the phone capability. */
class BootRestoreReceiver : BootRestoreHandler() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED) TriggerRuntime.rearm(context)
        // Timezone changed: local-time schedules that follow the device timezone are immediately recomputed for the new timezone (the alarms are re-armed internally); those explicitly pinned to another timezone are left alone
        if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) TriggerRuntime.syncTimeZone(context)
        // On boot, if a LAN-style computer connection was open, start listening again (USB does not survive a reboot: the boot ID changes, so it is already treated as off here, and the previous connection info is cleared too)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) (context.applicationContext as com.wangzan101.mika.MikaApp).mcp.ensureRunning()
        // AI calls were set up before: bring the call runtime back up, and once the built-in assistant reconnects it redoes the step that was lost after boot
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            com.wangzan101.mika.calls.CallTelecomAccess(context.applicationContext).preparedOnEarlierBoot()
        ) (context.applicationContext as com.wangzan101.mika.MikaApp).calls
    }
}
