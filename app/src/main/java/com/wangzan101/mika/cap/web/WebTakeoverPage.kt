package com.wangzan101.mika.cap.web

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.AppLanguage
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.wangzan101.mika.web.WebTakeoverActivity

/**
 * Web takeover UI: full screen on the main display, with a "Claire is waiting for you: <reason>" bar and buttons at the top, and the **same** WebView below (moved over from the background host).
 * Task takeover: "Hand back", "I finished it myself", "Cancel task"; the system back key = leave it for now (the task is still waiting for takeover and the notification remains).
 * Settings-page login: only "Done", and the back key closes it directly.
 * When the takeover ends ([WebHandover.end]) the UI closes itself and the view goes back to the background host.
 */
open class WebTakeoverPage : Activity() {
    private var session: WebHandover.Session? = null
    private lateinit var container: FrameLayout
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ticker = Handler(Looper.getMainLooper())
    private val pageRect = Rect()

    private val recordPage = object : Runnable {
        override fun run() {
            record()
            ticker.postDelayed(this, RECORD_INTERVAL_MS)
        }
    }

    // On Android 12 and earlier, apply the language selected in the App; from 13 on the system applies it itself
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = WebHandover.session.value
        if (s == null || s.tab.crashed) {
            finish()
            return
        }
        session = s
        container = FrameLayout(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            fitsSystemWindows = true
            addView(header(s), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        // The same WebView: detached from the background host and placed into this UI
        val view = s.tab.view
        (view.parent as? ViewGroup)?.removeView(view)
        container.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.requestFocus()
        ticker.post(recordPage)
        // The takeover ended elsewhere (notification button, timeout, cancel): close this UI; WebHandover puts the view back
        scope.launch {
            WebHandover.session.collect { now -> if (now !== s) leave() }
        }
        // Android 13+ delivers back through the predictive-back dispatcher instead of onBackPressed
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
    }

    private fun header(s: WebHandover.Session): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
            setBackgroundColor(Color.rgb(242, 242, 247))
        }
        bar.addView(TextView(this).apply {
            text = if (s.loginMode) tr(R.string.web_takeover_login) else tr(R.string.web_takeover_waiting, reasonLabel(s.reason))
            textSize = 15f
            setTextColor(Color.BLACK)
        })
        s.message?.takeIf { it.isNotBlank() }?.let { m ->
            bar.addView(TextView(this).apply {
                text = m
                textSize = 13f
                setTextColor(Color.DKGRAY)
            })
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        fun add(label: String, action: WebHandover.Action) = buttons.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { act(action) }
        })
        if (s.loginMode) {
            add(tr(R.string.done), WebHandover.Action.Done)
        } else {
            add(tr(R.string.action_hand_back), WebHandover.Action.HandBack)
            add(tr(R.string.action_finished_myself), WebHandover.Action.FinishedMyself)
            add(tr(R.string.action_cancel_task), WebHandover.Action.Cancel)
        }
        bar.addView(buttons)
        return bar
    }

    private fun act(action: WebHandover.Action) {
        val s = session ?: return
        record()
        s.onAction(action)
    }

    // Every touch (down) and key press the user makes on the page counts once; taps on the top bar do not
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val s = session
        if (s != null && ev.actionMasked == MotionEvent.ACTION_DOWN && ::container.isInitialized) {
            container.getGlobalVisibleRect(pageRect)
            if (pageRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) s.inputs++
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val s = session
        if (s != null && event.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_BACK && event.repeatCount == 0) s.inputs++
        return super.dispatchKeyEvent(event)
    }

    /** System back key: task takeover = leave it for now; settings-page login = close. */
    private fun back() {
        val s = session
        if (s == null) {
            finish()
            return
        }
        record()
        if (s.loginMode) s.onAction(WebHandover.Action.Cancel) else s.onAction(WebHandover.Action.PutAside)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Only reached before API 33; newer systems route back through the callback registered in onCreate.
        back()
    }

    private fun record() {
        val s = session ?: return
        s.url = s.tab.view.url ?: s.url
        s.title = s.tab.view.title?.takeIf { it.isNotBlank() } ?: s.title
    }

    private fun leave() {
        if (isFinishing) return
        record()
        finish()
    }

    override fun onDestroy() {
        ticker.removeCallbacks(recordPage)
        scope.cancel()
        // The view must not be destroyed along with this UI: detach it first, and WebHandover puts it back into the background host
        if (::container.isInitialized) container.removeAllViews()
        // The UI was swiped away while the takeover has not ended: task takeover is treated as "leave it for now", settings-page login ends outright
        session?.let { s ->
            if (WebHandover.session.value === s && isFinishing && !isChangingConfigurations) {
                if (s.loginMode) WebHandover.end() else s.onAction(WebHandover.Action.PutAside)
            }
        }
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val RECORD_INTERVAL_MS = 1_000L

        fun intent(context: Context): Intent =
            Intent(context, WebTakeoverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        private fun reasonLabel(reason: String?) = when (reason) {
            "login" -> tr(R.string.takeover_reason_login)
            "captcha" -> tr(R.string.takeover_reason_captcha)
            "access_blocked" -> tr(R.string.web_reason_security)
            "payment" -> tr(R.string.takeover_reason_payment)
            "decision" -> tr(R.string.takeover_reason_decision)
            else -> tr(R.string.takeover_title)
        }
    }
}
