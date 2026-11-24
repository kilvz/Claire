package com.wangzan101.mika.teach

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wangzan101.mika.MikaApp

/** Pause, resume, end and discard from the notification. */
class TeachReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? MikaApp ?: return
        if (!app.teachReady) return
        when (intent.action) {
            TeachNotifier.PAUSE -> app.teach.pause()
            TeachNotifier.RESUME -> app.teach.resume()
            TeachNotifier.END -> app.teach.end()
            TeachNotifier.DISCARD -> app.teach.discard()
        }
    }
}
