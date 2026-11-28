package com.wangzan101.mika.task.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wangzan101.mika.R
import com.wangzan101.mika.device.ui.DeviceAccessSection
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.DeviceAccessSettings
import com.wangzan101.mika.task.TriggerRuntime
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons

/**
 * "Run with the screen off": lists the three things a plan needs to run on time with the screen off and locked (exact alarms, battery policy, lock screen unlock); tap whichever is not ready to deal with it.
 * Reads state only and jumps to standard system settings pages; unlock credentials are set with [DeviceAccessSection] in a FLAG_SECURE dialog, the same setup as the task run page.
 * When [showAlarm] is false, exact alarms are not listed (the plans page already has that prompt card at the top).
 */
@Composable
internal fun ScreenOffSetupCard(settings: DeviceAccessSettings, showAlarm: Boolean = true) {
    val context = LocalContext.current
    val config by settings.config.collectAsState()
    // Re-read when returning from system settings: permissions, battery policy and lock screen method just changed must be reflected immediately
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose {}
    }
    val system = remember(resumes) { readSystem(context) }
    val readiness = ScreenOffReadiness(system.exactAlarm, system.battery, unlockReadiness(config, system.deviceSecure))
    var configuring by remember { mutableStateOf(false) }
    var openFailed by remember { mutableStateOf(false) }
    fun open(vararg intents: Intent) {
        openFailed = !openFirst(context, *intents)
    }
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    GroupLabel(tr(R.string.plans_screen_off_title))
    GroupCard {
        if (showAlarm) {
            ListRow(
                MikaIcons.Alarm, tr(R.string.plans_screen_off_alarm), first = true,
                onClick = { open(TriggerRuntime.exactAlarmSettings(context), appDetails) },
                modifier = Modifier.testTag("plan-screen-off-alarm"),
            ) {
                RowValue(tr(if (readiness.exactAlarm) R.string.state_on else R.string.state_off),
                    if (readiness.exactAlarm) ValueTone.Ok else ValueTone.Warn)
                RowChevron()
            }
        }
        ListRow(
            MikaIcons.Leaf, tr(R.string.plans_screen_off_battery), first = !showAlarm,
            subtitle = if (readiness.battery == PlanBatteryPolicy.Restricted) tr(R.string.plans_screen_off_battery_restricted_sub) else null,
            // Restricted must be changed on the app details battery page; the rest go to the standard system "battery optimization" list
            onClick = {
                if (readiness.battery == PlanBatteryPolicy.Restricted) open(appDetails)
                else open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails)
            },
            modifier = Modifier.testTag("plan-screen-off-battery"),
        ) {
            when (readiness.battery) {
                PlanBatteryPolicy.Restricted -> RowValue(tr(R.string.plans_screen_off_battery_restricted), ValueTone.Bad)
                PlanBatteryPolicy.Managed -> RowValue(tr(R.string.plans_screen_off_battery_optimized))
                PlanBatteryPolicy.Unrestricted -> RowValue(tr(R.string.plans_screen_off_battery_unrestricted), ValueTone.Ok)
                PlanBatteryPolicy.Unknown -> Unit
            }
            RowChevron()
        }
        ListRow(
            MikaIcons.Lock, tr(R.string.plans_screen_off_unlock),
            subtitle = tr(when (readiness.unlock) {
                PlanUnlockReadiness.Ready -> R.string.plans_screen_off_ready
                PlanUnlockReadiness.NeedsCredential -> R.string.plans_screen_off_needs_setup
                PlanUnlockReadiness.Blocked -> R.string.plans_screen_off_blocked
                PlanUnlockReadiness.Off -> R.string.plans_screen_off_disabled
            }),
            onClick = { configuring = true },
            modifier = Modifier.testTag("plan-screen-off-unlock"),
        ) {
            when (readiness.unlock) {
                PlanUnlockReadiness.Ready -> RowValue(tr(R.string.state_on), ValueTone.Ok)
                PlanUnlockReadiness.NeedsCredential, PlanUnlockReadiness.Blocked -> RowValue(tr(R.string.plans_screen_off_fix), ValueTone.Warn)
                PlanUnlockReadiness.Off -> RowValue(tr(R.string.state_off))
            }
            RowChevron()
        }
    }
    FooterNote(tr(if (openFailed) R.string.plans_screen_off_open_failed else R.string.plans_screen_off_foot))

    if (configuring) ScreenOffUnlockDialog(settings, onDismiss = { configuring = false })
}

/**
 * Unlock settings dialog. The dialog is a separate window, so FLAG_SECURE on the activity window does not cover it: it turns on [SecureFlagPolicy.SecureOn] itself,
 * so credentials are not captured in screenshots, screen recording or recent task thumbnails while being entered. Failures saving the switch and credentials are shown by [DeviceAccessSection] itself.
 */
@Composable
private fun ScreenOffUnlockDialog(settings: DeviceAccessSettings, onDismiss: () -> Unit) {
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.plans_screen_off_unlock)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DeviceAccessSection(settings, localTasks = true)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("plan-screen-off-done")) { Text(tr(R.string.done)) } },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    )
}

private data class SystemState(val exactAlarm: Boolean, val battery: PlanBatteryPolicy, val deviceSecure: Boolean)

private fun readSystem(context: Context): SystemState {
    val battery = runCatching {
        val activity = context.getSystemService(ActivityManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        when {
            activity?.isBackgroundRestricted == true -> PlanBatteryPolicy.Restricted
            power == null -> PlanBatteryPolicy.Unknown
            power.isIgnoringBatteryOptimizations(context.packageName) -> PlanBatteryPolicy.Unrestricted
            else -> PlanBatteryPolicy.Managed
        }
    }.getOrDefault(PlanBatteryPolicy.Unknown)
    // If it cannot be read, assume a lock screen password is set: better to prompt once more to store credentials than to falsely report "ready"
    val secure = runCatching { context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure }.getOrNull() ?: true
    return SystemState(TriggerRuntime.canScheduleExactly(context), battery, secure)
}

/** Try the standard system settings pages in order and stop at the first that opens; return false if none open. */
private fun openFirst(context: Context, vararg intents: Intent): Boolean {
    for (intent in intents) {
        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            // This device model does not have this page, try the next one
        } catch (_: SecurityException) {
            // Some systems do not let third parties open this page, try the next one
        }
    }
    return false
}
