package com.wangzan101.mika.task

import com.wangzan101.mika.cap.phone.shell.AppAudioKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the apps on Claire's display silent during a task. PLAY_AUDIO is set per "package + user" ([AppAudioKey]), so the original instance and the other one (clone, work profile) are muted separately.
 *
 * - [beforeOpen]: when Claire is about to launch an app, mute it before the launch is issued so the first few seconds are silent; if this step fails, don't report it yet and retry once the app actually opens.
 * - [opened]: an app appears on Claire's display (the launch target, or one opened from another app, or the other instance coming to the front): mute it if not already muted; if it cannot be muted, record it as "may produce sound".
 * - [notOpened]: the launch was rejected (not installed, the user is using it…): immediately restore the one muted in advance that never opened, so it is not needlessly silent during the task.
 * - [release]: when the user takes over, give the app's sound back; a later [opened] mutes it again.
 * - [unmute]: the user explicitly restores sound; it is then never auto-muted again in this task, and stays audible even after a takeover hand-back.
 * - [muteAgain]: the user explicitly mutes again (the reverse of [unmute]); the auto-mute decision is bypassed, and after muting it returns to automatic management.
 *
 * Never touched: Claire itself; system packages with no launcher entry (system UI, chooser, permission dialogs and the like, where muting also silences system sounds);
 * and whatever the user can see on the main screen right now (muting works per package, so muting would silence what is in the user's hands too). When muting is turned off in settings, nothing is muted.
 * At teardown everything is restored together by [AudioMute.restoreAll], including whatever was muted mid-task.
 */
class AppMuter(
    private val audio: AudioMute,
    /** Settings › Task run › "mute opened apps"; read fresh every time, so turning it off mid-task counts too. */
    private val enabled: () -> Boolean,
    /** Whether this package can be muted (see the first two items under "never touched" in the class doc). */
    private val mutable: (packageName: String) -> Boolean,
    /** Whether this package (the [user] instance, or the original when null) is visible on the main screen right now. */
    private val onMainScreen: (packageName: String, user: Int?) -> Boolean,
    /** Should have been muted but wasn't: report "this app may produce sound" in the task progress. */
    private val failed: (packageName: String) -> Unit,
) {
    /** Keys already muted (not yet restored) in this task. */
    private val muted = HashSet<String>()

    /** Keys muted before launch that have not been seen opening yet: used to restore them when the launch is rejected. */
    private val early = HashSet<String>()

    /** Keys whose sound the user explicitly restored; they are no longer auto-muted in this task. */
    private val unmuted = HashSet<String>()

    /** Keys whose sound the user explicitly restored and that have not been muted again; the mini-window's sound button uses them to show its direction, reflecting only real audio state. */
    private val _userUnmuted = MutableStateFlow<Set<String>>(emptySet())
    val userUnmuted: StateFlow<Set<String>> = _userUnmuted.asStateFlow()

    @Synchronized
    fun beforeOpen(packageName: String, user: Int?) {
        val key = AppAudioKey.of(packageName, user)
        if (key in muted || key in unmuted || !wanted(packageName) || onMainScreen(packageName, user)) return
        if (audio.mute(key)) {
            muted += key
            early += key
        }
    }

    @Synchronized
    fun opened(packageName: String, user: Int?) {
        val key = AppAudioKey.of(packageName, user)
        early -= key
        if (key in muted || key in unmuted || !wanted(packageName)) return
        // The user is using it on the main screen right now: don't mute, and report truthfully that it may produce sound
        if (onMainScreen(packageName, user) || !audio.mute(key)) {
            failed(packageName)
            return
        }
        muted += key
    }

    @Synchronized
    fun notOpened(packageName: String, user: Int?) {
        val key = AppAudioKey.of(packageName, user)
        if (!early.remove(key)) return
        if (audio.restore(key)) muted -= key
    }

    /** Restores one app's sound (user takeover); returns false if it cannot be restored, leaving it in the record to retry at teardown. */
    @Synchronized
    fun release(packageName: String, user: Int?): Boolean {
        val key = AppAudioKey.of(packageName, user)
        early -= key
        if (!audio.restore(key)) return false
        muted -= key
        return true
    }

    /**
     * The user explicitly restores sound. After success it is no longer auto-muted in this task; on failure the mute and the persisted record are kept,
     * so repeated taps can retry, and restoreAll at task teardown can still restore it.
     */
    @Synchronized
    fun unmute(packageName: String, user: Int?): Boolean {
        val key = AppAudioKey.of(packageName, user)
        if (!audio.restore(key)) return false
        early -= key
        muted -= key
        unmuted += key
        _userUnmuted.value = unmuted.toSet()
        return true
    }

    /**
     * The user explicitly mutes again (the reverse action of the mini-window). The auto-mute decision is bypassed: explicit intent wins, so even apps visible on the main screen are muted.
     * After success it returns to automatic management, consistent with the mute ledger and restored as usual at teardown; on failure the restored state is kept so it can be retried.
     */
    @Synchronized
    fun muteAgain(packageName: String, user: Int?): Boolean {
        val key = AppAudioKey.of(packageName, user)
        if (!audio.mute(key)) return false
        early -= key
        unmuted -= key
        muted += key
        _userUnmuted.value = unmuted.toSet()
        return true
    }

    private fun wanted(packageName: String) = enabled() && mutable(packageName)
}
