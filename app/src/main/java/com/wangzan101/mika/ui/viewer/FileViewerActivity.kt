package com.wangzan101.mika.ui.viewer

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.AppLanguage
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.animationsOff
import com.wangzan101.mika.ui.theme.systemBarsFor

/**
 * The in-app file viewing screen: web pages, Markdown, PDF, images and text-like files are viewed here directly, without hunting for another app.
 * It is a standalone Activity, so besides file cards in the conversation it can also be opened from elsewhere (floating capsule, notifications) via [FileViewer.open].
 */
class FileViewerActivity : ComponentActivity() {
    // On Android 12 and earlier, wrap with the language chosen in the app; from 13 on the system applies it itself
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null) { finish(); return }
        val name = intent.getStringExtra(EXTRA_NAME) ?: uri.lastPathSegment.orEmpty()
        val kind = FileViewer.kindOf(this, uri, name)
        setContent {
            val dark = com.wangzan101.mika.ui.theme.appearanceDark()
            androidx.compose.runtime.LaunchedEffect(dark) { systemBarsFor(dark) }
            MikaTheme(dark = dark, reduceMotion = animationsOff(this)) {
                com.wangzan101.mika.ui.AdaptiveWindow {
                    FileViewerScreen(uri, name, kind, onBack = ::finish)
                }
            }
        }
    }

    companion object {
        const val EXTRA_NAME = "name"
    }
}

/** Entry point for opening files: those viewable in the app go to [FileViewerActivity]; the rest are handed to whichever app in the system can open them. */
object FileViewer {
    fun kindOf(context: Context, uri: Uri, name: String): ViewerKind? =
        ViewerKinds.of(runCatching { context.contentResolver.getType(uri) }.getOrNull(), name)

    fun open(context: Context, uri: Uri, name: String) {
        if (kindOf(context, uri, name) == null) { openElsewhere(context, uri, name); return }
        val intent = Intent(context, FileViewerActivity::class.java).setData(uri).putExtra(FileViewerActivity.EXTRA_NAME, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Open with another app: the system chooser, with a hint shown when no app can open it. */
    fun openElsewhere(context: Context, uri: Uri, name: String) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(context, uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        start(context, Intent.createChooser(intent, name), tr(R.string.viewer_no_open_app))
    }

    /** Share: send the file to another app (with read permission granted). */
    fun share(context: Context, uri: Uri, name: String) {
        val intent = Intent(Intent.ACTION_SEND).setType(mimeOf(context, uri)).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, Intent.createChooser(intent, name), tr(R.string.viewer_no_share_app))
    }

    private fun mimeOf(context: Context, uri: Uri): String =
        runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "*/*"

    private fun start(context: Context, chooser: Intent, failure: String) {
        try {
            context.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, failure, Toast.LENGTH_SHORT).show()
        }
    }
}
