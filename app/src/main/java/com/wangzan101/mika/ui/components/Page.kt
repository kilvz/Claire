package com.wangzan101.mika.ui.components

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaSpace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Subpage skeleton: page background + top bar with back + one scrollable column (with [MikaSpace.Page] on both sides, clear of the navigation bar).
 * Put [GroupLabel] / [GroupCard] / [FooterNote] directly inside the content.
 * When [scrollState] is not given, use the one remembered in this page's own composition, which is lost when the page leaves it; for pages shown in a page switcher that must hold their place when returning,
 * the switcher supplies it via [PageScrolls].
 */
@Composable
fun PageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState? = null,
    viewportModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = scrollState ?: rememberScrollState()
    // When re-entering composition with a position (returning through the page switcher), content that appears one frame later in the first layout (card footnotes in compact lists, see rememberCardNote) has not taken up space yet,
    // so the maximum scroll offset is too small and the carried position gets clipped: wait for the offset to grow back, and if nobody moved it in the meantime, restore the original position.
    // Read the supplied position during composition: a LaunchedEffect coroutine runs only after dispatch, by which time the first layout may already have clipped it
    val wanted = remember(scroll) { scroll.value }
    val placed = remember(scroll) { CompletableDeferred<Unit>() }
    if (scrollState != null) LaunchedEffect(scroll) {
        placed.await()
        val clippedTo = scroll.value
        if (wanted > clippedTo && clippedTo == scroll.maxValue) {
            withTimeoutOrNull(CLIPPED_RESTORE_MS) { snapshotFlow { scroll.maxValue }.first { it >= wanted } }
            if (scroll.value == clippedTo && scroll.maxValue >= wanted) scroll.scrollTo(wanted)
        }
    }
    PageBackground(modifier) {
        Column(Modifier.fillMaxSize()) {
            TopBar(title, MikaIcons.Back, onBack, stringResource(R.string.back))
            Column(
                Modifier.weight(1f).fillMaxWidth().then(viewportModifier)
                    .onPlaced { placed.complete(Unit) }
                    .verticalScroll(scroll)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = MikaSpace.Page),
            ) {
                Spacer(Modifier.height(MikaSpace.S4))
                content()
                Spacer(Modifier.height(MikaSpace.S24))
            }
        }
    }
}

/** After the carried position is clipped by the first frame, wait at most this long for later content to appear; beyond that, treat the content as genuinely shorter. */
private const val CLIPPED_RESTORE_MS = 500L

/**
 * Scroll positions remembered per page, for [PageScaffold] pages shown in a page switcher: switching to another page removes the current page from composition,
 * losing the position its own [rememberScrollState] remembered, so returning goes back to the top. The positions live with the switcher
 * (which stays in composition while paging), and each page gets its own via [of] to pass to [PageScaffold]'s `scrollState`;
 * page recreation (changing UI language, restoring after the process is reclaimed) brings them back through [Saver]. Saved positions are keyed by enum name, so pages in one switcher must share one enum.
 */
class PageScrolls(saved: Map<String, Any?> = emptyMap()) {
    /** Positions saved by each page before recreation, applied when that page is first requested. */
    private val pending = saved.mapValues { (_, position) -> (position as? Number)?.toInt() ?: 0 }.toMutableMap()
    private val states = HashMap<Enum<*>, ScrollState>()

    /** The scroll state for [page], created on first request. */
    fun of(page: Enum<*>): ScrollState = states.getOrPut(page) {
        ScrollState(0).also { it.dispatchRawDelta((pending.remove(page.name) ?: 0).toFloat()) }
    }

    companion object {
        val Saver: Saver<PageScrolls, Any> = mapSaver(
            save = { scrolls -> scrolls.states.mapKeys { (page, _) -> page.name }.mapValues { (_, state) -> state.value } },
            restore = { PageScrolls(it) },
        )
    }
}
