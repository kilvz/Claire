package com.wangzan101.mika.settings

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.core.device.shell.A11yLoss
import com.wangzan101.mika.core.device.shell.A11yLossRecord
import com.wangzan101.mika.core.device.shell.RecoveryCapability
import com.wangzan101.mika.core.device.shell.RecoveryConsent
import com.wangzan101.mika.core.device.shell.RecoveryConsentStore
import com.wangzan101.mika.core.device.shell.RestoreOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Records of the "phone operation" capabilities and of the result of the last automatic restore after boot. The boot broadcast and the settings page share the same instance within one process.
 *
 * Each capability has two booleans: the original key (e.g. `accessibility`) records "in effect", with the same meaning as before (it was only written as true after a command succeeded);
 * `<key>_declined` records "turned off by the user". Older versions only had the first key: a stored false cannot tell "never touched" from "turned off",
 * so everything is treated as never touched — a missing new key means it was never turned off, hence no separate migration is needed on upgrade, and these capabilities are enabled by default once the connection is available.
 */
class RecoverySettings(private val prefs: SharedPreferences) : RecoveryConsentStore {
    private val _consent = MutableStateFlow(read())
    override val consent: StateFlow<RecoveryConsent> = _consent.asStateFlow()

    private val _lastRestore = MutableStateFlow(readOutcome())
    val lastRestore: StateFlow<RestoreOutcome?> = _lastRestore.asStateFlow()

    private val _lastA11yLoss = MutableStateFlow(readLoss())
    /** At Claire's previous launch the accessibility service was found revoked or not bound (used for diagnostics). */
    val lastA11yLoss: StateFlow<A11yLossRecord?> = _lastA11yLoss.asStateFlow()

    /** Claire's accessibility service has connected at least once: failing to connect on a later launch counts as a problem, while never having set it up does not. */
    var accessibilitySeen: Boolean
        get() = prefs.getBoolean(A11Y_SEEN, false)
        set(v) { if (v != accessibilitySeen) prefs.edit().putBoolean(A11Y_SEEN, v).apply() }

    fun recordA11yLoss(record: A11yLossRecord) {
        prefs.edit().putString(A11Y_LOSS, record.loss.name).putLong(A11Y_LOSS_AT, record.atMs).apply()
        _lastA11yLoss.value = record
    }

    private fun readLoss(): A11yLossRecord? {
        val loss = prefs.getString(A11Y_LOSS, null)?.let { n -> A11yLoss.entries.firstOrNull { it.name == n } } ?: return null
        return A11yLossRecord(loss, prefs.getLong(A11Y_LOSS_AT, 0))
    }

    @Synchronized override fun save(consent: RecoveryConsent) {
        val restore = RecoveryCapability.RestoreAfterReboot
        val restoreChanged = consent.isApplied(restore) != _consent.value.isApplied(restore)
        prefs.edit().apply {
            for ((c, key) in KEYS) {
                putBoolean(key, consent.isApplied(c))
                putBoolean(key + DECLINED, !consent.wants(c))
            }
            if (restoreChanged) remove(LAST_RESTORE)
        }.commit()
        _consent.value = consent
        // After turning it back on or off, the previous boot result no longer indicates anything
        if (restoreChanged) _lastRestore.value = null
    }

    fun recordRestore(outcome: RestoreOutcome) {
        prefs.edit().putString(LAST_RESTORE, outcome.name).commit()
        _lastRestore.value = outcome
    }

    private fun read() = RecoveryConsent(
        applied = KEYS.filter { (_, key) -> prefs.getBoolean(key, false) }.keys,
        declined = KEYS.filter { (_, key) -> prefs.getBoolean(key + DECLINED, false) }.keys,
    )

    private fun readOutcome(): RestoreOutcome? =
        prefs.getString(LAST_RESTORE, null)?.let { n -> RestoreOutcome.entries.firstOrNull { it.name == n } }

    companion object {
        private const val PREFS = "builtin_shell_recovery"
        private val KEYS = mapOf(
            RecoveryCapability.Accessibility to "accessibility",
            RecoveryCapability.BatteryWhitelist to "battery_whitelist",
            RecoveryCapability.RestoreAfterReboot to "restore_after_reboot",
            RecoveryCapability.AutoWirelessDebugging to "auto_wireless_debugging",
        )
        private const val DECLINED = "_declined"
        private const val LAST_RESTORE = "last_restore"
        private const val A11Y_SEEN = "a11y_seen"
        private const val A11Y_LOSS = "a11y_loss"
        private const val A11Y_LOSS_AT = "a11y_loss_at"

        /** The preferences file read and written by the production instance (created by [com.wangzan101.mika.cap.phone.PhoneServices]). */
        fun prefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
