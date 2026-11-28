package com.wangzan101.mika.device

/**
 * Where the lock screen credential page is read from: first the main display's accessibility window list; when it has no SystemUI window (on some systems the whole list is empty while the lock screen is up, #307),
 * fall back in order to the legacy main-display window list and then the current active window's root. The fallback only changes "where to read from"; it still only accepts SystemUI content,
 * and credential key events are still injected only into SystemUI.
 */
internal object KeyguardRoots {
    enum class Source(val tag: String) { Display("display"), Legacy("legacy"), ActiveRoot("active_root"), None("none") }

    data class Pick<W>(val source: Source, val roots: List<W>)

    fun <W> pick(display: List<W>, legacy: () -> List<W>, activeRoot: () -> W?, systemUi: (W) -> Boolean): Pick<W> {
        display.filter(systemUi).takeIf { it.isNotEmpty() }?.let { return Pick(Source.Display, it) }
        legacy().filter(systemUi).takeIf { it.isNotEmpty() }?.let { return Pick(Source.Legacy, it) }
        activeRoot()?.takeIf(systemUi)?.let { return Pick(Source.ActiveRoot, listOf(it)) }
        return Pick(Source.None, emptyList())
    }
}
