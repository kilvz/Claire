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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import com.wangzan101.mika.ui.theme.MikaTheme

/** Pages in the app. Chat is the root page; everything else is entered from it (or from Settings), and leaving returns to the page you came in from. */
enum class Route { Chat, Personal, Settings, Memory, Plans, Browser, Files, Run, Source, PhoneSetup, Models, Usage, SpeechModel, VoiceModel, ImageModel, Welcome, ModelSetup, About, License, UpdateMirrors, Screens, Teach, Calls, Report, Playback, Persona, VoiceCatalog, RealtimeVoice, VoiceServices, CallMode }

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
 * Slide-in push transition for page switches: when [depth] increases the new page slides in from the right and the old page shifts slightly left (parallax); when it decreases, the reverse.
 * Replacing at the same level is treated as a push; when system animations are off ([MikaTheme.reduceMotion]) it swaps directly.
 * Content must use the value of [targetState] (the old page is still present during the animation); don't read the live navigation state.
 */
@Composable
fun <T> NavSlide(
    targetState: T,
    depth: (T) -> Int,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedContentScope.(T) -> Unit,
) {
    val reduce = MikaTheme.reduceMotion
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
        content = content,
    )
}
