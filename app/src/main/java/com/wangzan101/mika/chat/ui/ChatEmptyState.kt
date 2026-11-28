package com.wangzan101.mika.chat.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.settings.RecommendedTask
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.components.MikaLogo
import com.wangzan101.mika.i18n.uiLocale
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalTime

/**
 * Empty chat: a greeting + one value proposition + a swipeable promo example card, with two slowly counter-scrolling example rows above the composer at the bottom.
 * Examples carry the icon of the matching app on the phone; tapping one fills the full prompt into the composer so it can be edited before sending.
 */
@Composable
internal fun EmptyState(
    conversation: String?,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
    onPick: (RecommendedTask) -> Unit,
    onSend: (RecommendedTask) -> Unit = onPick,
) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    val hour = remember(conversation) { LocalTime.now().hour }
    // Each time an empty chat opens, use a different arrangement; both loads in the same visit share one so the order does not jump
    val random = remember(conversation) { kotlin.random.Random(System.nanoTime()) }
    val seed = remember(conversation) { random.nextLong() }
    val ideas by produceState<EmptyIdeas?>(null, seed) {
        value = withContext(Dispatchers.IO) { loadEmptyIdeas(context, kotlin.random.Random(seed)) }
    }
    Column(modifier.fillMaxSize()) {
        // Put the greeting about one third from the top, with the promo card right below; leave the rest for the two scrolling example rows at the bottom
        Spacer(Modifier.weight(0.55f))
        Column(Modifier.fillMaxWidth().padding(horizontal = ComposerInset)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MikaLogo(26.dp, Modifier.border(0.5.dp, c.edge, RoundedCornerShape(8.dp)), corner = 8.dp)
                Text(remember(conversation) { todayLabel() }, style = MikaType.Caption, color = c.ink3)
            }
            Text(ChatCopy.greeting(hour), Modifier.padding(top = 14.dp), style = EmptyGreeting, color = c.ink)
            Text(ChatCopy.EMPTY_VALUE, Modifier.padding(top = 6.dp), style = EmptyLead, color = c.ink3)
        }
        ideas?.featured?.takeIf { it.isNotEmpty() }?.let { featured ->
            Spacer(Modifier.height(22.dp))
            FeaturedPager(featured, reduceMotion, onSend)
        }
        Spacer(Modifier.weight(1f))
        ideas?.rows?.forEachIndexed { i, row ->
            MarqueeRow(row, reverse = i % 2 == 1, animate = !reduceMotion, onPick = onPick, modifier = Modifier.padding(top = if (i == 0) 0.dp else 8.dp))
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** Promo example: one at a time, swipe left/right or auto-advance every few seconds, with small dots below. */
@Composable
private fun FeaturedPager(items: List<Idea>, reduceMotion: Boolean, onPick: (RecommendedTask) -> Unit) {
    val c = MikaTheme.colors
    val pager = rememberPagerState { items.size }
    if (!reduceMotion && items.size > 1) {
        LaunchedEffect(pager.settledPage, pager.isScrollInProgress) {
            if (pager.isScrollInProgress) return@LaunchedEffect
            delay(5_000)
            pager.animateScrollToPage((pager.currentPage + 1) % items.size)
        }
    }
    Column {
        HorizontalPager(
            pager,
            Modifier.fillMaxWidth().testTag("empty-featured"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = ComposerInset),
            pageSpacing = 8.dp,
        ) { page -> FeaturedCard(items[page], onPick) }
        if (items.size > 1) {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
            ) {
                repeat(items.size) { i ->
                    val on = i == pager.currentPage
                    Box(Modifier.height(5.dp).width(if (on) 14.dp else 5.dp).clip(CircleShape).background(if (on) c.ink2 else c.hair))
                }
            }
        }
    }
}

@Composable
private fun FeaturedCard(idea: Idea, onPick: (RecommendedTask) -> Unit) {
    val c = MikaTheme.colors
    // Light grey fill, no border, same material as the example pills at the bottom; the subtitle is a one-line description, and tapping sends the full prompt directly
    val blurb = idea.blurb ?: idea.task.prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    Row(
        Modifier.fillMaxWidth().clip(FeaturedShape).background(c.fill)
            .clickable(role = Role.Button) { onPick(idea.task) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppMark(idea, 40.dp, tile = c.surface)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(idea.task.title, style = MikaType.BodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (blurb.isNotEmpty()) {
                Text(blurb, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // Tapping the card sends directly, so the right side looks like send (same shape as the composer's send button, one size smaller), not a "go to next page" arrow
        Box(Modifier.padding(start = 8.dp).size(28.dp).clip(CircleShape).background(c.surface), contentAlignment = Alignment.Center) {
            Icon(MikaIcons.Up, null, Modifier.size(15.dp), tint = c.ink)
        }
    }
}

/** App icon; when the app is not on the phone, a line icon on a light grey background. */
@Composable
private fun AppMark(idea: Idea, size: androidx.compose.ui.unit.Dp, tile: Color = MikaTheme.colors.fill) {
    val c = MikaTheme.colors
    val shape = RoundedCornerShape(size * 0.26f)
    val app = idea.app
    if (app != null) {
        Image(app, null, Modifier.size(size).clip(shape))
    } else {
        val site = idea.site
        Box(Modifier.size(size).clip(shape).background(tile), contentAlignment = Alignment.Center) {
            // Website marks are monochrome and follow the text color, turning white automatically in dark mode
            if (site != null) Icon(painterResource(site), null, Modifier.size(size * 0.58f), tint = c.ink)
            else Icon(idea.fallback, null, Modifier.size(size * 0.5f), tint = c.ink2)
        }
    }
}

private val FeaturedShape = RoundedCornerShape(18.dp)

/** The date line above the greeting, written in the UI language's convention, e.g. "Tue, Nov 25" or "25. Nov., Di". */
private fun todayLabel(): String {
    val locale = uiLocale()
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMdEEE")
    return java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern(pattern, locale))
}

private const val MARQUEE_DP_PER_SECOND = 22f

/**
 * One slowly scrolling example row. The content is laid out twice end to end, and after scrolling one copy's width it returns to the start, so it looks endless;
 * both ends fade out. With animations off, it becomes a row that can be swiped by hand.
 */
@Composable
private fun MarqueeRow(items: List<Idea>, reverse: Boolean, animate: Boolean, onPick: (RecommendedTask) -> Unit, modifier: Modifier = Modifier) {
    val fade = Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val edge = 28.dp.toPx() / size.width
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, edge to Color.Black, 1f - edge to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }
    val pills: @Composable () -> Unit = { items.forEach { Pill(it, onPick) } }
    if (!animate) {
        Row(
            modifier.fillMaxWidth().then(fade).horizontalScroll(rememberScrollState()).padding(horizontal = ComposerInset),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) { pills() }
        return
    }
    val speed = with(LocalDensity.current) { MARQUEE_DP_PER_SECOND.dp.toPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var cycle by remember { mutableIntStateOf(0) }
    var shift by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(cycle) {
        if (cycle == 0) return@LaunchedEffect
        var last = 0L
        while (true) withFrameNanos { now ->
            if (last != 0L) shift = (shift + speed * (now - last) / 1e9f) % cycle
            last = now
        }
    }
    Box(modifier.fillMaxWidth().clipToBounds().then(fade)) {
        Row(
            Modifier.wrapContentWidth(Alignment.Start, unbounded = true).offset {
                // Moving left: 0 → -cycle; moving right: -cycle → 0. In right-to-left languages the direction is mirrored too
                val x = if (reverse) shift - cycle else -shift
                IntOffset((if (rtl) -x else x).toInt(), 0)
            },
        ) {
            repeat(3) { copy ->
                Row(
                    Modifier.padding(end = 8.dp).then(if (copy == 0) Modifier.onSizeChanged { cycle = it.width } else Modifier),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) { pills() }
            }
        }
    }
}

/** One item in the scrolling row: app icon + one line of text. */
@Composable
private fun Pill(idea: Idea, onPick: (RecommendedTask) -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.height(40.dp).clip(CircleShape).background(c.fill)
            .clickable(role = Role.Button) { onPick(idea.task) }
            .padding(start = 7.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppMark(idea, 26.dp)
        Spacer(Modifier.width(8.dp))
        Text(idea.task.title, style = MikaType.Hint, color = c.ink, maxLines = 1)
    }
}

// ---------- Question and answer ----------
