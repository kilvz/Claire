package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural

/**
 * Places in the model settings key pool copy that pick wording by Key count (pure functions, easy to test).
 * Static copy used only once is used directly in the UI via stringResource.
 */
object KeyPoolCopy {
    /** "N keys"; other count-bearing phrases reference it, and each language follows its own plural rules. */
    fun keys(count: Int): String = trPlural(R.plurals.model_keys_count, count)

    /** Title of the key section in provider details: one for no Key, one for a single key and one for multiple. */
    fun sectionTitle(count: Int): String = when (count) {
        0 -> tr(R.string.model_keys_title)
        1 -> tr(R.string.model_keys_pool_one)
        else -> tr(R.string.model_keys_pool_many, keys(count))
    }

    /** Footnote under the key section: with one Key, encourage adding more; with several, explain the rotation rule; none when no Key is configured. */
    fun footerNote(count: Int): String? = when {
        count > 1 -> tr(R.string.model_keys_rotate_note)
        count == 1 -> tr(R.string.model_keys_add_more_note)
        else -> null
    }

    /** Feedback in the add Key dialog when multiple Keys are pasted. */
    fun detectedAdd(count: Int): String = tr(R.string.model_keys_detected_add, keys(count))

    /** Feedback in the change Key dialog when multiple Keys are pasted. */
    fun detectedChange(count: Int): String = trPlural(R.plurals.model_keys_detected_change, count)

    /** Feedback at the Key field of the setup wizard when multiple Keys are pasted. */
    fun detectedSetup(count: Int): String = tr(R.string.model_keys_detected_setup, keys(count))

    /** Confirm button of the add Key dialog: state the count for multiple keys; for one it is just "Save". */
    fun addConfirm(count: Int, save: String): String =
        if (count > 1) trPlural(R.plurals.model_key_add_count, count) else save

    /** Subtitle of a row in the provider list: when the pool has multiple Keys, append the key pool note after [line]; with at most one it is just [line]. */
    fun providerLine(line: String, count: Int): String =
        if (count > 1) tr(R.string.model_provider_line_pool, line, keys(count)) else line

    /**
     * Subtitle of the provider entry: no providers connected, multiple Keys in the pool, or just the provider count.
     * The provider count follows the current language's plural rules, so a single provider with multiple Keys in the pool is no longer written as "1 providers".
     */
    fun providersSubtitle(providers: Int, keysInPool: Int): String {
        if (providers == 0) return tr(R.string.model_providers_none)
        val count = trPlural(R.plurals.models_providers_count, providers)
        return if (keysInPool > 1) tr(R.string.model_providers_keys_in_pool, count, keys(keysInPool)) else count
    }
}
