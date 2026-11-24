package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.safety.guard.DIAGNOSTIC_VERBS
import com.wangzan101.mika.core.screen.observation.Place
import com.wangzan101.mika.core.screen.observation.PlaceKind
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.tool.Verb

/** The reference material to look up after a tool result. */
internal sealed interface KnowledgeLookup {
    /** Knowledge about the foreground App. */
    data class App(val pkg: String) : KnowledgeLookup
    /** Knowledge about a website; [host] is already lowercased with www. removed, and each site is looked up only on first entry. */
    data class Site(val host: String) : KnowledgeLookup
}

/**
 * What follows a tool result: which note goes with an attached image, and which reference material to look up for
 * where it landed. Pure functions.
 */
internal object NoticePolicy {
    /** Viewing a UI in a web page, reading a page, opening a web page: arrived at that site. */
    private val SITE_RESULT_VERBS = setOf(Verb.Observe, Verb.ListElements, Verb.Navigate, Verb.ReadPage)

    /** The note attached to the image on a result; [offScreen] true means an image file read by a tool that does not touch the screen, not a screen frame. */
    fun imageNote(verb: Verb?, offScreen: Boolean): String = when {
        // Images re-sent when looking back are handled here; for cropping purposes they are new images
        verb == Verb.ViewPastScreens -> Feedback["recalled_note"]
        offScreen -> Feedback["file_image_note"]
        verb in DIAGNOSTIC_VERBS -> Feedback["screenshot_note"]
        // The frame automatically attached after a successful action; only it is current, and earlier frames are replaced with a look-back placeholder
        else -> Feedback["action_frame_note"]
    }

    /**
     * The reference material to look up after arriving at [place]: after viewing an App's UI, look up that App's;
     * when viewing a UI in a web page, reading a page, or opening a web page, look up that site by host.
     * Whether a site has already been looked up is tracked by the caller. [ok] tells whether this call succeeded.
     */
    fun knowledgeAfter(verb: Verb?, place: Place?, ok: Boolean): List<KnowledgeLookup> = when {
        place == null -> emptyList()
        // Viewing a UI, listing elements, or launching an App: on arriving at that App, look up its knowledge. Apps not in the initial top 3 at launch are added here (the same copy is never injected twice).
        place.kind == PlaceKind.App && (verb == Verb.Observe || verb == Verb.ListElements || verb == Verb.LaunchApp) -> listOf(KnowledgeLookup.App(place.name))
        place.kind == PlaceKind.Site && verb in SITE_RESULT_VERBS && ok -> listOf(KnowledgeLookup.Site(place.name.lowercase().removePrefix("www.")))
        else -> emptyList()
    }
}
