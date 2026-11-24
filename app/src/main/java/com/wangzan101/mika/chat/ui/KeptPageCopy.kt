package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.core.cap.web.KeptPageClose
import com.wangzan101.mika.i18n.tr

/** The UI wording for the page kept running in the background: how much time is left and why it was closed. */
object KeptPageCopy {
    /** "1 hour 52 min", "2 hours", "8 minutes"; anything under a minute is rounded up to one minute. */
    fun duration(ms: Long): String {
        val minutes = ((ms.coerceAtLeast(0) + 59_999) / 60_000).coerceAtLeast(1)
        val h = (minutes / 60).toInt()
        val m = (minutes % 60).toInt()
        return when {
            h == 0 -> tr(R.string.kept_m, m)
            m == 0 -> tr(R.string.kept_h, h)
            else -> tr(R.string.kept_hm, h, m)
        }
    }

    /** The wording for auto-closing with [ms] left (concatenated into "… until it closes automatically"). */
    fun closesIn(ms: Long): String = duration(ms)

    /** The reason it was closed; when it is not recognized (old record, not recorded), say "Claire exited". */
    fun reason(wire: String?): String = when (KeptPageClose.of(wire)) {
        KeptPageClose.Expired -> tr(R.string.kept_reason_expired)
        KeptPageClose.Memory -> tr(R.string.kept_reason_memory)
        KeptPageClose.Crashed -> tr(R.string.kept_reason_crashed)
        KeptPageClose.User -> tr(R.string.kept_reason_user)
        KeptPageClose.Replaced -> tr(R.string.kept_reason_replaced)
        KeptPageClose.AppExit, null -> tr(R.string.kept_reason_app_exit)
    }

    /** The line shown under that round: "background page closed (reason)"; when the reason is not recorded yet (just closed, or Claire exited partway through), the reason is omitted. */
    fun closedNote(wire: String?): String = if (KeptPageClose.of(wire) == null) tr(R.string.kept_closed_plain) else tr(R.string.kept_closed_note, reason(wire))
}
