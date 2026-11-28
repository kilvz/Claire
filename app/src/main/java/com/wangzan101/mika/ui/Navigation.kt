package com.wangzan101.mika.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import com.wangzan101.mika.ui.theme.MikaTheme

/** Pages in the app. The conversation is the root page; all others are entered from it (or from settings) and return to the page they came from. */
enum class Route { Chat, Personal, Settings, Memory, Plans, Browser, Files, Run, Source, PhoneSetup, Models, Usage, SpeechModel, VoiceModel, ImageModel, Welcome, ModelSetup, About, License, UpdateMirrors, Screens, Teach, Calls, Report, Playback, Persona, VoiceCatalog, RealtimeVoice, VoiceServices, CallMode, VoiceCall, VideoModel, VoicePacks }

/**
 * Page stack: the bottom is always the Chat page. [push] goes to a page, [back] returns to the previous page (returns false when already on the Chat page, leaving exit to the system).
 * The back key and the on-page back button both go through [back], so the two are always consistent.
 */
@Stable
class NavStack(initial: List<Route> = listOf(Route.Chat), fromDrawer: Boolean = false) {
    var stack: List<Route> by mutableStateOf(normalize(initial))
        private set

    /** This chain of pages was entered from the drawer: when backing out to the Chat page, return to the drawer that was open. */
    private var fromDrawer = fromDrawer && stack.size > 1

    /** A page just entered from the drawer has been popped back to the Chat page and the drawer should reopen; call [drawerReopened] after opening it. */
    var reopenDrawer: Boolean by mutableStateOf(false)
        private set

    /** Incremented whenever an external jump or a system share returns to the Chat page, so an already-expanded drawer collapses as well. */
    var resetSerial: Int by mutableStateOf(0)
        private set

    val current: Route get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    /** Go to a page; don't push again when already on it. */
    fun push(route: Route) {
        if (route == Route.Chat) return reset()
        if (current != route) stack = stack + route
    }

    /** Enter a page from the drawer: when backing out to the Chat page afterwards, the drawer is open. */
    fun pushFromDrawer(route: Route) {
        fromDrawer = true
        push(route)
    }

    fun back(): Boolean {
        if (!canGoBack) return false
        stack = stack.dropLast(1)
        if (!canGoBack && fromDrawer) {
            fromDrawer = false
            reopenDrawer = true
        }
        return true
    }

    /** Bring [route] to the top: if it is already in the stack, pop back to it (closing the pages above); otherwise push it. */
    fun bringToTop(route: Route) {
        if (route == Route.Chat) return reset()
        val at = stack.indexOf(route)
        stack = if (at < 0) stack + route else stack.take(at + 1)
    }

    /** [route] leaves the page stack: if it is on top this pops one page, and if it is below the top it is pulled out; it does nothing when not in the stack. */
    fun leave(route: Route) {
        if (route == Route.Chat || route !in stack) return
        if (current == route) back() else stack = stack - route
    }

    fun drawerReopened() {
        reopenDrawer = false
    }

    /** Keep only the Chat page and [route] (used for each step of first-run onboarding). */
    fun show(route: Route) {
        fromDrawer = false
        stack = normalize(listOf(route))
    }

    /** Return to the Chat page (when a conversation is opened). */
    fun reset() {
        fromDrawer = false
        reopenDrawer = false
        stack = listOf(Route.Chat)
        resetSerial++
    }

    companion object {
        private const val DRAWER_MARK = "fromDrawer"

        private fun normalize(list: List<Route>): List<Route> =
            listOf(Route.Chat) + list.filter { it != Route.Chat }

        val Saver: Saver<NavStack, ArrayList<String>> = Saver(
            save = { ArrayList(it.stack.map(Route::name) + listOfNotNull(DRAWER_MARK.takeIf { _ -> it.fromDrawer })) },
            restore = { names ->
                NavStack(names.mapNotNull { n -> Route.entries.firstOrNull { it.name == n } }, fromDrawer = DRAWER_MARK in names)
            },
        )
    }
}

@Composable
fun rememberNavStack(first: Route = Route.Chat): NavStack =
    rememberSaveable(saver = NavStack.Saver) { NavStack(listOf(first)) }

/** Animation parameters for page switches: duration, parallax ratio (the old page moves a quarter), and easing curve. */
private object NavAnim {
    const val MS = 280
    const val PARALLAX = 4
    val EASE = FastOutSlowInEasing
}

/**
 * UI state of each page across page switches (scroll position, input field, expanded sub-pages…). The rules follow the back stack:
 * a page still on the stack (shallower than the current page) is kept and restored as it was when going back; once it leaves the stack (going back to a shallower page, or replacing it with another page at the same level) it is dropped,
 * and entering again from the entry point next time is a new page. Pages with [keep] true are always kept (root pages such as the conversation page and the personal assistant, switched back and forth).
 *
 * The caller remembers it outside the page-switch animation: when the page framework (persistent sidebar/drawer) changes shape, [NavSlide] recomposes and the state must not be lost with it.
 */
@Stable
class PageStates internal constructor(
    internal val holder: SaveableStateHolder,
    private val depths: HashMap<String, Int>,
    private val keep: (String) -> Boolean,
) {
    /** Enters page [key] (level [depth]): other pages at the same level and deeper have left the stack, so discard their stored state. */
    internal fun enter(key: String, depth: Int) {
        for (gone in stalePages(depths, key, depth, keep)) {
            depths -= gone
            holder.removeState(gone)
        }
        depths[key] = depth
    }

    internal companion object {
        /** Pages that should be discarded when entering [key]/[depth]: level not shallower than it, not itself, and not a kept page. */
        fun stalePages(known: Map<String, Int>, key: String, depth: Int, keep: (String) -> Boolean): List<String> =
            known.filter { (k, d) -> k != key && d >= depth && !keep(k) }.keys.toList()

        val DepthsSaver: Saver<HashMap<String, Int>, ArrayList<String>> = Saver(
            save = { m -> ArrayList(m.map { (k, d) -> "$d:$k" }) },
            restore = { list -> HashMap(list.associate { it.substringAfter(':') to it.substringBefore(':').toInt() }) },
        )
    }
}

@Composable
fun rememberPageStates(keep: (String) -> Boolean = { false }): PageStates {
    val holder = rememberSaveableStateHolder()
    val depths = rememberSaveable(saver = PageStates.DepthsSaver) { HashMap() }
    return remember(holder, depths) { PageStates(holder, depths, keep) }
}

/**
 * Slide push transition for page switches: when [depth] increases the new page slides in from the right and the old page shifts slightly left (parallax), and the reverse when it decreases.
 * Same-level replacement is treated as a push; when system animations are off ([MikaTheme.reduceMotion]) it replaces directly.
 * Content must use the [targetState] value (the old page is still there during the animation); do not read the navigation's live state.
 *
 * When [states] is given, each page's UI state is stored under [pageKey] (rules in [PageStates]); the same [pageKey] is treated as the same page:
 * a mere page data change does not replay the page-switch animation, and two pages with the same [pageKey] are never in the composition tree at once.
 */
@Composable
fun <T> NavSlide(
    targetState: T,
    depth: (T) -> Int,
    modifier: Modifier = Modifier,
    states: PageStates? = null,
    pageKey: (T) -> String = { it.toString() },
    content: @Composable AnimatedContentScope.(T) -> Unit,
) {
    val reduce = MikaTheme.reduceMotion
    if (states != null) {
        val key = pageKey(targetState)
        val level = depth(targetState)
        SideEffect { states.enter(key, level) }
    }
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            if (reduce) {
                fadeIn(snap()) togetherWith fadeOut(snap())
            } else {
                val slide = tween<IntOffset>(NavAnim.MS, easing = NavAnim.EASE)
                if (depth(targetState) >= depth(initialState)) {
                    slideInHorizontally(slide) { it } togetherWith slideOutHorizontally(slide) { -it / NavAnim.PARALLAX }
                } else {
                    slideInHorizontally(slide) { -it / NavAnim.PARALLAX } togetherWith slideOutHorizontally(slide) { it }
                }
            }
        },
        label = "nav-slide",
        contentKey = { if (states != null) pageKey(it) else it },
    ) { page ->
        if (states != null) states.holder.SaveableStateProvider(pageKey(page)) { content(page) }
        else content(page)
    }
}
