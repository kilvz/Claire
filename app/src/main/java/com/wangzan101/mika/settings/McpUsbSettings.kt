package com.wangzan101.mika.settings

import com.wangzan101.mika.core.mcp.McpUsb
import com.wangzan101.mika.core.remote.RemoteOwnership
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64

/**
 * The switch and token for computer connections over USB (the kind a computer opens with adb). Separate from the LAN kind ([McpSettings]); each keeps its own token.
 *
 * - Turning it on generates a new token; turning it on again while already on reuses the existing one (a computer that re-runs the command does not lose an already configured setup).
 * - Once listening, [publish] writes the connection info to [infoFile] (the external files directory; since Android 11 only Claire and the shell can read it).
 * - Turning it off forgets the token (the next enable gets a new one) and deletes the connection info file.
 * - It does not survive a reboot: the switch records which boot ([bootId]) it was turned on in; after a different boot it counts as off, and the file left over from last time is cleaned up.
 *
 * The token is also written in plaintext into the connection info file (the computer has to read it), so it is stored in plaintext in the app's private directory as well, with no extra encryption.
 */
class McpUsbSettings(
    private val dir: File,
    private val bootId: () -> String,
    private val infoFile: () -> File?,
    private val random: SecureRandom = SecureRandom(),
    migrateTaskOwner: (String) -> Unit = {},
) {
    private val file = File(dir, "mcp-usb.json")
    private val _enabled = MutableStateFlow(false)
    @Volatile private var token: String? = null

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    init {
        val o = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()
        val boot = (o?.get("boot") as? JsonPrimitive)?.content
        val t = (o?.get("token") as? JsonPrimitive)?.content?.takeIf { it.length >= MIN_TOKEN }
        // Older versions used a token digest as the ownership; migrate its tasks before the token from the last boot is cleared.
        if (t != null) migrateTaskOwner(RemoteOwnership.of(t))
        if (boot != null && boot == bootId() && t != null) {
            token = t
            _enabled.value = true
        } else {
            file.delete()
            runCatching { infoFile()?.delete() }
        }
    }

    /** The token while it is on; null when off. */
    fun token(): String? = token

    /** Turns it on; if already on, the existing token is reused. Returns the token as of now. */
    @Synchronized fun enable(): String {
        token?.let { return it }
        val t = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(buildJsonObject { put("boot", bootId()); put("token", t) }.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        token = t
        _enabled.value = true
        return t
    }

    /** Turns it off: the token becomes invalid immediately and the connection info file is deleted. */
    @Synchronized fun disable() {
        token = null
        _enabled.value = false
        file.delete()
        unpublish()
    }

    /** Listening has started: write the connection info for the computer (write a temp file and rename it, so a computer reading at that moment never sees a partial file). Does nothing while off. */
    @Synchronized fun publish(port: Int) {
        val t = token ?: return
        val f = infoFile() ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(McpUsb.info(port, t, bootId()))
        if (!tmp.renameTo(f)) {
            f.delete()
            check(tmp.renameTo(f)) { "rename failed" }
        }
    }

    /** Not listening (never started, or turned off): delete the connection info file so the script on the computer knows it did not start. */
    @Synchronized fun unpublish() {
        runCatching { infoFile()?.delete() }
    }

    private companion object {
        const val MIN_TOKEN = 32
    }
}
