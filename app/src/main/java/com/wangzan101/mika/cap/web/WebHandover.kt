package com.wangzan101.mika.cap.web

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import com.wangzan101.mika.takeover.HandoverActivity
import com.wangzan101.mika.task.TaskService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Web takeover: moves **the same** WebView out of the background host into [WebTakeoverPage] on the main screen for the user to operate, and puts it back when done.
 * Page state (forms, scrolling, one-time tokens in a login flow) is preserved as-is. At most one takeover at a time in the process.
 *
 * Two uses:
 * - Task takeover ([show]): buttons in the UI are handed via [Session.onAction] to the task service (handled / I finished it myself / cancel the task / leave the back button for now);
 *   on hand-back the takeover state machine calls [end], gets the number of inputs made during the takeover and the page title, and puts the view back into [Session.home].
 * - Settings-screen login ([showLogin]): opens a new visible tab with only "Done"; on Done it takes a look at the page and, unless the page still looks signed-out, records the site as signed in.
 * A task takeover is only recorded when the page visibly shows signed-in at hand-back ([LoginProbe]); if it does not, nothing is recorded (what the user handled may have been just a CAPTCHA).
 */
object WebHandover {
    enum class Action { HandBack, FinishedMyself, Cancel, PutAside, Done }

    /**
     * One takeover. When [home] is null there is no background host (settings-screen login) and the tab is destroyed at the end. A null [reason] means settings-login mode.
     * [message] is a one-line explanation for the user (payment information included when relevant).
     */
    class Session internal constructor(
        val tab: WebTab,
        val home: WebHost?,
        val reason: String?,
        val message: String?,
        val waitKey: Int,
        val onAction: (Action) -> Unit,
    ) {
        /** Number of touches and key presses the user made on the page in the takeover UI. */
        @Volatile var inputs: Int = 0
            internal set

        /** Page URL and title recorded by the takeover UI on the main thread at any time: at the end it may be on another thread, where WebView cannot be read. */
        @Volatile var url: String? = null
            internal set
        @Volatile var title: String? = null
            internal set
        val loginMode: Boolean get() = reason == null
    }

    private val main = Handler(Looper.getMainLooper())
    /** Upper bound for waiting for the page to answer after "Done" is tapped on the settings-screen login. */
    private const val CONFIRM_WAIT_MS = 1_500L

    /** The settings screen's site login is open: a task's web takeover has to wait until it closes. */
    val loginOpen: Boolean get() = _session.value?.loginMode == true
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    /**
     * Task takeover: open the takeover UI and move the tab over. Returns false when a takeover is already in progress, or the tab does not belong to [taskId], or is no longer usable.
     * [currentKey] supplies the task's current wait key: if it has already changed by the time the button is pressed (that wait is over), nothing happens.
     */
    fun show(
        context: Context, tab: WebTab, home: WebHost, reason: String, message: String?, waitKey: Int, taskId: String,
        onAction: (Action) -> Unit = taskActions(context.applicationContext, waitKey, null),
        currentKey: (() -> Int?)? = null,
    ): Boolean {
        if (_session.value != null || tab.crashed || tab.destroyed || home.closed || tab.ownerTaskId != taskId) return false
        val guarded: (Action) -> Unit = { a ->
            val now = currentKey?.invoke()
            // "Cancel task" counts no matter which wait it is
            if (a != Action.Cancel && currentKey != null && now != waitKey) android.util.Log.i("MikaWebHandover", "ignored $a for a past wait ($waitKey, now $now)")
            else onAction(a)
        }
        _session.value = Session(tab, home, reason, message, waitKey, guarded)
        context.startActivity(WebTakeoverPage.intent(context))
        return true
    }

    /** Settings screen's "open login": open a visible tab on the main screen, let the user log in themselves, and end it when "Done" is tapped. Called on the main thread. */
    fun showLogin(context: Context, host: String, onDone: (String) -> Unit = { WebSites.registry(context).markLoggedIn(it) }): Boolean {
        if (_session.value != null) return false
        val url = "https://${host.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')}/"
        if (WebUrlPolicy.checkNavigable(url) != null) return false
        val tab = WebTab(context.applicationContext, "login", "settings")
        tab.view.loadUrl(url)
        _session.value = Session(tab, null, null, null, -1) { action ->
            if (action != Action.Done) {
                end()
                return@Session
            }
            // The button is pressed on the main thread: the user says they are done, so record it unless the page still looks signed-out; close the tab after reading
            val fallback = (tab.view.url ?: _session.value?.url)?.let(WebUrlPolicy::host)
            val decided = java.util.concurrent.atomic.AtomicBoolean(false)
            fun finish(host: String?, state: LoginState) {
                if (!decided.compareAndSet(false, true)) return
                if (host != null && state != LoginState.SignedOut) onDone(host)
                end()
            }
            LoginProbe.probe(tab) { host, state -> finish(host, state) }
            main.postDelayed({ finish(fallback, LoginState.Unknown) }, CONFIRM_WAIT_MS)
        }
        context.startActivity(WebTakeoverPage.intent(context))
        return true
    }

    /**
     * Ends this takeover: the UI closes and the view goes back into the background host (for settings-screen login the tab is destroyed). Returns the number of inputs during the takeover and the page title;
     * null when no takeover is in progress. With [loggedIn] (the user handed back), once the view is back, take a look at the page and record it in [registry] only if it visibly shows signed-in. Callable from any thread.
     */
    fun end(loggedIn: Boolean = false, registry: SiteRegistry? = null, tab: WebTab? = null): HandoverActivity? {
        val s = _session.value ?: return null
        // If [tab] is given, end only that tab's takeover (with parallel tasks, the others are left alone); each takeover ends only once
        if (tab != null && s.tab !== tab) return null
        if (!_session.compareAndSet(s, null)) return null
        main.post {
            restore(s)
            if (loggedIn && registry != null) LoginProbe.probe(s.tab) { host, state ->
                if (state == LoginState.SignedIn) registry.markLoggedIn(host)
                else android.util.Log.i("MikaWebHandover", "takeover on $host handed back; sign-in not confirmed ($state), not recorded")
            }
        }
        return HandoverActivity(s.inputs, s.title?.takeIf { it.isNotBlank() }, s.url?.takeIf { it.isNotBlank() })
    }

    /** Puts the view back into the background host; if there is no host (settings-screen login), destroys it. */
    internal fun restore(s: Session) {
        if (s.tab.destroyed) return
        val home = s.home
        if (home == null) {
            s.tab.destroy()
            return
        }
        // The tab crashed or the host is already closed (the task has ended): do not attach it back, leave it for the tab pool to clean up
        if (s.tab.crashed || home.closed) {
            (s.tab.view.parent as? android.view.ViewGroup)?.removeView(s.tab.view)
            return
        }
        home.attach(s.tab)
    }

    /** Task-takeover buttons → task service actions, carrying this wait's key and the task id (with several tasks running at once, only this one is affected). */
    fun taskActions(context: Context, waitKey: Int, runKey: String?): (Action) -> Unit = { action ->
        when (action) {
            Action.HandBack, Action.Done -> TaskService.handBack(context, waitKey, runKey)
            Action.FinishedMyself -> TaskService.finishedMyself(context, waitKey, runKey)
            Action.Cancel -> TaskService.cancel(context, runKey)
            Action.PutAside -> TaskService.putAside(context, waitKey, runKey)
        }
    }
}
