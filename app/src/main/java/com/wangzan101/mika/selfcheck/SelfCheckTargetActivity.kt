package com.wangzan101.mika.selfcheck

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Display
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.View
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.cap.phone.shell.SystemIntents
import java.lang.ref.WeakReference

/**
 * The page the compatibility self-check operates on Claire's virtual display: a counter button, a text field, a column of scrollable rows, a line of numbers that keeps ticking, and a bar that alternates colours
 * (so the frames keep changing, making it visible whether the virtual display freezes after a few seconds). The page states that this is Claire's own test page.
 *
 * It is exported in the manifest, but callers must hold INTERACT_ACROSS_USERS_FULL (shell and the system have it, ordinary apps cannot), and it has no intent-filter:
 * it is only opened by the built-in assistant, running as shell, by component name onto Claire's display (see [intent]).
 * If it ever opens on the main screen (display id 0), it closes itself at once so the user is not disturbed. The elements are plain Views with resource ids;
 * text is taken in the app language ([tr]), and the self-check recognises elements by that same text (elements with text carry no resource id when read).
 */
class SelfCheckTargetActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var taps = 0
    private var ticks = 0
    private lateinit var count: TextView
    private lateinit var tick: TextView
    private lateinit var strip: View

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private val ticker = object : Runnable {
        override fun run() {
            ticks++
            tick.text = tr(R.string.selfcheck_target_tick, ticks)
            strip.setBackgroundColor(if (ticks % 2 == 0) STRIP_DARK else STRIP_LIGHT)
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shown = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
        if ((shown?.displayId ?: Display.DEFAULT_DISPLAY) == Display.DEFAULT_DISPLAY) {
            finish()
            return
        }
        current = WeakReference(this)
        setContentView(content())
    }

    // When opened again, start from scratch: the counter resets to zero, the text field is cleared, and the list scrolls back to the top
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setContentView(content())
    }

    // Keep ticking while it is visible (a page on the virtual display does not necessarily get focus, so this is not tied to onResume)
    override fun onStart() {
        super.onStart()
        main.removeCallbacks(ticker)
        if (::tick.isInitialized) main.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        main.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    private fun content(): View {
        taps = 0
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.WHITE)
        }
        fun text(id: Int, s: String, size: Float) = TextView(this).apply {
            this.id = id
            text = s
            textSize = size
            setTextColor(Color.BLACK)
            setPadding(0, pad / 4, 0, pad / 4)
        }
        column.addView(text(R.id.selfcheck_title, tr(R.string.selfcheck_target_title), 20f))
        column.addView(text(R.id.selfcheck_note, tr(R.string.selfcheck_target_note), 14f))
        count = text(R.id.selfcheck_count, tr(R.string.selfcheck_target_taps, 0), 16f)
        column.addView(count)
        column.addView(Button(this).apply {
            id = R.id.selfcheck_button
            text = tr(R.string.selfcheck_target_button)
            // By default the theme renders the button all in capitals, which also changes what screen reading reports: display it as written
            isAllCaps = false
            setOnClickListener {
                taps++
                count.text = tr(R.string.selfcheck_target_taps, taps)
            }
        })
        column.addView(EditText(this).apply {
            id = R.id.selfcheck_input
            hint = tr(R.string.selfcheck_target_input)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        tick = text(R.id.selfcheck_tick, tr(R.string.selfcheck_target_tick, ticks), 14f)
        column.addView(tick)
        strip = View(this).apply { setBackgroundColor(STRIP_DARK) }
        column.addView(strip, LinearLayout.LayoutParams(MATCH_PARENT, pad * 4))
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (i in 1..ROWS) rows.addView(text(View.NO_ID, tr(R.string.selfcheck_target_row, i), 16f).apply { setPadding(0, pad, 0, pad) })
        column.addView(ScrollView(this).apply {
            id = R.id.selfcheck_list
            addView(rows)
        }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return column
    }

    companion object {
        private const val TICK_MS = 250L
        private const val ROWS = 60
        private val STRIP_DARK = Color.rgb(0x33, 0x33, 0x33)
        private val STRIP_LIGHT = Color.rgb(0xEE, 0xEE, 0xEE)

        @Volatile private var current: WeakReference<SelfCheckTargetActivity>? = null

        /** The intent used to open this page: it names the component and runs as a task of its own that does not appear in recents. */
        fun intent(context: Context): Intent = Intent()
            .setClassName(context.packageName, SystemIntents.OWN_PAGE_CLASS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** The self-check finished or was cancelled: close it if it is still open. Called on the main thread. */
        fun finishOpen() {
            current?.get()?.finish()
            current = null
        }
    }
}
