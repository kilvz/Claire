package com.wangzan101.mika.core.screen.uistate

import com.wangzan101.mika.core.tool.Until
import com.wangzan101.mika.core.tool.UntilOutcome
import com.wangzan101.mika.core.run.waitOrSteer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Keep at least this much time between two screen reads: UI events come in bursts, so there is no need to read for every one of them. */
private const val MIN_GAP_MS = 200L
/** When the port reports no UI events, re-read after this interval; it is also the longest interval between two checks for a user interjection. */
private const val POLL_MS = 500L
/** Upper bound for each wait handed to the port when waiting for "UI stable" (the port judges by its own quiet period). */
private const val STABLE_SLICE_MS = 1_500L
/** How many consecutive reads an anomaly needs before it counts (during page transitions the old and new windows briefly coexist). */
private const val CONFIRM_READS = 2

/**
 * The wait after one step of a batch finishes: read the screen repeatedly per [Until] (without registering refs) and compare literally, the same for phones and web pages, going only through [ScreenPort] and the screen-read results.
 * Wake early when the port has UI events ([ScreenPort.awaitChange]), otherwise re-read after a while. On spotting an anomaly (popup, another package covering it, app switch),
 * a user interjection, or the deadline, it stops waiting and hands back for the model to judge. The baseline is the screen when the wait began: switches caused by this step itself are verified separately by the execution entry point.
 */
internal class UntilWait(
    private val screen: ScreenPort,
    private val read: suspend () -> ScreenSnapshot?,
    private val clock: () -> Long,
    private val interrupted: () -> Boolean,
) {
    suspend fun run(until: Until): UntilOutcome {
        val start = clock()
        val timeout = until.timeoutMs.coerceIn(0, Until.MAX_MS)
        // Guarantee termination even when the clock does not advance (tests, clock errors): every round costs at least MIN_GAP_MS
        val maxRounds = timeout / MIN_GAP_MS + 2
        var base: ScreenSnapshot? = null
        var stableNow = false
        var suspect: String? = null
        var seen = 0
        fun done(met: Boolean, stopped: String?) = UntilOutcome(met, stopped, (clock() - start).coerceAtLeast(0))
        repeat(maxRounds.toInt()) {
            val snap = read()
            if (snap != null) {
                val first = base ?: snap.also { base = it }
                // A round with an anomaly does not count as satisfied: text showing behind a popup does not mean the step can continue
                val odd = unexpected(first, snap, until)
                if (odd == null && (!until.stable || stableNow) && holds(until, snap)) return done(true, null)
                if (odd != null && odd == suspect) seen++ else { suspect = odd; seen = if (odd == null) 0 else 1 }
                // While a page dialog is up the page is at rest, so no extra read is needed to confirm
                if (suspect != null && (seen >= CONFIRM_READS || snap.web?.dialog != null)) return done(false, suspect)
            }
            if (interrupted()) return done(false, UntilOutcome.NEW_MESSAGE)
            val left = timeout - (clock() - start)
            if (left <= 0) return done(false, UntilOutcome.TIMEOUT)
            // Every wait segment ends immediately on user interjection (see waitOrSteer), rather than only being checked in the next round
            if (until.stable) {
                stableNow = waitOrSteer { waitStable(minOf(left, STABLE_SLICE_MS)) } ?: return done(false, UntilOutcome.NEW_MESSAGE)
            } else if (waitOrSteer { awaitChange(minOf(left, POLL_MS)) } ?: return done(false, UntilOutcome.NEW_MESSAGE)) {
                delay(MIN_GAP_MS)
            }
        }
        return done(false, UntilOutcome.TIMEOUT)
    }

    private suspend fun waitStable(ms: Long): Boolean =
        try { screen.waitStable(ms) } catch (e: CancellationException) { throw e } catch (e: Exception) { delay(ms); false }

    private suspend fun awaitChange(ms: Long): Boolean =
        try { screen.awaitChange(ms) } catch (e: CancellationException) { throw e } catch (e: Exception) { delay(ms); false }

    companion object {
        /** Whether the condition holds right now (only the items provided are checked); like the expect verification in action results, it compares by element name, case-insensitively. */
        fun holds(until: Until, s: ScreenSnapshot): Boolean {
            val names = s.elements.map { it.name }
            until.appears?.trim()?.let { want -> if (names.none { it.contains(want, ignoreCase = true) }) return false }
            // Reading nothing at all (protected, unreadable) does not count as "already gone"
            until.gone?.trim()?.let { gone -> if (names.isEmpty() || names.any { it.contains(gone, ignoreCase = true) }) return false }
            until.place?.trim()?.let { p -> if (!inPlace(s, p)) return false }
            return true
        }

        private fun inPlace(s: ScreenSnapshot, p: String) =
            s.packageName?.contains(p, ignoreCase = true) == true || s.web?.url?.contains(p, ignoreCase = true) == true

        /**
         * Whether [now] shows an obvious anomaly compared with the screen [base] at the start of the wait; null if not.
         * A page dialog hanging open, or one more application window of the same app, is [UntilOutcome.POPUP]; an extra application or system window of another package is [UntilOutcome.SYSTEM_DIALOG];
         * the foreground switching to a different app (and not the one [until] waits for) is [UntilOutcome.APP_SWITCHED]. A web page moving to another site is a normal navigation, not an app switch.
         */
        fun unexpected(base: ScreenSnapshot, now: ScreenSnapshot, until: Until): String? {
            if (now.web?.dialog != null && base.web?.dialog == null) return UntilOutcome.POPUP
            val top = now.packageName
            val foreign = { s: ScreenSnapshot ->
                s.windows.filter { it.type in OVERLAY_TYPES && it.packageName != null && it.packageName != s.packageName }.map { it.id }.toSet()
            }
            if ((foreign(now) - foreign(base)).isNotEmpty()) return UntilOutcome.SYSTEM_DIALOG
            if (now.web == null && top != null && top != base.packageName && until.place?.let { inPlace(now, it.trim()) } != true) return UntilOutcome.APP_SWITCHED
            val own = { s: ScreenSnapshot -> s.windows.count { it.type == APPLICATION && it.packageName == top } }
            if (top == base.packageName && own(now) > own(base)) return UntilOutcome.POPUP
            return null
        }

        private const val APPLICATION = "application"
        private val OVERLAY_TYPES = setOf(APPLICATION, "system")
    }
}
