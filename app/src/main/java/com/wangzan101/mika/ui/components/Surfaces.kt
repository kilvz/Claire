package com.wangzan101.mika.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.theme.MikaColors
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.LocalSkinBackdrop
import com.wangzan101.mika.ui.theme.SkinBackdrop
import com.wangzan101.mika.ui.theme.rememberSkinBackdrop
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy

/**
 * Page background: clean near-white (near-black in dark mode), with no gradient.
 * When a background image is set, the image is laid out full-bleed and then covered with a same-color scrim (see [SkinBackdrop]); cards and text are unchanged.
 */
@Composable
fun PageBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val backdrop = rememberSkinBackdrop()
    // With or without an image the structure is the same: the image is read asynchronously, and switching structures would rebuild the whole page inside and lose its state
    Box(
        modifier.fillMaxSize().background(MikaTheme.colors.bg).then(
            if (backdrop == null) Modifier else Modifier
                .onGloballyPositioned { backdrop.pageOrigin = it.positionInRoot(); backdrop.pageSize = it.size }
                .drawBehind { backdrop.draw(this, backdrop.pageOrigin) },
        ),
    ) {
        CompositionLocalProvider(LocalSkinBackdrop provides backdrop) { content() }
    }
}

/**
 * Page background color: used by the top bar, the fade under the input bar, and anywhere else that must hide scrolling content.
 * When a background image is set and [color] is the page background color, a page background is drawn at the same position so it joins seamlessly with the page background;
 * [fade] is a gradient from transparent to the page background color, and acts as an opacity mask when a background image is set.
 */
fun Modifier.pageBackground(color: Color, fade: Brush? = null): Modifier = composed {
    val backdrop = LocalSkinBackdrop.current
    if (backdrop == null || color != MikaTheme.colors.bg) return@composed if (fade != null) background(fade) else background(color)
    var origin by remember { mutableStateOf(Offset.Zero) }
    val placed = onGloballyPositioned { origin = it.positionInRoot() }
    if (fade == null) {
        placed.drawBehind { backdrop.draw(this, origin) }
    } else {
        // Draw the background first, then clip it by the gradient's alpha: transparent at the top, solid at the bottom
        placed.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawBehind {
                backdrop.draw(this, origin)
                drawRect(fade, blendMode = BlendMode.DstIn)
            }
    }
}

/** Card background: white base + 5% black border + a very faint shadow; in dark mode only the border. */
fun Modifier.cardSurface(colors: MikaColors, shape: Shape = MikaShapes.Card): Modifier =
    (if (colors.cardShadow.alpha > 0f) {
        shadow(6.dp, shape, clip = false, ambientColor = colors.cardShadow.copy(alpha = 0.45f), spotColor = colors.cardShadow.copy(alpha = 0.16f))
    } else this)
        .background(colors.surface, shape)
        .border(0.5.dp, colors.edge, shape)

/** Shadow for floating elements (the input bar, buttons floating above content). */
fun Modifier.floatShadow(colors: MikaColors, shape: Shape): Modifier =
    shadow(14.dp, shape, clip = false, ambientColor = colors.floatShadow.copy(alpha = 0.3f), spotColor = colors.floatShadow.copy(alpha = 0.35f))

/** A card whose content is clipped inside the rounded corners (so a row's press effect doesn't spill out). */
@Composable
fun MikaCard(modifier: Modifier = Modifier, shape: Shape = MikaShapes.Card, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().cardSurface(MikaTheme.colors, shape).clip(shape), content = content)
}

/**
 * Group card: a group of [ListRow]. It carries its own 12dp bottom spacing, which together with the next group's [GroupLabel] (8 on top) makes 20 between groups.
 * The dividers between rows are drawn by the rows themselves, starting at the text.
 */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    MikaCard(modifier.padding(bottom = 12.dp), content = content)
}

/** Status card: round icon on the left, title and description on the right. Used for the overall status at the top of a page. */
@Composable
fun StateCard(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tone: TileTone = TileTone.Neutral,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = MikaTheme.colors
    MikaCard(modifier.padding(bottom = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val (bg, fg) = tone.colors(c)
            Box(Modifier.size(36.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = fg)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MikaType.BodyStrong, color = c.ink)
                if (!subtitle.isNullOrEmpty()) Text(subtitle, style = MikaType.Caption, color = c.ink3)
            }
            trailing()
        }
    }
}

// ---------- Floating glass ----------

/** Background blur is available (RenderEffect since Android 12); when it isn't, glass falls back to the opaque card background. */
val GlassBlurSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** The image of the content layer behind the glass: content is drawn into it via [glassSource], and [GlassBar] takes it, blurs it and lays it underneath itself. */
@Stable
class GlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
}

/** null when blur is unsupported, in which case [GlassBar] uses an opaque background. */
@Composable
fun rememberGlassBackdrop(): GlassBackdrop? {
    if (!GlassBlurSupported) return null
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/** Records this content as the glass backdrop. The glass itself must not be inside this content. */
fun Modifier.glassSource(backdrop: GlassBackdrop?): Modifier =
    if (backdrop == null) this
    else onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            backdrop.layer.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop.layer)
        }

private val GlassBlur = BlurEffect(30f, 30f, TileMode.Clamp)

/**
 * The shell of the glass input bar: translucent base + background blur + floating shadow, at least 52 high.
 * When [backdrop] is null (blur unsupported, or nothing behind it), an opaque base is used.
 */
@Composable
fun GlassBar(
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop? = null,
    shape: Shape = MikaShapes.Composer,
    content: @Composable RowScope.() -> Unit,
) {
    val c = MikaTheme.colors
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier.floatShadow(c, shape).clip(shape)
            .then(if (backdrop != null) Modifier.onGloballyPositioned { origin = it.positionInRoot() } else Modifier),
    ) {
        if (backdrop != null) {
            Box(
                Modifier.matchParentSize()
                    .graphicsLayer { renderEffect = GlassBlur; clip = true }
                    .drawBehind {
                        val d = backdrop.origin - origin
                        translate(d.x, d.y) { drawLayer(backdrop.layer) }
                    },
            )
        }
        Row(
            Modifier.fillMaxWidth().background(if (backdrop != null) c.glass else c.surface)
                .border(0.5.dp, c.glassEdge, shape)
                .heightIn(min = MikaSize.Composer).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

/**
 * A small piece of floating frosted glass: 72% white + background blur + hairline border + floating shadow, sized to its content (used by the top bar's menu/title/new button).
 * When [backdrop] is null (blur unsupported, or nothing behind it), an opaque base is used.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop? = null,
    shape: Shape = MikaShapes.Pill,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = MikaTheme.colors
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier.floatShadow(c, shape).clip(shape)
            .then(if (backdrop != null) Modifier.onGloballyPositioned { origin = it.positionInRoot() } else Modifier),
    ) {
        if (backdrop != null) {
            Box(
                Modifier.matchParentSize().graphicsLayer { renderEffect = GlassBlur; clip = true }
                    .drawBehind {
                        val d = backdrop.origin - origin
                        translate(d.x, d.y) { drawLayer(backdrop.layer) }
                    },
            )
        }
        Box(
            Modifier.background(if (backdrop != null) c.glass else c.surface)
                .border(0.5.dp, c.glassEdge, shape),
            content = content,
        )
    }
}
