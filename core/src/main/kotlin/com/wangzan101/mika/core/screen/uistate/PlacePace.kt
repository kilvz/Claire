package com.wangzan101.mika.core.screen.uistate

/** The pace requirement this site or App imposes, as stated by `pace` in site knowledge and App knowledge (for App pacing see [com.wangzan101.mika.core.cap.phone.AppPacing]). */
enum class PlacePace {
    /** No special requirement. */
    NORMAL,
    /** This site or App throttles browsing (looking too fast or at too much gets you blocked): dwell longer on each page, and view fewer pages and screens per task. */
    CAREFUL;

    companion object {
        /** Value in the site knowledge header (`careful`; omitted means null). */
        fun of(value: String?): PlacePace = if (value == "careful") CAREFUL else NORMAL
    }
}
