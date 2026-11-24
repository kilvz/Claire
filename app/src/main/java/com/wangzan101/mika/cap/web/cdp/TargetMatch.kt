package com.wangzan101.mika.cap.web.cdp

import com.wangzan101.mika.core.cap.web.cdp.CdpTargetInfo

/** Matching a tab to a DevTools target: first write a marker into the tab, then evaluate each candidate target to find it. */
object TargetMatch {
    /** Name of the global variable holding the marker in the page; deleted once found. */
    const val KEY = "__mikaDevtoolsMark"

    fun markScript(marker: String): String = "window['$KEY']='$marker';1"
    const val READ_SCRIPT = "window['$KEY']||''"
    const val CLEAR_SCRIPT = "delete window['$KEY'];1"

    /**
     * Targets to try one by one: pages only, not already attached by another client (has a webSocketDebuggerUrl), and not already claimed by another tab;
     * those whose URL matches the tab's current URL come first (that is most likely the one).
     */
    fun candidates(targets: List<CdpTargetInfo>, claimed: Set<String>, tabUrl: String?): List<CdpTargetInfo> =
        targets.filter { it.type == "page" && it.webSocketDebuggerUrl != null && it.id !in claimed }
            .sortedBy { if (!tabUrl.isNullOrEmpty() && it.url == tabUrl) 0 else 1 }

    fun isMarker(value: String?, marker: String): Boolean = marker.isNotEmpty() && value == marker
}
