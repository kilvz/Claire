package com.wangzan101.mika.ui.terminal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.animationsOff
import com.wangzan101.mika.ui.theme.appearanceDark
import com.wangzan101.mika.ui.theme.systemBarsFor

/**
 * User terminal: a standalone page containing a shell for this conversation's workspace. It doesn't go through the model and doesn't write a run record;
 * leaving the page doesn't end the session (it survives rotation and going Home and back), and it ends together with the app when the process is killed.
 */
class TerminalActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION)
        val workspaceKey = intent.getStringExtra(EXTRA_WORKSPACE)?.let(WorkspaceKey::parse)
        if (conversationId == null && workspaceKey == null) { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE)
        val app = applicationContext as MikaApp
        setContent {
            val dark = appearanceDark()
            LaunchedEffect(dark) { systemBarsFor(dark) }
            MikaTheme(dark = dark, reduceMotion = animationsOff(this)) {
                TerminalRoute(app, conversationId, workspaceKey, title, onDone = ::finish)
            }
        }
    }

    companion object {
        const val EXTRA_CONVERSATION = "conversationId"
        const val EXTRA_WORKSPACE = "workspaceKey"
        const val EXTRA_TITLE = "title"
    }
}

/** Opens the terminal from the conversation menu or from a workspace row in settings. */
object TerminalLauncher {
    fun open(context: Context, conversationId: String, title: String?) {
        val intent = Intent(context, TerminalActivity::class.java)
            .putExtra(TerminalActivity.EXTRA_CONVERSATION, conversationId)
            .putExtra(TerminalActivity.EXTRA_TITLE, title)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openWorkspace(context: Context, workspace: WorkspaceKey, title: String?) {
        val intent = Intent(context, TerminalActivity::class.java)
            .putExtra(TerminalActivity.EXTRA_WORKSPACE, workspace.wire)
            .putExtra(TerminalActivity.EXTRA_TITLE, title)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
