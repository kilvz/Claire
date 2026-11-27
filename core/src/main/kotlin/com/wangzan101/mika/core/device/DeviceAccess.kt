package com.wangzan101.mika.core.device

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Main-screen state, unrelated to the Agent's virtual display. When the state cannot be read the port reports an error; being unlocked is never assumed. */
data class DeviceState(val interactive: Boolean, val keyguardShowing: Boolean, val deviceLocked: Boolean,
    val secure: Boolean, val helperAvailable: Boolean, val userUnlocked: Boolean) {
    val ready: Boolean get() = interactive && !keyguardShowing && !deviceLocked && userUnlocked
}

enum class CredentialKind { Pin, Password }

/** The credential exists only briefly inside the deterministic on-device flow; no toString with the plaintext is provided. Wiped when done. */
class DeviceCredential(val kind: CredentialKind, val value: CharArray, val revision: String = "") : AutoCloseable {
    override fun close() { value.fill('\u0000') }
    override fun toString() = "DeviceCredential($kind, redacted)"

    companion object {
        fun valid(kind: CredentialKind, value: CharArray): Boolean = when (kind) {
            CredentialKind.Pin -> value.size in 4..16 && value.all { it in '0'..'9' }
            CredentialKind.Password -> value.size in 4..64 && value.all { it in ' '..'~' }
        }
    }
}

/** Platform adapter: wake, show the system credential page, confirm the input target, type. Never changes the system lock screen credential. */
interface DeviceAccessPort {
    suspend fun state(): DeviceState
    suspend fun wake(): Boolean
    suspend fun dismiss(): Boolean
    /** When the secure lock screen is still on the notification/fingerprint page, attempts one generic system navigation to expose the credential page; recognizes no text and types no credential. */
    suspend fun revealCredential(): Boolean = false
    /** When the biometric-first page remains after swiping up and there is no credential field, does one generic fallback tap (tapping the largest tappable target); if no target is found it does nothing. */
    suspend fun revealCredentialFallback(): Boolean = false
    suspend fun credentialReady(kind: CredentialKind): Boolean
    /** Read-only recognition of the single system credential field currently shown; returns null when it is unknown, ambiguous, or unsupported by the platform, and neither focuses nor types. */
    suspend fun detectedCredentialKind(): CredentialKind? = null
    /** When the credential page cannot be recognized, returns restricted diagnostics containing no on-screen text and no credential, so gaps in the generic recognition rules can be located. */
    suspend fun credentialDiagnostics(): List<String> = emptyList()
    suspend fun enter(credential: DeviceCredential): Boolean
}

/** Permission and credential come from the phone settings and cannot be changed by the computer. A placeholder is persisted before typing, so nothing is retried automatically after a failure, cancellation, or crash. */
interface DeviceAccessStore {
    val enabled: Boolean
    /** A separate switch for on-device tasks, independent of [enabled]; off by default. */
    val localEnabled: Boolean get() = false
    val credentialConfigured: Boolean
    val attemptBlocked: Boolean
    fun credential(): DeviceCredential?
    fun beginAttempt(credential: DeviceCredential): Boolean
    /** Records its own placeholder before an on-device task types the credential; returns false when the switch is off or paused. */
    fun beginLocalAttempt(): Boolean = false
    fun completeAttempt(credential: DeviceCredential)
}

data class DeviceAccessResult(val status: String, val state: DeviceState?, val enabled: Boolean,
    val credentialConfigured: Boolean, val attemptBlocked: Boolean,
    val storedKind: CredentialKind? = null, val detectedKind: CredentialKind? = null,
    val keyguardDiagnostics: List<String> = emptyList(),
    /** This flow really brought the phone from screen-off/locked to usable (wake, swipe-up, or credential injection); a user unlocking on their own in the meantime does not count. */
    val performedUnlock: Boolean = false)

/** One instance is shared by the whole process. Concurrent calls are serialized, and success is judged by the system state, not by key-press or command return codes. */
class DeviceAccess(private val port: DeviceAccessPort, private val store: DeviceAccessStore,
    private val pollMs: Long = 150, private val waitMs: Long = 5_000) {
    private val mutex = Mutex()

    suspend fun state(): DeviceAccessResult {
        val s = read()
        return result(if (s == null) "device_unavailable" else "state", s)
    }

    suspend fun wake(): DeviceAccessResult = mutex.withLock {
        prepare(local = false) { true }?.let { return@withLock it }
        val before = read() ?: return@withLock result("device_unavailable")
        if (before.interactive) return@withLock result("awake", before)
        if (!port.wake()) return@withLock result("wake_failed", read())
        val after = await { it.interactive }
        result(if (after?.interactive == true) "awake" else "wake_failed", after)
    }

    /** Unlock for a remote source (a connected computer): only the remote switch applies. */
    suspend fun unlock(authorized: () -> Boolean = { true }): DeviceAccessResult = unlockUsing(local = false, authorized)

    /** Unlock for on-device tasks (started from the UI or by a schedule): only the on-device switch applies, independently of the remote switch. */
    suspend fun unlockLocal(authorized: () -> Boolean = { true }): DeviceAccessResult = unlockUsing(local = true, authorized)

    /** The source authorization can only narrow the corresponding switch further; it is re-checked before every operation, including after waiting on the lock and system UI. */
    private suspend fun unlockUsing(local: Boolean, authorized: () -> Boolean): DeviceAccessResult = mutex.withLock {
        fun allowed() = (if (local) store.localEnabled else store.enabled) && authorized()
        prepare(local, authorized)?.let { return@withLock it }
        var now = read() ?: return@withLock result("device_unavailable")
        if (now.ready) return@withLock result("unlocked", now)
        var woke = false
        if (!now.interactive) {
            if (!allowed()) return@withLock result("disabled", now)
            if (!port.wake()) return@withLock result("wake_failed", read())
            woke = true
            now = await { !allowed() || it.interactive } ?: return@withLock result("device_unavailable")
            if (!allowed()) return@withLock result("disabled", now)
            if (!now.interactive) return@withLock result("wake_failed", now)
        }
        if (!allowed()) return@withLock result("disabled", now)
        val wasSecure = now.secure
        // 这次流程刚把屏幕唤醒时锁屏可能还没准备好接受 dismiss:在同一个有界等待([waitMs])里重试,每次前复核授权
        if (!retrying(enabled = woke, ::allowed) { port.dismiss() }) {
            return@withLock result(if (allowed()) "dismiss_failed" else "disabled", read())
        }
        now = await { !allowed() || it.ready || it.secure && it.keyguardShowing } ?: return@withLock result("device_unavailable")
        if (!allowed()) return@withLock result("disabled", now)
        // A non-secure lock screen dismissed by this flow's swipe, or a screen-off woken by this flow, counts as unlocked by this flow; a secure lock screen that opened without a credential was unlocked by the user at the same time
        if (now.ready) return@withLock result("unlocked", now, performedUnlock = woke || !wasSecure)
        if (!now.secure) return@withLock result("unlock_failed", now)
        // The remote branch checks the placeholder before loading the credential, saving one decryption; the on-device branch's separate placeholder is checked atomically by beginLocalAttempt before typing
        if (!local && store.attemptBlocked) return@withLock result("attempt_blocked", now)
        val credential = store.credential() ?: return@withLock result("credential_required", now)
        credential.use {
            if (!DeviceCredential.valid(it.kind, it.value)) return@withLock result("credential_unavailable", now)
            var targetReady = false
            var revealed = false
            var fallbackTried = false
            var waited = 0L
            while (!targetReady && waited < waitMs) {
                val current = read() ?: return@withLock result("device_unavailable")
                if (current.ready) return@withLock result("unlocked", current)
                if (!allowed()) return@withLock result("disabled", current)
                if (!current.helperAvailable) return@withLock result("helper_unavailable", current)
                targetReady = port.credentialReady(it.kind)
                // After dismiss, give the system credential page one polling cycle first; only if it is still unrecognized do one navigation, avoiding a needless swipe on an already usable PIN page.
                if (!targetReady) {
                    if (!revealed && waited >= pollMs) {
                        if (!allowed()) return@withLock result("disabled", current)
                        revealed = true
                        port.revealCredential()
                    } else if (revealed && !fallbackTried && waited >= pollMs * 2) {
                        // When the biometric-first page remains after swiping up (no credential field), give the fallback tap one more chance.
                        if (!allowed()) return@withLock result("disabled", current)
                        fallbackTried = true
                        port.revealCredentialFallback()
                    }
                    delay(pollMs); waited += pollMs
                }
            }
            if (!targetReady) {
                val current = read() ?: return@withLock result("device_unavailable")
                if (current.ready) return@withLock result("unlocked", current)
                if (!allowed()) return@withLock result("disabled", current)
                if (!current.helperAvailable) return@withLock result("helper_unavailable", current)
                val detected = port.detectedCredentialKind()
                val diagnostics = port.credentialDiagnostics()
                if (detected != null && detected != it.kind) {
                    return@withLock result("credential_kind_mismatch", current, it.kind, detected, diagnostics)
                }
                return@withLock result("unsupported_keyguard", current, keyguardDiagnostics = diagnostics)
            }
            if (!allowed()) return@withLock result("disabled", read())
            val reserved = if (local) store.beginLocalAttempt() else store.beginAttempt(it)
            if (!reserved) return@withLock result("attempt_blocked", read())
            // From this point on, even an interruption keeps the placeholder so nothing is typed twice. It is only cleared once the system is unlocked.
            if (!allowed()) return@withLock result("disabled", read())
            if (!port.enter(it)) {
                val after = read()
                if (after?.ready == true) { store.completeAttempt(it); return@withLock result("unlocked", after) }
                return@withLock result("input_failed", after)
            }
            now = await { s -> s.ready } ?: return@withLock result("device_unavailable")
            if (now.ready) { store.completeAttempt(it); result("unlocked", now, performedUnlock = true) }
            else result("unlock_failed", now)
        }
    }

    private suspend fun prepare(local: Boolean, authorized: () -> Boolean): DeviceAccessResult? {
        val on = if (local) store.localEnabled else store.enabled
        if (!on || !authorized()) return result("disabled", read())
        val s = read() ?: return result("device_unavailable")
        if (!s.userUnlocked) return result("first_unlock_required", s)
        if (!s.helperAvailable) return result("helper_unavailable", s)
        return null
    }

    private suspend fun read(): DeviceState? = try { port.state() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    private suspend fun await(done: (DeviceState) -> Boolean): DeviceState? {
        var waited = 0L
        var s = read()
        while (s != null && !done(s) && waited < waitMs) { delay(pollMs); waited += pollMs; s = read() }
        return s
    }
    /** [action] 成功就返回 true;[enabled] 时失败了隔 [pollMs] 再试,总共不超过 [waitMs],[allowed] 一收回就停。 */
    private suspend fun retrying(enabled: Boolean, allowed: () -> Boolean, action: suspend () -> Boolean): Boolean {
        var waited = 0L
        while (true) {
            if (action()) return true
            if (!enabled || waited >= waitMs || !allowed()) return false
            delay(pollMs); waited += pollMs
        }
    }
    private fun result(status: String, s: DeviceState? = null, storedKind: CredentialKind? = null, detectedKind: CredentialKind? = null,
        keyguardDiagnostics: List<String> = emptyList(), performedUnlock: Boolean = false) =
        DeviceAccessResult(status, s, store.enabled, store.credentialConfigured, store.attemptBlocked, storedKind, detectedKind,
            keyguardDiagnostics, performedUnlock)
}
