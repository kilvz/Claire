package com.wangzan101.mika.cap.phone

import android.content.Context
import com.wangzan101.mika.cap.phone.setup.BuiltinShellManager
import com.wangzan101.mika.cap.phone.setup.BuiltinShellRecovery
import com.wangzan101.mika.cap.phone.setup.PairingCoordinator
import com.wangzan101.mika.cap.phone.setup.PhoneSetupPrompt
import com.wangzan101.mika.cap.phone.shell.BuiltinShellConnection
import com.wangzan101.mika.cap.phone.shell.HelperHub
import com.wangzan101.mika.cap.phone.shell.SharedShell
import com.wangzan101.mika.core.device.shell.BuiltinShellControl
import com.wangzan101.mika.settings.RecoverySettings

/**
 * Services for the phone capabilities inside the Claire process, created once by the App and handed out via [Owner]: the built-in helper (handoff endpoint, pairing and watchdog,
 * automatic recovery and authorization), the one shell connection shared by tasks and boot restore, the pairing flow, and the "go to settings" prompt.
 * The helper processes (HelperMain, ShellService) run under the shell identity and have no App object, so they do not go through here.
 */
class PhoneServices(context: Context) {
    private val app = context.applicationContext

    /** The endpoint where the helper hands over its Binder: the helper only knows one endpoint in one process. */
    val hub = HelperHub(app)

    /** Opt-in incoming SMS login and process-wide single-attempt admission. */
    val smsLogin by lazy { com.wangzan101.mika.cap.phone.sms.SmsLoginRuntime(app) }

    /** Pairing, starting the helper through adbd, and the watchdog. */
    val shellManager = BuiltinShellManager(app, hub) { recoverySettings.consent.value }

    /** The built-in helper itself (status, pairing, retry, unpair). */
    val shellControl: BuiltinShellControl get() = shellManager.engine

    /** Settings for automatic recovery and the authorization switches. */
    val recoverySettings: RecoverySettings by lazy { RecoverySettings(RecoverySettings.prefs(app)) }

    /** Automatic recovery plus those authorization switches. */
    val recovery: BuiltinShellRecovery by lazy { BuiltinShellRecovery(app, shellManager.commandRunner, recoverySettings, shellControl) }

    /** The shell connection shared by tasks and boot restore. */
    val sharedShell = SharedShell { BuiltinShellConnection(hub) { shellManager.need() } }

    /** The whole process of one pairing. */
    val pairing: PairingCoordinator by lazy { PairingCoordinator(app, shellControl) }

    /** Shown when a task needs the phone but the helper is not running. */
    val setupPrompt = PhoneSetupPrompt(app)

    /** Hands out the single instance held by the process: the Application. */
    interface Owner {
        val phone: PhoneServices
    }

    companion object {
        fun of(context: Context): PhoneServices = (context.applicationContext as? Owner)?.phone
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement PhoneServices.Owner to give the phone's shared services")
    }
}
