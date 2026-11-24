package com.wangzan101.mika.settings

import com.wangzan101.mika.core.device.CredentialKind
import com.wangzan101.mika.core.device.DeviceAccessStore
import com.wangzan101.mika.core.device.DeviceCredential
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class DeviceAccessConfig(val enabled: Boolean = false, val localEnabled: Boolean = false,
    val kind: CredentialKind? = null, val blocked: Boolean = false, val localBlocked: Boolean = false,
    val revision: String = "")

/** All settings are written atomically and encrypted. Only the non-secret state flow is kept; decrypted credentials are never cached. Anything unreadable is treated as off. */
class DeviceAccessSettings(private val dir: File, private val cipher: KeyCipher) : DeviceAccessStore {
    private val file = File(dir, "device-access.bin")
    private val mutable = MutableStateFlow(runCatching { read()?.let(::configOf) }.getOrNull() ?: DeviceAccessConfig())
    val config = mutable.asStateFlow()
    override val enabled get() = config.value.enabled
    override val localEnabled get() = config.value.localEnabled
    override val credentialConfigured get() = config.value.kind != null
    override val attemptBlocked get() = config.value.blocked

    @Synchronized fun setEnabled(on: Boolean) {
        try { update(config.value.copy(enabled = on)) }
        catch (e: Exception) {
            // When permission is revoked, turn it off in-process even if the disk or the key is unavailable, so a save error does not leave an old usable permission behind.
            if (!on) { mutable.value = config.value.copy(enabled = false); file.delete() }
            throw e
        }
    }

    /** The on-device switch and the remote switch do not drive each other; failure handling on turn-off is the same as in [setEnabled]. */
    @Synchronized fun setLocalEnabled(on: Boolean) {
        try { update(config.value.copy(localEnabled = on)) }
        catch (e: Exception) {
            if (!on) { mutable.value = config.value.copy(localEnabled = false); file.delete() }
            throw e
        }
    }

    @Synchronized fun setCredential(kind: CredentialKind, value: String) {
        val chars = value.toCharArray()
        try { require(DeviceCredential.valid(kind, chars)) { "Unsupported credential format" } } finally { chars.fill('\u0000') }
        // Saving the credential again clears both latches at once: a new credential gets one fresh attempt on each path
        save(config.value.copy(kind = kind, blocked = false, localBlocked = false, revision = UUID.randomUUID().toString()), value)
    }

    @Synchronized fun clearCredential() { save(config.value.copy(kind = null, blocked = false, localBlocked = false, revision = ""), null) }

    @Synchronized override fun credential(): DeviceCredential? = runCatching {
        val o = read() ?: return null
        val c = configOf(o)
        if (c != config.value) return null
        c.kind?.let { DeviceCredential(it, (o.getValue("credential") as JsonPrimitive).content.toCharArray(), c.revision) }
    }.getOrNull()

    @Synchronized override fun beginAttempt(credential: DeviceCredential): Boolean {
        if (!enabled || attemptBlocked || config.value.revision != credential.revision || config.value.kind != credential.kind) return false
        update(config.value.copy(blocked = true))
        return true
    }

    /** The separate latch for on-device tasks: independent of the latch of [beginAttempt], also set before input and cleared on failure. */
    @Synchronized override fun beginLocalAttempt(): Boolean {
        if (!localEnabled || config.value.localBlocked || config.value.kind == null) return false
        update(config.value.copy(localBlocked = true))
        return true
    }

    @Synchronized override fun completeAttempt(credential: DeviceCredential) {
        // The latch exists because the credential might be wrong; a successful verification with the same revision disproves that, clearing both latches together
        if (config.value.revision == credential.revision) update(config.value.copy(blocked = false, localBlocked = false))
    }

    private fun read(): JsonObject? {
        if (!file.isFile) return null
        val bytes = cipher.decrypt(file.readBytes())
        return try { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject } finally { bytes.fill(0) }
    }

    private fun configOf(o: JsonObject): DeviceAccessConfig {
        val kind = (o["kind"] as? JsonPrimitive)?.content?.let(CredentialKind::valueOf)
        val value = (o["credential"] as? JsonPrimitive)?.content
        val chars = value?.toCharArray()
        try { check(kind == null && value == null || kind != null && chars != null && DeviceCredential.valid(kind, chars)) }
        finally { chars?.fill('\u0000') }
        val revision = (o["revision"] as? JsonPrimitive)?.content.orEmpty()
        check(kind == null || revision.isNotEmpty())
        // Old files have no on-device switch field: it defaults to off, for backward compatibility
        return DeviceAccessConfig(enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull == true,
            localEnabled = (o["local_enabled"] as? JsonPrimitive)?.booleanOrNull == true, kind = kind,
            blocked = (o["blocked"] as? JsonPrimitive)?.booleanOrNull == true,
            localBlocked = (o["local_blocked"] as? JsonPrimitive)?.booleanOrNull == true, revision = revision)
    }

    private fun update(config: DeviceAccessConfig) {
        val c = credential()
        try {
            check(config.kind == null || c != null) { "Credential unavailable; configure it again on the phone" }
            save(config, c?.let { String(it.value) })
        } finally { c?.close() }
    }

    private fun save(config: DeviceAccessConfig, value: String?) {
        val bytes = buildJsonObject {
            put("enabled", config.enabled); put("local_enabled", config.localEnabled)
            put("blocked", config.blocked); put("local_blocked", config.localBlocked); put("revision", config.revision)
            config.kind?.let { put("kind", it.name); put("credential", value) }
        }.toString().toByteArray(Charsets.UTF_8)
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        try {
            tmp.writeBytes(cipher.encrypt(bytes))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            mutable.value = config
        } finally { bytes.fill(0); tmp.delete() }
    }
}
