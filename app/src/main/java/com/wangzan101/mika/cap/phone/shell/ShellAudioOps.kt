package com.wangzan101.mika.cap.phone.shell

import android.os.Binder
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.MODE_RE
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.parsePlayAudioMode

// Assistant service: mute, audio mode and call-audio identity
internal fun ShellService.audioMuted(packageName: String, user: Int?, muted: Boolean): Boolean = synchronized(this.muted) {
    val key = AppAudioKey.of(packageName, user)
    if (muted) {
        if (!this.muted.contains(key)) {
            val (code, out) = appops("get", packageName, user)
            if (code != 0) return false
            this.muted.put(key, parsePlayAudioMode(out))
        }
        appops("set", packageName, user, "deny").first == 0
    } else {
        val ok = restoreAudioMode(packageName, user, AudioMuteLedger.restoreMode(this.muted[key]))
        if (ok) this.muted.remove(key)
        ok
    }
}

/** Writes one entry back by its recorded key; a key in the wrong format counts as handled so it is not kept around forever. */
internal fun ShellService.writeRecorded(key: String, mode: String): Boolean {
    val (pkg, user) = AppAudioKey.parse(key) ?: return true
    return restoreAudioMode(pkg, user, mode)
}

internal fun ShellService.restoreAudioMode(packageName: String, user: Int?, mode: String): Boolean {
    if (appops("set", packageName, user, mode).first == 0) return true
    // Some systems forbid shell from writing AppOps. If muting did not take effect, reading back and confirming the original value is still there is enough to drop the record.
    val (code, output) = appops("get", packageName, user)
    return code == 0 && AudioMuteLedger.readMode(output) == mode
}

/** An empty [user] means the user Claire runs as. */
internal fun ShellService.appops(verb: String, packageName: String, user: Int?, mode: String? = null): Pair<Int, String> {
    val u = (user ?: myUser).toString()
    return if (mode == null) sh("appops", verb, "--user", u, packageName, "PLAY_AUDIO")
    else sh("appops", verb, "--user", u, packageName, "PLAY_AUDIO", mode)
}

/** Any other user ID is returned as is; null when it is the user Claire runs as (handle it as the original copy); also null when invalid, so the caller can refuse it on that basis. */
internal fun ShellService.otherUser(userId: Int): Int? = userId.takeIf { it >= 0 && it != myUser }

internal fun ShellService.audioMode(packageName: String, user: Int?): String? {
    val (code, out) = appops("get", packageName, user)
    return if (code == 0) parsePlayAudioMode(out) else null
}

internal fun ShellService.audioMode(packageName: String, user: Int?, mode: String): Boolean {
    if (!MODE_RE.matches(mode)) return false
    val ok = restoreAudioMode(packageName, user, AudioMuteLedger.restoreMode(mode))
    if (ok) synchronized(muted) { muted.remove(AppAudioKey.of(packageName, user)) }
    return ok
}

internal inline fun <T> ShellService.asCallAudioShell(block: () -> T): T {
    val identity = Binder.clearCallingIdentity()
    return try { block() } finally { Binder.restoreCallingIdentity(identity) }
}
