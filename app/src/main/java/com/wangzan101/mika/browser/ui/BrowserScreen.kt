package com.wangzan101.mika.browser.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.rememberCoroutineScope
import com.wangzan101.mika.browser.PageAskMode
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.wangzan101.mika.ui.components.MikaTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wangzan101.mika.browser.BrowserFavorites
import com.wangzan101.mika.browser.ChooserMimeTypes
import com.wangzan101.mika.browser.RecentPages
import com.wangzan101.mika.browser.SiteLooks
import com.wangzan101.mika.browser.StartPageModel
import com.wangzan101.mika.browser.UserBrowser
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import com.wangzan101.mika.core.screen.uistate.PageDialog
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.theme.MikaType

/**
 * The browser page ("Browser" in the sidebar): with no web page open it is the start page ([BrowserStart]: search box, favorites, recent),
 * with one open it is the web page ([BrowserPageBar] + page + [BrowserToolbar]; "more" is [BrowserMoreSheet]).
 * Under the page is "Ask Claire" ([PageAskBar]): ask about this page, and the page is lent to that task as its current web tab; while it works,
 * the lower half of the page is covered by a conversation panel ([PagePanel]) and a ring of light glows around the page ([PageGlow]); as soon as
 * you touch the page Claire pauses, the panel shrinks to a small card ([PageCard]) and a "continue" bar appears on top ([PausedBanner]).
 * When text on the page is selected, the text selection menu gets an extra "Ask Claire" item that carries the selected text along. State is in [PageChat].
 * The system back key first goes back inside the page; when it cannot, it returns to the start page, and on the start page it goes back to where you came from.
 * The web view lives in the process ([UserBrowser]) and keeps being used across rotation; it is closed when leaving this page (not a rotation, and no
 * browser page elsewhere).
 */
@Composable
fun BrowserScreen(
    browser: UserBrowser,
    onBack: () -> Unit,
    /** True when opened from a link elsewhere: going back past the start closes the tab and returns to the previous page instead of stopping at the browser home page. */
    returnOnClose: Boolean = false,
) {
    val context = LocalContext.current
    var filePickerId by rememberSaveable { mutableStateOf<String?>(null) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = filePickerId
        filePickerId = null
        if (id != null) browser.answerFileChooser(id, uri?.let { listOf(it) })
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val id = filePickerId
        filePickerId = null
        if (id != null) browser.answerFileChooser(id, uris)
    }
    var mediaPermissionId by rememberSaveable { mutableStateOf<String?>(null) }
    var locationPermissionId by rememberSaveable { mutableStateOf<String?>(null) }
    val askMediaPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val id = mediaPermissionId
        mediaPermissionId = null
        if (id != null) browser.answerPagePermission(id, granted.isNotEmpty() && granted.values.all { it })
    }
    val askLocationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val id = locationPermissionId
        locationPermissionId = null
        if (id != null) browser.answerGeolocation(id, granted.values.any { it })
    }
    val page by browser.page.collectAsState()
    val dialog by browser.dialog.collectAsState()
    val notice by browser.notice.collectAsState()
    val filePick by browser.filePick.collectAsState()
    val pagePermission by browser.pagePermission.collectAsState()
    val geoPermission by browser.geoPermission.collectAsState()
    val registry by browser.sites.entries.collectAsState()
    val recentPages by browser.recent.entries.collectAsState()
    val edits by browser.favorites.edits.collectAsState()
    val bookmarks by browser.bookmarks.entries.collectAsState()
    val looks by browser.looks.entries.collectAsState()
    val tabs by browser.tabs.collectAsState()
    val find by browser.find.collectAsState()
    val fullscreen by browser.fullscreen.collectAsState()
    val searchEngines by browser.searchEngine.choices.collectAsState()
    val activity = remember(context) { context.findActivity() }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    var bookmarksOpen by rememberSaveable { mutableStateOf(false) }
    var downloadsOpen by rememberSaveable { mutableStateOf(false) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var tabsOpen by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var clearOpen by rememberSaveable { mutableStateOf(false) }

    val face = remember(looks) { { host: String -> SiteLooks.faceOf(host, looks, browser.looks::iconFile) } }
    val favorites = remember(edits, registry) { BrowserFavorites.combine(edits, registry) }
    val allRecent = remember(recentPages, looks, searchEngines) { StartPageModel.recentRows(recentPages, { face(it).name }, searchEngines.all) }
    val recent = remember(allRecent) { allRecent.take(RecentPages.START_LIMIT) }
    val toast: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    val scope = rememberCoroutineScope()
    val chat = remember(browser) { PageChat(context.applicationContext, browser, scope) }
    // When the page changes (and nothing has been asked on this page yet) look again for the conversation to continue; closing the page returns to nothing
    val pageUrl = page?.url
    LaunchedEffect(pageUrl) { chat.onPage(pageUrl) }
    // Keep the original request ID until the system chooser returns; a new request is only shown after the original chooser finishes.
    LaunchedEffect(filePick, filePickerId) {
        val request = filePick ?: return@LaunchedEffect
        if (filePickerId != null || request.started || browser.filePick.value?.id != request.id) return@LaunchedEffect
        request.started = true
        filePickerId = request.id
        val params = request.params
        val types = chooserMimeTypes(params)
        if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) pickFiles.launch(types) else pickFile.launch(types)
    }

    // This browser page place: registered on entry, takes the web view over when back in the foreground (with two UI instances it is attached to
    // the visible one), and hands it back on leaving; the page is closed only when the last place leaves (and it is not a rotation)
    val place = remember(browser, activity) { browser.newHost(activity ?: context.applicationContext) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(browser, place, lifecycle) {
        browser.openHost(place)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) browser.hostStarted(place) }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            browser.closeHost(place, keepPage = activity?.isChangingConfigurations == true)
        }
    }
    BackHandler(enabled = page != null || tabs.isNotEmpty()) {
        val leave = returnOnClose && !browser.canGoBackInTab()
        browser.systemBack()
        if (leave) onBack()
    }
    BackHandler(enabled = find.active) { browser.stopFind() }
    LaunchedEffect(notice) {
        notice?.let {
            toast(it)
            browser.noticeShown()
        }
    }

    PageBackground {
        val p = page
        if (p == null) {
            LaunchedEffect(Unit) { moreOpen = false }
            BrowserStart(
                sites = favorites, recent = recent, face = face,
                onBack = onBack,
                onSubmit = { input -> browser.submit(input)?.let(toast) },
                onOpenUrl = { url -> browser.open(url)?.let(toast) },
                onOpenSite = { site -> browser.openFavorite(site)?.let(toast) },
                onRemoveSite = { site ->
                    browser.favorites.remove(site.host)
                    toast(tr(R.string.browser_removed, face(site.host).name))
                },
                onAddHint = { toast(BROWSER_ADD_HINT) },
                onClearRecent = browser.recent::clear,
                bookmarks = bookmarks,
                onOpenBookmark = { b -> browser.open(b.url)?.let(toast) },
                onShowBookmarks = { bookmarksOpen = true },
                onDownloads = { downloadsOpen = true },
                tabsCount = tabs.size,
                onMenu = { menuOpen = true },
                onHistory = { historyOpen = true },
                engine = searchEngines.selected,
                custom = searchEngines.custom,
                onPickEngine = browser.searchEngine::set,
                onAddEngine = browser.searchEngine::addCustom,
                onRemoveEngine = { browser.searchEngine.removeCustom(it.id) },
            )
        } else {
            val host = WebUrlPolicy.host(p.url)
            val loginBanner = p.loginFor != null && !BrowserFavorites.loggedIn(registry, p.loginFor)
            Column(Modifier.fillMaxSize()) {
                BrowserPageBar(
                    p, host?.let(face),
                    onClose = browser::closeActiveTab,
                    onSubmit = { input -> browser.submit(input)?.let(toast) },
                    onReload = browser::reload, onStop = browser::stop,
                    engines = searchEngines.all,
                )
                if (find.active) {
                    BrowserFindBar(
                        find, browser::setFindQuery,
                        onPrev = { browser.findNext(false) }, onNext = { browser.findNext(true) }, onClose = browser::stopFind,
                    )
                }
                if (loginBanner) LoginBanner(desktop = p.desktop, onDone = browser::confirmLogin)
                val mode = chat.mode
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // The web view is put into this container by UserBrowser (the same one even after switching tabs); a finger down tells the task borrowing the page, and selecting text puts "Ask Claire" in the menu
                    AndroidView(
                        factory = { ctx ->
                            PageFrame(ctx, onTouch = chat::touched, onAsk = { done ->
                                browser.readSelection { text ->
                                    done()
                                    text?.let(chat::askAbout)
                                }
                            }).also { browser.setContainer(place, it) }
                        },
                        modifier = Modifier.fillMaxSize().testTag("browser-page"),
                        onRelease = { browser.setContainer(place, null) },
                    )
                    PageAskOverlay(chat, site = host?.let { face(it).name } ?: p.url, pageTitle = p.title)
                }
                if (mode == PageAskMode.Bar && p.loginFor == null) PageAskBar(chat, Modifier.imePadding())
                BrowserToolbar(
                    p, onBackPage = browser::back, onForward = browser::forward, onHome = browser::closeActiveTab,
                    onShare = { share(context, p.url, p.title)?.let(toast) },
                    onMore = { moreOpen = true },
                )
            }
            if (moreOpen) {
                val favorite = host != null && BrowserFavorites.contains(favorites, host)
                val bookmarked = bookmarks.any { it.url.trim().substringBefore('#') == p.url.trim().substringBefore('#') }
                BrowserMoreSheet(
                    face = host?.let(face), host = host, favorite = favorite, desktop = p.desktop,
                    onDismiss = { moreOpen = false },
                    onReload = browser::reload,
                    onCopy = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(p.title.ifBlank { p.url }, p.url))
                        toast(tr(R.string.browser_link_copied))
                    },
                    onOpenOutside = { openOutside(context, p.url)?.let(toast) },
                    bookmarked = bookmarked,
                    onToggleBookmark = {
                        if (bookmarked) {
                            browser.bookmarks.remove(p.url)
                            toast(tr(R.string.browser_unbookmarked))
                        } else if (browser.bookmarks.add(p.url, p.title)) {
                            toast(tr(R.string.browser_bookmarked))
                        }
                    },
                    onDownloads = { downloadsOpen = true },
                    onToggleFavorite = {
                        if (host == null) return@BrowserMoreSheet
                        if (favorite) {
                            browser.favorites.remove(host)
                            toast(tr(R.string.browser_unfavorited))
                        } else {
                            browser.favorites.add(host)
                            toast(tr(R.string.browser_favorited))
                        }
                    },
                    onToggleDesktop = {
                        browser.setDesktop(!p.desktop)
                        toast(if (p.desktop) tr(R.string.browser_switched_mobile) else tr(R.string.browser_switched_desktop))
                    },
                    onTabs = { tabsOpen = true },
                    tabsCount = tabs.size,
                    onHistory = { historyOpen = true },
                    onFind = browser::startFind,
                    onClearData = { clearOpen = true },
                )
            }
        }
    }

    if (bookmarksOpen) {
        BookmarksSheet(
            bookmarks = bookmarks, face = face,
            onDismiss = { bookmarksOpen = false },
            onOpen = { b -> bookmarksOpen = false; browser.open(b.url)?.let(toast) },
            onRemove = { b -> browser.bookmarks.remove(b.url); toast(tr(R.string.browser_unbookmarked)) },
            onRename = { b, title -> browser.bookmarks.rename(b.url, title) },
        )
    }
    if (downloadsOpen) {
        DownloadsSheet(browser.downloads, onDismiss = { downloadsOpen = false })
    }
    val askingAndroid = mediaPermissionId != null || locationPermissionId != null
    pagePermission?.takeUnless { askingAndroid }?.let { req ->
        WebPermissionDialog(
            site = req.origin,
            what = mediaPermissionText(req.value.resources),
            onAllow = allow@{
                if (mediaPermissionId != null || locationPermissionId != null) return@allow
                if (browser.pagePermission.value?.id != req.id) return@allow
                val missing = mediaAndroidPermissions(req.value.resources)
                    .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) browser.answerPagePermission(req.id, true) else {
                    mediaPermissionId = req.id
                    askMediaPermissions.launch(missing.toTypedArray())
                }
            },
            onDeny = { browser.answerPagePermission(req.id, false) },
        )
    }
    geoPermission?.takeUnless { askingAndroid || pagePermission != null }?.let { req ->
        WebPermissionDialog(
            site = req.origin,
            what = tr(R.string.browser_permission_location),
            onAllow = allow@{
                if (mediaPermissionId != null || locationPermissionId != null) return@allow
                if (browser.geoPermission.value?.id != req.id) return@allow
                val missing = LOCATION_PERMISSIONS.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) browser.answerGeolocation(req.id, true) else {
                    locationPermissionId = req.id
                    askLocationPermission.launch(missing.toTypedArray())
                }
            },
            onDeny = { browser.answerGeolocation(req.id, false) },
        )
    }
    if (menuOpen) {
        BrowserMenuSheet(
            tabsCount = tabs.size,
            onDismiss = { menuOpen = false },
            onTabs = { menuOpen = false; tabsOpen = true },
            onHistory = { menuOpen = false; historyOpen = true },
            onClearData = { menuOpen = false; clearOpen = true },
        )
    }
    if (tabsOpen) {
        TabsSheet(
            tabs = tabs,
            onDismiss = { tabsOpen = false },
            onSelect = { id -> tabsOpen = false; browser.selectTab(id) },
            onClose = browser::closeTab,
            onNew = { tabsOpen = false; browser.newTab() },
        )
    }
    if (historyOpen) {
        HistorySheet(
            pages = allRecent, now = System.currentTimeMillis(),
            onDismiss = { historyOpen = false },
            onOpen = { r -> historyOpen = false; browser.open(r.url)?.let(toast) },
            onRemove = { r -> browser.recent.remove(r.url) },
            onClear = browser.recent::clear,
        )
    }
    if (clearOpen) {
        ClearDataSheet(onDismiss = { clearOpen = false }) { history, siteData, cache ->
            browser.clearBrowsingData(history, siteData, cache)
            clearOpen = false
            toast(tr(R.string.browser_data_cleared))
        }
    }

    fullscreen?.let { f ->
        key(f) {
            Dialog(
                onDismissRequest = { browser.exitFullscreen() },
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) {
                val view = LocalView.current
                // The compat helper is the API 29-safe way to reach the dialog window's controller.
                @Suppress("DEPRECATION")
                val controller = remember(view) { ViewCompat.getWindowInsetsController(view) }
                DisposableEffect(view) {
                    controller?.hide(WindowInsetsCompat.Type.systemBars())
                    controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
                }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        factory = { ctx ->
                            FrameLayout(ctx).also {
                                (f.view.parent as? ViewGroup)?.removeView(f.view)
                                it.addView(f.view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = { it.removeView(f.view) },
                    )
                }
            }
        }
    }

    dialog?.let { d -> PageDialogBox(d, browser::answerDialog) }
}

private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

private fun mediaAndroidPermissions(resources: Array<String>): List<String> = buildList {
    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in resources) add(Manifest.permission.RECORD_AUDIO)
    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources) add(Manifest.permission.CAMERA)
}

private fun mediaPermissionText(resources: Array<String>): String = when {
    PermissionRequest.RESOURCE_AUDIO_CAPTURE in resources && PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources ->
        tr(R.string.browser_permission_camera_mic)
    PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources -> tr(R.string.browser_permission_camera)
    else -> tr(R.string.browser_permission_microphone)
}

/** Converts the file types given by the page into MIME types the system chooser understands; extensions are looked up in the system table, falling back to all types when absent. */
private fun chooserMimeTypes(params: WebChromeClient.FileChooserParams): Array<String> =
    ChooserMimeTypes.of(params.acceptTypes.toList()) { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }.toTypedArray()

@Composable
private fun WebPermissionDialog(site: String, what: String, onAllow: () -> Unit, onDeny: () -> Unit) {
    MikaDialog(
        onDismissRequest = onDeny,
        title = { Text(site) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr(R.string.browser_permission_ask))
                Text(what, style = MikaType.BodyStrong)
            }
        },
        confirmButton = { TextButton(onClick = onAllow) { Text(tr(R.string.browser_permission_allow)) } },
        dismissButton = { TextButton(onClick = onDeny) { Text(tr(R.string.browser_permission_block)) } },
    )
}

/** System share sheet: the URL, with the title as the subject. Returns the reason when it cannot be opened. */
private fun share(context: Context, url: String, title: String): String? {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    if (title.isNotBlank()) send.putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TITLE, title)
    return try {
        context.startActivity(Intent.createChooser(send, null))
        null
    } catch (e: ActivityNotFoundException) {
        tr(R.string.browser_no_share_app)
    }
}

/** Hands it to the system default browser to open. Returns the reason when it cannot be opened. */
private fun openOutside(context: Context, url: String): String? = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    null
} catch (e: ActivityNotFoundException) {
    tr(R.string.browser_no_other_browser)
}

/** Page dialog: an alert only has "OK"; the confirm and leave-page dialogs also have "Cancel"; a prompt dialog carries one input field. */
@Composable
private fun PageDialogBox(d: PageDialog, answer: (Boolean, String?) -> Unit) {
    var input by remember(d) { mutableStateOf(d.defaultText.orEmpty()) }
    val leave = d.kind == PageDialog.BEFORE_UNLOAD
    MikaDialog(
        onDismissRequest = { answer(false, null) },
        title = { Text(if (leave) tr(R.string.browser_leave_q) else tr(R.string.browser_page_says)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (d.message.isNotBlank()) Text(d.message)
                if (leave && d.message.isBlank()) Text(tr(R.string.browser_leave_body))
                if (d.kind == PageDialog.PROMPT) MikaTextField(input, { input = it }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton({ answer(true, if (d.kind == PageDialog.PROMPT) input else null) }) { Text(if (leave) tr(R.string.browser_leave) else tr(R.string.ok_yes)) }
        },
        dismissButton = if (d.kind == PageDialog.ALERT) null else ({ TextButton({ answer(false, null) }) { Text(tr(R.string.cancel)) } }),
    )
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
