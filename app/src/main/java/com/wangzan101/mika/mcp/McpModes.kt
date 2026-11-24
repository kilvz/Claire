package com.wangzan101.mika.mcp

import com.wangzan101.mika.settings.McpSettings
import com.wangzan101.mika.settings.McpUsbSettings
import com.wangzan101.mika.settings.RelaySettings

/**
 * The local listener shared by LAN and USB: with LAN on it binds all interfaces, with only USB on it binds loopback only.
 * The remote gateway uses its own outbound channel and does not change the local listening scope; the three switches are independent, while the notification's "turn off" turns them all off together.
 * It touches no Android API, so it can be tested on a computer.
 */
object McpModes {
    /** A computer turns USB mode on over adb (`am start-foreground-service -a`). */
    const val ACTION_USB_ON = "com.wangzan101.mika.mcp.USB_ON"
    /** A computer turns USB mode off over adb. */
    const val ACTION_USB_OFF = "com.wangzan101.mika.mcp.USB_OFF"
    /** The "turn off" action on the notification: turns off both the local listener and the remote channel. */
    const val ACTION_TURN_OFF = "com.wangzan101.mika.mcp.TURN_OFF"

    enum class Listen { Off, LoopbackOnly, PrivateNetworks }

    fun listen(network: Boolean, usb: Boolean): Listen = when {
        network -> Listen.PrivateNetworks
        usb -> Listen.LoopbackOnly
        else -> Listen.Off
    }

    /** Which switch the action received by the service flips; any other action (including none) changes nothing and the current switch state applies as is. */
    fun apply(action: String?, network: McpSettings, usb: McpUsbSettings, relay: RelaySettings? = null) {
        when (action) {
            ACTION_USB_ON -> usb.enable()
            ACTION_USB_OFF -> usb.disable()
            ACTION_TURN_OFF -> {
                try { network.setEnabled(false) }
                finally { try { usb.disable() } finally { relay?.setEnabled(false) } }
            }
        }
    }
}
