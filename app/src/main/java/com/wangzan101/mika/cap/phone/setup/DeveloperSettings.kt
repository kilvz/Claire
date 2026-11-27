package com.wangzan101.mika.cap.phone.setup

import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

/**
 * The system switches the guide checks, and the Intents that jump to the matching settings pages.
 * "USB debugging" and "Wireless debugging" have no public entry point: open developer options and highlight that row the way the stock settings app does; phones that do not honor this argument stop at the top of developer options.
 */
object DeveloperSettings {
    /** The stock global settings key; the constant is not public, so it is read by name. */
    const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    /** Key of the "Wireless debugging" row in the stock developer options, used for highlighting. */
    private const val WIRELESS_DEBUGGING_KEY = "toggle_adb_wireless"
    /** Key of the "USB debugging" row in the stock developer options. */
    private const val USB_DEBUGGING_KEY = "enable_adb"
    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

    val developerOptionsUri: Uri = Settings.Global.getUriFor(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)
    val wirelessDebuggingUri: Uri = Settings.Global.getUriFor(ADB_WIFI_ENABLED)
    val usbDebuggingUri: Uri = Settings.Global.getUriFor(Settings.Global.ADB_ENABLED)

    fun developerOptions(cr: ContentResolver): Boolean =
        Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    /** A key that was never written (never turned on) counts as off; on phones where it cannot be read reliably, the guide page's "I already turned it on" is the fallback. */
    fun wirelessDebugging(cr: ContentResolver): Boolean = Settings.Global.getInt(cr, ADB_WIFI_ENABLED, 0) == 1

    /**
     * Wireless debugging does not exist on this system. The stock system (Android 11 and later) always writes this setting as 0 or 1 according to its current state at boot, so both on and off are readable;
     * if the key is never present, the system does not ship wireless debugging. An error while reading (the system forbids reading) draws no conclusion and counts as present.
     */
    fun wirelessDebuggingMissing(cr: ContentResolver): Boolean =
        runCatching { Settings.Global.getString(cr, ADB_WIFI_ENABLED) == null }.getOrDefault(false)

    /**
     * The "Developer options" page cannot be opened on this system: no activity handles the jump Intent (some runtime environments do not ship Android's system settings).
     * Being unable to ask (the system forbids the query) draws no conclusion and counts as present.
     */
    fun developerOptionsPageMissing(pm: PackageManager): Boolean =
        runCatching { pm.resolveActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), 0) == null }.getOrDefault(false)

    /**
     * Write the wireless debugging switch to on (requires the permission to modify system settings). A rejected write (throwing or returning false) yields false;
     * after the write the system may still flip it back right away (see [com.wangzan101.mika.core.device.shell.WirelessDebuggingAutoOn]), so the caller reads it again.
     */
    fun turnOnWirelessDebugging(cr: ContentResolver): Boolean =
        runCatching { Settings.Global.putInt(cr, ADB_WIFI_ENABLED, 1) }.getOrDefault(false)

    /**
     * 把无线调试开关写成关闭(要有修改系统设置的授权,和打开同一条路)。
     * 只关开关,不解除配对:配对钥匙、端口缓存和系统里的授权记录都留着,
     * 再打开时直接用已配对的连接重连,不用重新配对。写入被拒返回 false。
     */
    fun turnOffWirelessDebugging(cr: ContentResolver): Boolean =
        runCatching { Settings.Global.putInt(cr, ADB_WIFI_ENABLED, 0) }.getOrDefault(false)

    fun usbDebugging(cr: ContentResolver): Boolean = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1

    /** "About phone", where the build number lives (on some phones it is under "Software information" there). */
    fun aboutPhone(): List<Intent> = listOf(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), Intent(Settings.ACTION_SETTINGS))

    fun developerOptionsPage(): List<Intent> = listOf(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Intent(Settings.ACTION_SETTINGS))

    fun usbDebuggingPage(): List<Intent> = highlighted(USB_DEBUGGING_KEY)

    fun wirelessDebuggingPage(): List<Intent> = highlighted(WIRELESS_DEBUGGING_KEY)

    private fun highlighted(key: String): List<Intent> {
        val highlight = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(EXTRA_FRAGMENT_ARG_KEY, key)
            .putExtra(EXTRA_SHOW_FRAGMENT_ARGS, Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, key) })
        return listOf(highlight) + developerOptionsPage()
    }

    fun wifiPage(): List<Intent> = listOf(Intent(Settings.ACTION_WIFI_SETTINGS), Intent(Settings.ACTION_SETTINGS))
}
