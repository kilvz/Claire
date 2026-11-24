package com.wangzan101.mika.settings

import com.wangzan101.mika.mcp.McpModes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

/** Computer connection over USB: how the switch toggles, when the token is rotated, when the connection-info file is written or deleted, that it does not survive a reboot, and that it is independent of the LAN mode. */
class McpUsbSettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private var boot = "5"
    private val internal get() = tmp.root.resolve("settings")
    private val info get() = tmp.root.resolve("external/mcp-usb.json")
    private fun usb() = McpUsbSettings(internal, { boot }, { info })
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun network() = McpSettings(internal, AesGcmKeyCipher { key })

    @Test fun offByDefault() {
        val u = usb()
        assertFalse(u.enabled.value)
        assertNull(u.token())
        assertFalse(info.exists())
    }

    @Test fun enableTwiceKeepsTheTokenAndPublishWritesTheConnectionInfo() {
        val u = usb()
        val t = u.enable()
        assertTrue(t.length >= 40)
        assertEquals(t, u.enable())
        // Nothing is written for the computer before it is listening
        assertFalse(info.exists())
        u.publish(8765)
        val o = Json.parseToJsonElement(info.readText()).jsonObject
        assertEquals("http://127.0.0.1:8765/mcp", o["url"]!!.jsonPrimitive.content)
        assertEquals("Bearer $t", o["headers"]!!.jsonObject["Authorization"]!!.jsonPrimitive.content)
        assertEquals("5", o["boot"]!!.jsonPrimitive.content)
        // A process restart (same boot session) keeps it on and the token unchanged
        val again = usb()
        assertTrue(again.enabled.value)
        assertEquals(t, again.token())
    }

    @Test fun disableDeletesTheFileAndTheNextEnableGetsANewToken() {
        val u = usb()
        val old = u.enable()
        u.publish(8765)
        u.disable()
        assertFalse(u.enabled.value)
        assertNull(u.token())
        assertFalse(info.exists())
        assertFalse(usb().enabled.value)
        assertNotEquals(old, u.enable())
    }

    @Test fun publishWhileOffWritesNothingAndUnpublishDeletes() {
        val u = usb()
        u.publish(8765)
        assertFalse(info.exists())
        u.enable()
        u.publish(8765)
        assertTrue(info.exists())
        u.unpublish()
        assertFalse(info.exists())
        assertTrue(u.enabled.value)
    }

    @Test fun doesNotSurviveARestartOfThePhone() {
        val u = usb()
        u.enable()
        u.publish(8765)
        boot = "6"
        val after = usb()
        assertFalse(after.enabled.value)
        assertNull(after.token())
        // The connection info left over from the previous boot is cleared too
        assertFalse(info.exists())
    }

    @Test fun unreadableStateMeansOff() {
        internal.mkdirs()
        internal.resolve("mcp-usb.json").writeText("{oops")
        assertFalse(usb().enabled.value)
    }

    @Test fun commandsFromTheComputerAndTheNotification() {
        val n = network()
        val u = usb()
        McpModes.apply(McpModes.ACTION_USB_ON, n, u)
        assertTrue(u.enabled.value)
        // Turning on USB does not turn on the LAN mode
        assertFalse(n.enabled.value)
        assertFalse(network().enabled.value)
        assertEquals(McpModes.Listen.LoopbackOnly, McpModes.listen(n.enabled.value, u.enabled.value))

        val t = u.token()
        McpModes.apply(McpModes.ACTION_USB_ON, n, u)
        assertEquals(t, u.token())
        // No action (service recreated by the system) or an unrecognised action: the switch does not change
        McpModes.apply(null, n, u)
        McpModes.apply("something.else", n, u)
        assertTrue(u.enabled.value)

        McpModes.apply(McpModes.ACTION_USB_OFF, n, u)
        assertFalse(u.enabled.value)
        assertEquals(McpModes.Listen.Off, McpModes.listen(n.enabled.value, u.enabled.value))

        // "Turn off" on the notification turns off both
        n.setEnabled(true)
        u.enable()
        assertEquals(McpModes.Listen.PrivateNetworks, McpModes.listen(n.enabled.value, u.enabled.value))
        McpModes.apply(McpModes.ACTION_TURN_OFF, n, u)
        assertFalse(n.enabled.value)
        assertFalse(u.enabled.value)
    }

    @Test fun usbOffLeavesTheNetworkModeAlone() {
        val n = network()
        n.setEnabled(true)
        val token = n.token()
        val u = usb()
        u.enable()
        McpModes.apply(McpModes.ACTION_USB_OFF, n, u)
        assertTrue(n.enabled.value)
        assertEquals(token, n.token())
        assertEquals(McpModes.Listen.PrivateNetworks, McpModes.listen(n.enabled.value, u.enabled.value))
    }
}
