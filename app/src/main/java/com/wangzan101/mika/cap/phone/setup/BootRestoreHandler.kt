package com.wangzan101.mika.cap.phone.setup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wangzan101.mika.cap.phone.PhoneServices
import kotlinx.coroutines.launch

/**
 * Boot broadcast. It acts only when the user has enabled "restore automatically after reboot" and Claire still holds the authorization to modify system settings;
 * in every other case it does nothing (the check lives in [BuiltinShellRecovery.restoreAfterBoot]).
 */
open class BootRestoreHandler : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val phone = PhoneServices.of(context)
        phone.pairing.scope.launch {
            try {
                runCatching { phone.recovery.restoreAfterBoot() }
            } finally {
                pending.finish()
            }
        }
    }
}
