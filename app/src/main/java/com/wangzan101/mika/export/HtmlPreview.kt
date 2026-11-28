package com.wangzan101.mika.export

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.core.export.ExportNames
import java.io.File

internal const val HTML_PREVIEW_ORIGIN = "https://appassets.androidplatform.net"

/** The WebView and staging directory of one HTML preview are held by the same session; release order is fixed and repeatable. */
internal class HtmlPreviewSession(context: Context, val preview: ExportPreview.Html) {
    private val loader = htmlPreviewAssetLoader(context, preview.directory)

    val webView: WebView = HtmlPreviewWebView(context).apply {
        settings.javaScriptEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        @Suppress("DEPRECATION")
        settings.databaseEnabled = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false
        settings.setGeolocationEnabled(false)
        settings.blockNetworkLoads = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.mediaPlaybackRequiresUserGesture = true
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? =
                loader.shouldInterceptRequest(Uri.parse(url))

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true
        }
        loadUrl("$HTML_PREVIEW_ORIGIN/${Uri.encode(ExportNames.entryPath(preview.entry))}")
    }

    @Volatile
    var isReleased: Boolean = false
        private set

    fun release() {
        if (isReleased) return
        isReleased = true
        webView.stopLoading()
        webView.clearHistory()
        webView.removeAllViews()
        webView.destroy()
        preview.release()
    }
}

/** Records release state with the real destroy lifecycle, so an old WebView does not stay alive after recomposition. */
internal class HtmlPreviewWebView(context: Context) : WebView(context) {
    @Volatile
    var isDestroyed: Boolean = false
        private set

    override fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        super.destroy()
    }
}

/** Only exposes regular files inside this staging root; neither normalized paths nor link targets may escape it. */
internal fun htmlPreviewAssetLoader(context: Context, directory: File): WebViewAssetLoader {
    val root = directory.canonicalFile
    val rootPath = root.toPath()
    val delegate = WebViewAssetLoader.InternalStoragePathHandler(context, root)
    val handler = WebViewAssetLoader.PathHandler { path ->
        val target = try {
            File(root, ExportNames.entryPath(path)).canonicalFile
        } catch (e: IllegalArgumentException) {
            null
        }
        if (target == null || !target.toPath().startsWith(rootPath) || !target.isFile) {
            null
        } else {
            delegate.handle(path)
        }
    }
    return WebViewAssetLoader.Builder().addPathHandler("/", handler).build()
}

@Composable
internal fun HtmlPreview(preview: ExportPreview.Html, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val session = remember(preview) { HtmlPreviewSession(context, preview) }
    DisposableEffect(session) {
        onDispose { session.release() }
    }
    AndroidView(
        modifier = modifier,
        factory = { session.webView },
        onRelease = { session.release() },
    )
}
