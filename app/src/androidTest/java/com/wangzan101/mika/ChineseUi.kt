package com.wangzan101.mika

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.i18n.AppLanguage
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Pins Claire's UI to Simplified Chinese for on-device tests (the assertions are written in Simplified Chinese): from Android 13 on, it temporarily switches to Simplified Chinese through the system's "app languages" and switches back after the test;
 * it must be placed outermost (`@get:Rule(order = Int.MIN_VALUE)`) so the switch happens before the UI comes up.
 * Android 12 and earlier have no per-app language, so the test UI follows the system: run those on a Simplified Chinese system.
 */
class ChineseUi : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return base.evaluate()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val simplified = { context.resources.getString(R.string.ui_locale) == AppLanguage.SimplifiedChinese.tag }
            if (simplified()) return base.evaluate()
            val lm = context.getSystemService(LocaleManager::class.java)
            val before = lm.applicationLocales
            lm.applicationLocales = LocaleList.forLanguageTags(AppLanguage.SimplifiedChinese.tag)
            waitFor(simplified)
            try {
                base.evaluate()
            } finally {
                lm.applicationLocales = before
            }
        }
    }

    private fun waitFor(ok: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 5_000
        while (!ok() && SystemClock.uptimeMillis() < until) SystemClock.sleep(50)
    }
}
