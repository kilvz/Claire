package com.wangzan101.mika.task

/**
 * What the Picture-in-Picture small window shows while on the web UI (pure logic; [TaskRun] updates by it every half second).
 *
 * The small window's picture comes from the current tab attached to the web display. When a web page is read only in a background temporary tab (`web_read_page` with an address),
 * the web display has no tab and drawing it would just be a white area: then it does not count as being on the web, the small window does not appear, and the floating capsule shows the conversation card instead;
 * as soon as a visible page is opened it shows as usual. While the page is in the user's hands (during takeover) it still counts as being on the web, but no picture is shown.
 */
enum class WebPreview(val open: Boolean) {
    /** Not on the web: does not count as being on the web and the picture does not change (already cleared when switching UI). */
    Off(false),

    /** On the web but with no visible tab (read only in a background temporary tab): does not count as being on the web, clears the picture. */
    NoPage(false),

    /** On the web with the page in the user's hands: counts as being on the web, no picture is shown. */
    Hidden(true),

    /** On the web with a visible page: show the latest frame. */
    Frames(true);

    companion object {
        fun of(onWeb: Boolean, hasTab: Boolean, handedOver: Boolean): WebPreview = when {
            !onWeb -> Off
            !hasTab -> NoPage
            handedOver -> Hidden
            else -> Frames
        }
    }
}
