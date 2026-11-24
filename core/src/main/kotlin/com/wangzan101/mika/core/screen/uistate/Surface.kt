package com.wangzan101.mika.core.screen.uistate


/**
 * One set of same-named verb tools for a kind of screen (phone, web, …): the names are `<id>_<verb>`, the parameter
 * structure is the same, and the description and allowed values are given per screen.
 * Every capability with a screen supplies one; when no port is attached the tools are still registered (findable by
 * name), they are merely unavailable.
 */
class ScreenFamily(
    /** The capability id, which is also the prefix of this tool set's names. */
    val id: String,
    /** Prefix of this screen's snapshot ids; used to tell which screen a ref belongs to when it cannot be identified. */
    val snapshotPrefix: String,
    /** The field in a screen-read result that reports "where" it is. */
    val placeKey: String = APP_PLACE,
    /** The keys press_key may send; null means unrestricted. */
    val keys: Set<Int>? = null,
    /** Explanation given to the model when [keys] does not allow a key. */
    val keysHint: String? = null,
    /** Empirical routes record its actions and replay them. */
    val routable: Boolean = false,
    /** Tools in this set whose result is text can be called by `mika phone` in the sandbox. */
    val sandboxCallable: Boolean = false,
    /** These verbs use this screen's own description (prompts/tools/<id>_<verb>.md); the rest use the shared template. */
    val ownPrompts: Set<String> = emptySet(),
    /** Explanation given to the model when nothing is open yet on this screen (a feedback key). */
    val emptyHint: String = "empty_screen",
) {
    /** Name of the tool in this set that performs the [base] action. */
    fun tool(base: String) = "${id}_$base"

    companion object {
        const val APP_PLACE = "currentApp"
        const val SITE_PLACE = "currentSite"
    }
}

/** A screen with a port attached, supplied by a capability that has a screen. */
class Surface(
    val family: ScreenFamily,
    /** This screen's reads and actions go through it. */
    val screen: ScreenPort,
    /** How the declared app field should be written on this screen: throws ArgError when wrong, returns the canonical form when right. */
    val declaredTarget: (String) -> String,
    /** What to write in the declared app field on this screen (used in the tool parameter description). */
    val targetNote: String,
    /** The version of this screen at runtime (e.g. mobile or desktop web); null if none. */
    val mode: (suspend () -> String?)? = null,
) {
    val id: String get() = family.id
}

/**
 * Which screen is being operated: while a call is in progress it is the one the call acts on ([enter] to [leave]); when
 * no call is in progress it is the one most recently operated successfully, and at task start it is the first one.
 * Only used to pick defaults where no screen is named (confirming, takeover, verifying evidence, waiting for the
 * interface to settle); it does not forward any call. Calls may nest (actions inside page scripts).
 */
class SurfaceFocus(val surfaces: List<Surface>) {
    init {
        require(surfaces.isNotEmpty()) { "need at least one surface" }
    }

    /** The screen of the most recent successful operation. */
    @Volatile private var settled: Surface = surfaces.first()
    private val acting = ArrayDeque<Surface>()

    val current: Surface get() = synchronized(acting) { acting.lastOrNull() } ?: settled

    operator fun get(id: String): Surface? = surfaces.firstOrNull { it.id == id }

    /** A call starts, acting on the screen with [id]; when it is not a screen or none is given, the current one is used. */
    fun enter(id: String?) {
        val s = id?.let(::get) ?: current
        synchronized(acting) { acting.addLast(s) }
    }

    /** That call ends; when [succeeded], its screen becomes the most recently operated one, failures do not count. */
    fun leave(succeeded: Boolean) {
        val s = synchronized(acting) { acting.removeLastOrNull() } ?: return
        if (succeeded) settled = s
    }
}
