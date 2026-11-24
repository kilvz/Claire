package com.wangzan101.mika.i18n

import android.content.res.Resources
import com.wangzan101.mika.R
import java.util.Locale
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Where UI text comes from. In the app it is Android resources read in the app language ([ResourceTexts]); in JVM unit tests it is replaced by an implementation that reads the resource files directly.
 * Where stringResource can be used in Compose, keep using it as usual; this is for code that computes text outside the UI (status copy, notifications, services, floating capsule data).
 */
interface UiTexts {
    fun string(@StringRes id: Int, args: Array<out Any?>): String
    fun plural(@PluralsRes id: Int, count: Int, args: Array<out Any?>): String
}

/** The [UiTexts] currently used in the process: installed at app startup. */
object UiText {
    @Volatile
    var source: UiTexts? = null

    internal fun require(): UiTexts = source ?: error("UiText.source is not installed")
}

/** Fetches one piece of UI text; when [args] is given it is formatted in (the same as Resources.getString). */
fun tr(@StringRes id: Int, vararg args: Any?): String = UiText.require().string(id, args)

/** Fetches a quantity-dependent piece of UI text; when [args] is not given, [count] is passed in as the only argument. */
fun trPlural(@PluralsRes id: Int, count: Int, vararg args: Any?): String = UiText.require().plural(id, count, args)

/** Android resources implementation; [resources] is fetched on every call, so it follows a language change. */
class ResourceTexts(private val resources: () -> Resources) : UiTexts {
    override fun string(id: Int, args: Array<out Any?>): String =
        if (args.isEmpty()) resources().getString(id) else resources().getString(id, *args)

    override fun plural(id: Int, count: Int, args: Array<out Any?>): String =
        resources().getQuantityString(id, count, *(if (args.isEmpty()) arrayOf(count) else args))
}

/** The Locale corresponding to the UI language: weekday and month names are written in it. */
fun uiLocale(): Locale = Locale.forLanguageTag(tr(R.string.ui_locale))
