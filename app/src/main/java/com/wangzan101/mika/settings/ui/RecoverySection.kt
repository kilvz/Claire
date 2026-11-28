package com.wangzan101.mika.settings.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.core.device.shell.AutoOnBlock
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.core.device.shell.HelperRestart
import com.wangzan101.mika.core.device.shell.PermissionResult
import com.wangzan101.mika.core.device.shell.RecoveryCapability
import com.wangzan101.mika.core.device.shell.RecoveryConsent
import com.wangzan101.mika.core.device.shell.RepairOutcome
import com.wangzan101.mika.core.device.shell.RestoreOutcome
import com.wangzan101.mika.cap.phone.PhoneServices
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.focusHighlightTarget
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.theme.MikaType

/**
 * Text for the phone-action permission switches under Settings › Recovery & repair (pure functions, easy to test). Like the onboarding page it uses plain language:
 * each item spells out what Claire will do once enabled and how to turn it off; the explanation always sits below the switch. All default to on, and Claire applies them herself once phone actions are set up.
 */
object RecoveryCopy {
    data class SwitchCopy(val title: String, val subtitle: String, val explain: String)

    fun switch(c: RecoveryCapability): SwitchCopy = when (c) {
        RecoveryCapability.Accessibility -> SwitchCopy(
            tr(R.string.rec_a11y),
            tr(R.string.rec_default_on),
            tr(R.string.rec_a11y_body),
        )
        RecoveryCapability.BatteryWhitelist -> SwitchCopy(
            tr(R.string.rec_battery),
            tr(R.string.rec_default_on),
            tr(R.string.rec_battery_body),
        )
        RecoveryCapability.RestoreAfterReboot -> SwitchCopy(
            tr(R.string.rec_boot),
            tr(R.string.rec_default_on),
            tr(R.string.rec_boot_body),
        )
        RecoveryCapability.AutoWirelessDebugging -> SwitchCopy(
            tr(R.string.rec_auto_wireless),
            tr(R.string.rec_default_on),
            tr(R.string.rec_auto_wireless_body),
        )
    }

    val NEED_SETUP: String get() = tr(R.string.rec_need_setup)
    val FAILED: String get() = tr(R.string.rec_failed)
    val WORKING: String get() = tr(R.string.rec_working)
    val LEARN: String get() = tr(R.string.rec_learn_what)

    /** One line of feedback after a switch is toggled; says nothing extra on success. */
    fun result(r: PermissionResult): String? = when (r) {
        PermissionResult.Done -> null
        PermissionResult.NoRunner -> NEED_SETUP
        is PermissionResult.Failed -> FAILED
    }

    /** The items that require paired wireless debugging: useless when the helper was started only via the USB start command. */
    private val NEEDS_PAIRING = setOf(RecoveryCapability.RestoreAfterReboot, RecoveryCapability.AutoWirelessDebugging)

    /**
     * Says one line below an item that is on by default but not yet in effect: if phone actions are not set up, prompts to finish setup first (it turns on automatically afterwards),
     * if turning it on automatically failed, says so plainly; if it is in effect or the user turned it off, says nothing extra.
     * When the helper was started only via the USB start command ([HelperRestart.UsbCommand]), the two items that restart the helper over wireless debugging explain that they do not apply and that the command must be run again after a reboot.
     */
    fun pending(
        c: RecoveryCapability, consent: RecoveryConsent, paired: Boolean, autoFailed: Set<RecoveryCapability>,
        restart: HelperRestart = HelperRestart.None,
    ): String? = when {
        !consent.wants(c) -> null
        restart == HelperRestart.UsbCommand && c in NEEDS_PAIRING -> tr(R.string.rec_needs_pairing)
        restart == HelperRestart.Shizuku && c in NEEDS_PAIRING -> tr(R.string.rec_shizuku_no_wireless)
        consent.isApplied(c) -> null
        !paired -> NEED_SETUP
        c in autoFailed -> FAILED
        else -> null
    }

    /** Below "Turn on accessibility for me": the time the accessibility service was last restarted automatically and successfully; silent when it did not succeed, since the banner at the top of the screen still offers the manual fix. */
    fun restarted(o: RepairOutcome?, zone: ZoneId = ZoneId.systemDefault()): String? =
        o?.takeIf { it.restarted }?.let { tr(R.string.rec_restarted_at, HH_MM.format(Instant.ofEpochMilli(it.atMillis).atZone(zone))) }

    private val HH_MM = DateTimeFormatter.ofPattern("HH:mm")

    /** Below "Turn on wireless debugging automatically": why turning it on for you did not succeed and it stopped early; says nothing extra when it did not stop. */
    fun autoOnBlocked(b: AutoOnBlock?): String? = when (b) {
        null -> null
        AutoOnBlock.NotAllowedHere -> tr(R.string.rec_not_allowed_here)
        AutoOnBlock.Refused -> tr(R.string.rec_refused)
        AutoOnBlock.GaveUp -> tr(R.string.rec_gave_up)
    }

    /** Below "Restore automatically after reboot": the result of the last boot; says nothing extra when it restored normally. */
    fun lastRestore(o: RestoreOutcome?): String? = when (o) {
        null, RestoreOutcome.Restored -> null
        RestoreOutcome.PermissionMissing -> tr(R.string.rec_permission_missing)
        RestoreOutcome.SettingUnavailable -> tr(R.string.rec_setting_unavailable)
        RestoreOutcome.NotRecovered -> tr(R.string.rec_not_recovered)
    }
}

/** The same switches wired to real state: the choice is stored in [com.wangzan101.mika.settings.RecoverySettings], execution goes through the paired connection. */
@Composable
fun RecoverySwitches() {
    val context = LocalContext.current
    val phone = remember { PhoneServices.of(context) }
    val settings = phone.recoverySettings
    val consent by settings.consent.collectAsState()
    val shell by phone.shellControl.status.collectAsState()
    val autoFailed by phone.recovery.permissions.autoFailed.collectAsState()
    val lastRestore by settings.lastRestore.collectAsState()
    val repaired by phone.recovery.accessibilityRepair.last.collectAsState()
    val autoOnBlocked by phone.shellManager.wirelessAutoOn.blocked.collectAsState()
    var busy by remember { mutableStateOf<RecoveryCapability?>(null) }
    var message by remember { mutableStateOf<Pair<RecoveryCapability, String>?>(null) }
    val notes = RecoveryCapability.entries.mapNotNull { cap ->
        val m = message
        (if (m?.first == cap) m.second else RecoveryCopy.pending(
            cap, consent, shell == BuiltinShellStatus.Running || phone.shellControl.paired, autoFailed, phone.shellControl.restart,
        ))
            ?.let { cap to it }
    }.toMap()
    RecoverySection(
        consent = consent, busy = busy, notes = notes, lastRestore = lastRestore, restartNote = RecoveryCopy.restarted(repaired),
        autoOnNote = RecoveryCopy.autoOnBlocked(autoOnBlocked),
        onToggle = { cap, on ->
            busy = cap
            message = null
            // Not tied to the page: the command must still finish when the user leaves the settings page, so the choice stays consistent with the system
            phone.pairing.scope.launch {
                val r = phone.recovery.permissions.set(cap, on)
                message = RecoveryCopy.result(r)?.let { cap to it }
                busy = null
            }
        },
    )
}

/**
 * Stateless switches, easy to test. A switch shows whether the user wants this item (on unless turned off by hand), toggling executes immediately with no further confirmation;
 * [notes] is a hint line below a given item (the toggle result, setup required first, automatic enabling failed);
 * [restartNote] is the note line below "Turn on accessibility for me" (the accessibility service was restarted automatically last time);
 * [autoOnNote] is the note line below "Turn on wireless debugging automatically" (turning it on for you failed and it stopped early).
 */
@Composable
fun RecoverySection(
    consent: RecoveryConsent,
    busy: RecoveryCapability?,
    notes: Map<RecoveryCapability, String>,
    lastRestore: RestoreOutcome?,
    onToggle: (RecoveryCapability, Boolean) -> Unit,
    restartNote: String? = null,
    autoOnNote: String? = null,
) {
    var showExplain by remember { mutableStateOf(false) }
    GroupCard {
        RecoveryCapability.entries.forEachIndexed { i, cap ->
            val copy = RecoveryCopy.switch(cap)
            val on = consent.wants(cap)
            val icon = iconOf(cap)
            ListRow(
                icon, copy.title, if (busy == cap) RecoveryCopy.WORKING else null, first = i == 0,
                modifier = Modifier.focusHighlightTarget(focusKeyOf(cap)),
            ) {
                MikaSwitch(on, { want -> onToggle(cap, want) }, Modifier.testTag("recovery-${cap.name}"), enabled = busy == null)
            }
            notes[cap]?.let { Note(it, MikaTheme.colors.bad) }
            if (cap == RecoveryCapability.Accessibility) restartNote?.let { Note(it, MikaTheme.colors.ink3) }
            if (cap == RecoveryCapability.RestoreAfterReboot && consent.isApplied(cap)) {
                RecoveryCopy.lastRestore(lastRestore)?.let { Note(it, MikaTheme.colors.warn) }
            }
            if (cap == RecoveryCapability.AutoWirelessDebugging && consent.wants(cap)) autoOnNote?.let { Note(it, MikaTheme.colors.warn) }
        }
        ListRow(MikaIcons.Info, RecoveryCopy.LEARN, onClick = { showExplain = !showExplain }) {
            RowChevron()
        }
        if (showExplain) {
            RecoveryCapability.entries.forEach { Note(RecoveryCopy.switch(it).explain, MikaTheme.colors.ink3) }
        }
    }
}

/** Recents visibility uses the same below-row error presentation as the recovery switches. */
@Composable
fun RecentsExclusionSection(excluded: Boolean, onSet: (Boolean) -> Boolean) {
    var current by remember(excluded) { mutableStateOf(excluded) }
    var failed by remember { mutableStateOf(false) }
    GroupCard {
        ListRow(
            MikaIcons.EyeOff,
            tr(R.string.rec_exclude_recents),
            tr(R.string.rec_exclude_recents_sub),
            first = true,
            modifier = Modifier.focusHighlightTarget(SettingsFocusKey.RECENTS_EXCLUSION),
        ) {
            MikaSwitch(
                current,
                { want ->
                    if (onSet(want)) {
                        current = want
                        failed = false
                    } else {
                        failed = true
                    }
                },
                Modifier.testTag("recents-exclusion"),
            )
        }
        if (failed) Note(tr(R.string.rec_exclude_recents_failed), MikaTheme.colors.bad)
    }
}

private fun iconOf(c: RecoveryCapability): ImageVector = when (c) {
    RecoveryCapability.Accessibility -> MikaIcons.Hand
    RecoveryCapability.BatteryWhitelist -> MikaIcons.Sun
    RecoveryCapability.RestoreAfterReboot -> MikaIcons.Wrench
    RecoveryCapability.AutoWirelessDebugging -> MikaIcons.Refresh
}

private fun focusKeyOf(c: RecoveryCapability): String = when (c) {
    RecoveryCapability.Accessibility -> SettingsFocusKey.RECOVERY_A11Y
    RecoveryCapability.BatteryWhitelist -> SettingsFocusKey.RECOVERY_BATTERY
    RecoveryCapability.RestoreAfterReboot -> SettingsFocusKey.RECOVERY_BOOT
    RecoveryCapability.AutoWirelessDebugging -> SettingsFocusKey.RECOVERY_AUTO_WIRELESS
}

@Composable
private fun Note(text: String, color: Color) {
    Text(text, Modifier.fillMaxWidth().padding(start = 60.dp, end = 16.dp, bottom = 10.dp), style = MikaType.Hint, color = color)
}
