package com.wangzan101.mika.chat.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.graphics.drawable.toBitmap
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.uiLocale
import kotlin.random.Random
import com.wangzan101.mika.settings.RecommendedTask
import com.wangzan101.mika.ui.theme.MikaIcons

/**
 * An example in the empty chat: tapping fills the prompt from [task] into the composer; [app] is the icon of the matching app on the phone,
 * when absent, use [site] (the website's own mark), and if that is absent too, [fallback].
 */
internal data class Idea(val task: RecommendedTask, val app: ImageBitmap?, val fallback: ImageVector, val site: Int? = null, val blurb: String? = null)

/** Built-in inspiration examples: text + which kind of system action finds an app that can handle it. Ones with no matching app are hidden. */
private class Seed(val id: String, val text: Int, val intent: () -> Intent, val fallback: ImageVector)

private val seeds = listOf(
    Seed("maps", R.string.chat_idea_maps, { Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=coffee")) }, MikaIcons.Globe),
    Seed("camera", R.string.chat_idea_camera, { Intent(MediaStore.ACTION_IMAGE_CAPTURE) }, MikaIcons.Camera),
    Seed("sms", R.string.chat_idea_sms, { Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")) }, MikaIcons.Message),
    Seed("news", R.string.chat_idea_news, { Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")) }, MikaIcons.Search),
    Seed("dark", R.string.chat_idea_dark, { Intent(Settings.ACTION_SETTINGS) }, MikaIcons.Moon),
    Seed("music", R.string.chat_idea_music, { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC) }, MikaIcons.Spark),
    Seed("email", R.string.chat_idea_email, { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL) }, MikaIcons.Message),
    Seed("screenshot", R.string.chat_idea_screenshot, { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY) }, MikaIcons.Photo),
    Seed("meeting", R.string.chat_idea_meeting, { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR) }, MikaIcons.Calendar),
)

/** When fewer than this many library examples have their app installed, top up with system-action examples so the scrolling rows are not too empty. */
private const val MIN_LIBRARY = 8

/** Empty-chat content: several promo examples in a carousel above and two scrolling example rows below. */
internal data class EmptyIdeas(val featured: List<Idea>, val rows: List<List<Idea>>)

/**
 * The promo cards above are the hardcoded ones from [ChatCopy.promos], in the current UI language; the two scrolling rows below come from the [IdeaLibrary] library,
 * listing only apps installed on the phone. When too few are installed, top up with a few system actions (maps, camera, calendar, …). Reads PackageManager, so call it on a background thread.
 */
internal fun loadEmptyIdeas(context: Context, random: Random): EmptyIdeas {
    val pm = context.packageManager
    fun iconOf(pkg: String): ImageBitmap? = icons.getOrPut(pkg) {
        runCatching { pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull() ?: return null
    }
    fun handler(intent: Intent): String? = runCatching {
        pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.firstOrNull { it != "android" && it != context.packageName }
    }.getOrNull()

    val featured = ChatCopy.promos.mapIndexed { i, p ->
        val (pkg, site) = when (p.kind) {
            ChatCopy.ExampleKind.Web -> null to R.drawable.ic_site_github
            // Chinese UI uses Xiaohongshu, other languages use X; when the app is not installed, X uses its own mark
            ChatCopy.ExampleKind.Follow -> if (uiLocale().language == "zh") "com.xingin.xhs" to null else "com.twitter.android" to R.drawable.ic_site_x
            ChatCopy.ExampleKind.GroupChat -> "com.tencent.mm" to null
            ChatCopy.ExampleKind.Share -> context.packageName to null
            else -> null to null
        }
        Idea(RecommendedTask("promo-$i", p.title, p.prompt), pkg?.let(::iconOf), fallbackFor(p.kind), site, p.blurb)
    }
    val library = IdeaLibrary.load(context, uiLocale()).mapIndexedNotNull { i, e ->
        val icon = e.packages.firstNotNullOfOrNull(::iconOf) ?: return@mapIndexedNotNull null
        e.scene to Idea(RecommendedTask("idea-lib-$i", e.text, e.text), icon, MikaIcons.Spark)
    }
    val filler = if (library.size >= MIN_LIBRARY) emptyList() else seeds.mapNotNull { seed ->
        val pkg = handler(seed.intent()) ?: return@mapNotNull null
        val text = tr(seed.text)
        "system-${seed.id}" to Idea(RecommendedTask("idea-${seed.id}", text, text), iconOf(pkg), seed.fallback)
    }
    val rows = IdeaLibrary.arrange(library + filler, { it.first }, random).map { row -> row.map { it.second } }
    return EmptyIdeas(featured, rows)
}

/** App icons already drawn, keyed by package name; an app changing its icon only updates after a process restart, which is good enough. */
private val icons = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

private fun fallbackFor(kind: ChatCopy.ExampleKind): ImageVector = when (kind) {
    ChatCopy.ExampleKind.Web -> MikaIcons.Globe
    ChatCopy.ExampleKind.Follow -> MikaIcons.Person
    ChatCopy.ExampleKind.GroupChat -> MikaIcons.Message
    ChatCopy.ExampleKind.Share -> MikaIcons.Share
    ChatCopy.ExampleKind.Alarm -> MikaIcons.Alarm
    ChatCopy.ExampleKind.Schedule -> MikaIcons.Calendar
}
