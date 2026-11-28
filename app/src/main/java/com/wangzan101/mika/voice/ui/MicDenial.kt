package com.wangzan101.mika.voice.ui

import android.Manifest
import android.content.Context
import androidx.core.app.ActivityCompat
import com.wangzan101.mika.browser.ui.findActivity

/**
 * Marker for a previously denied recording permission, plus the "system will no longer prompt" check. Each entry point records into its own prefs file ([InputBar] push-to-talk, [Call] calls),
 * all with the key `mic_denied`; the file names and key already exist on users' devices and must not change.
 */
internal class MicDenial private constructor(private val prefs: String) {
    fun denied(context: Context): Boolean =
        context.getSharedPreferences(prefs, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun record(context: Context, denied: Boolean) =
        context.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit().putBoolean(KEY, denied).apply()

    /** Denied before, and the system no longer prompts either, so explaining no longer helps: the only way is to enable it on the app's permissions page. */
    fun blocked(context: Context): Boolean {
        val activity = context.findActivity()
        return denied(context) && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
    }

    companion object {
        private const val KEY = "mic_denied"
        val InputBar = MicDenial("voice_input")
        val Call = MicDenial("voice_call")
    }
}
