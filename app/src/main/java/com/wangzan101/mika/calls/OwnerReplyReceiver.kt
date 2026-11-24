package com.wangzan101.mika.calls

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wangzan101.mika.MikaApp

/** Where the "reply" action in the owner-question notification lands: registered in the manifest, not exported; the original reply text is handed to [OwnerAskNotifier.deliver]. */
class OwnerReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != OwnerAskNotifier.ACTION_REPLY) return
        val app = context.applicationContext as? MikaApp ?: return
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(OwnerAskNotifier.KEY_REPLY)?.toString()
        OwnerAskNotifier.deliver(app, app.ownerQuestions, intent.getStringExtra(OwnerAskNotifier.EXTRA_TOKEN), reply,
            intent.getStringExtra(OwnerAskNotifier.EXTRA_CONVERSATION))
    }
}
