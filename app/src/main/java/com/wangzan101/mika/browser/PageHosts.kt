package com.wangzan101.mika.browser

/**
 * Where the browser page's web view is attached. There is only one web tab (kept in the process), but there can be several browser pages at once: when the app has more than one UI instance
 * (another one was opened from a notification, the launcher or another app and covers the previous one), each instance may be sitting on the browser page and each leaves a container for the web view.
 * A view can be attached to only one container at a time, and if it is attached to the wrong one (the covered instance) the user sees just a blank area.
 *
 * Rule: attach to the host among those with containers that most recently came to the foreground ([started]), and on a tie prefer the later one. When that host leaves, switch to the remaining one that most recently came to the foreground.
 * [close] tells the caller whether the host leaving is the last one: only when the last one leaves (and it is not a configuration change) is the web page closed; hosts leaving the browser page elsewhere do not close a page someone else is viewing.
 *
 * [C] is the container holding the view, [A] is the UI the host belongs to (the view's context is switched to it when attaching). Only call on the main thread.
 */
class PageHosts<C : Any, A : Any> {
    /** One browser page host. */
    class Host<C : Any, A : Any>(val activity: A) {
        /** The container this host currently leaves for the web view; null when no page is open. */
        var container: C? = null
            internal set
        internal var startedAt = 0L
    }

    private val hosts = mutableListOf<Host<C, A>>()
    private var clock = 0L

    /** This host entered the browser page. */
    fun open(host: Host<C, A>) {
        if (host !in hosts) hosts += host
    }

    /** This host left the browser page (or its UI is gone); returns true when no other browser page is left. */
    fun close(host: Host<C, A>): Boolean {
        hosts.remove(host)
        host.container = null
        return hosts.isEmpty()
    }

    /** This host came back to the foreground (is visible). */
    fun started(host: Host<C, A>) {
        host.startedAt = ++clock
    }

    /** This host's container appeared or disappeared. */
    fun setContainer(host: Host<C, A>, container: C?) {
        host.container = container
    }

    /** The host the view should attach to: among those still on the browser page that have a container, the one that most recently came to the foreground; null when there is none. */
    fun target(): Host<C, A>? =
        hosts.withIndex().filter { it.value.container != null }
            .maxWithOrNull(compareBy<IndexedValue<Host<C, A>>>({ it.value.startedAt }, { it.index }))?.value
}
