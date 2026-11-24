package com.wangzan101.mika.core.screen.user

/** One line of text on the screen: [x]/[y] is its center, in the same 0–999 coordinate system as tapping (0,0 at the top left). */
data class ScreenLine(val text: String, val x: Int, val y: Int)

/**
 * The "screen right now" sent by the user: the screen the user is looking at on their main display when they double-tap the capsule or the screenshot button in the card.
 * It is not the same as an image picked from the gallery or files: it says "I am looking at this right now", carrying the foreground app, the capture time and the on-screen text.
 * [packageName]/[appLabel] are the foreground app (null if unreadable); when [blocked] the page disallows screenshots (screenshot protection), so there is no frame and [lines] is empty too;
 * [lines] is the on-screen text (from the accessibility tree, topped up with recognized text when the tree is inadequate), and when [fromOcr] some of it came from recognition.
 */
data class UserScreen(
    val id: String,
    val takenAt: Long,
    val packageName: String?,
    val appLabel: String?,
    val blocked: Boolean = false,
    val lines: List<ScreenLine> = emptyList(),
    val fromOcr: Boolean = false,
) {
    /** How to refer to that app: use the label when there is one, otherwise the package name. */
    val app: String get() = appLabel?.takeIf(String::isNotBlank) ?: packageName ?: UNKNOWN_APP

    companion object {
        const val UNKNOWN_APP = "某个 App"
    }
}

/** Fetches a user-sent screen of this conversation by id: both the description and the screenshot may no longer exist (null when unavailable). */
interface UserScreenSource {
    suspend fun screen(id: String): UserScreen?

    /** The captured frame (JPEG or PNG); null for screenshot-protected pages or when unavailable. */
    suspend fun image(id: String): ByteArray?
}

/** A captured screen that has not been sent yet: the frame ([jpeg], null when screenshots are disallowed) and the description ([screen], whose id is assigned only when it is stored into the conversation). */
class CapturedScreen(val jpeg: ByteArray?, val screen: UserScreen, val sourcePackages: Set<String> = setOfNotNull(screen.packageName))
