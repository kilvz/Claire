package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.screen.uistate.DwellInterrupted
import com.wangzan101.mika.core.screen.uistate.IdleScroll
import com.wangzan101.mika.core.screen.uistate.PageLimitReached
import com.wangzan101.mika.core.run.waitOrSteer
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Per-task dwell inside Apps according to [AppPacing]: after every action that changes the screen record it
 * ([after]), and before the next one top up whatever time was not yet spent ([before]).
 * [tempo] gives the effective tempo of an App by package name ([AppTempo]; "fast" Apps neither record nor wait),
 * and [capped] says which Apps limit browsing speed (screens are counted and capped).
 * When [idle] is given, the current scope allows it, and the caller supplied a screen port (the upcoming actions do
 * not depend on positions on screen), scroll gently a few times during the dwell as it plans ([ScreenPort.idleScroll]).
 * When a browsing-capped App has reached the screen cap in this task, [before] stops waiting and throws
 * [PageLimitReached] (the same result page_limit as the web); when the user interjects during a dwell
 * ([waitOrSteer]), it throws [DwellInterrupted]: the caller skips this action, and the shortfall is still made up
 * next time.
 */
internal class AppDwell(
    private val pacing: AppPacing,
    private val tempo: (String) -> AppTempo = { AppTempo.NORMAL },
    private val capped: (String) -> Boolean = { false },
    private val idle: IdleScroll? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** How long it dwelled, and how many gentle scrolls happened during the dwell. */
    class Dwelt(val waitMs: Long, val idleScrolls: Int)

    /** Called after a gentle scroll during the dwell (the screen position the model holds is no longer accurate). */
    var onIdleScroll: () -> Unit = {}

    private val tempos = HashMap<String?, AppTempo>()
    private val caps = HashMap<String, Boolean>()

    val paced: Boolean get() = pacing.paced

    /** An action that changes the screen finished in [app]: [newScreen] means it moved to a new screen, and [counts] means it counts toward the screen tally (see [AppPacing.noteAction]). */
    fun after(app: String?, newScreen: Boolean, counts: Boolean = newScreen) {
        if (!paced) return
        val t = tempoOf(app)
        if (t == AppTempo.FAST) return
        pacing.noteAction(app, clock(), newScreen, t, counts, app?.let(::cappedOf) ?: false)
    }

    /**
     * Before the next screen-changing action in [app]: top up if not enough time has passed. [textChars] is the
     * character count on the current screen (a new screen uses it to add reading time).
     * A "fast" App neither waits nor checks the cap. With [gate], the screen cap is checked first and
     * [PageLimitReached] is thrown when it is reached. When [port] is given, the dwell may scroll gently a few times.
     */
    suspend fun before(app: String?, textChars: Int, gate: Boolean, port: ScreenPort? = null): Dwelt {
        if (!paced) return Dwelt(0, 0)
        if (tempoOf(app) == AppTempo.FAST) return Dwelt(0, 0)
        val cap = pacing.screenCap
        if (gate && app != null && cap != null && cappedOf(app) && pacing.capReached(app, true)) {
            throw PageLimitReached(app, pacing.screensIn(app), cap, PageLimitReached.Scope.APP_SCREENS)
        }
        val wait = pacing.waitBefore(clock(), textChars)
        if (wait <= 0) return Dwelt(0, 0)
        val plan = idle?.takeIf { port != null && pacing.idleAllowed() }
        val scrolls = if (plan != null) idleWait(port!!, plan, wait) else 0.also { pause(wait) }
        return Dwelt(wait, scrolls)
    }

    /** The user answered: screen tallies for every App restart. */
    fun userAnswered() = pacing.userAnswered()

    /** An outward action finished in [app]; when [done] (it succeeded) this App no longer has a screen cap. */
    fun outwardEnd(app: String, done: Boolean) {
        if (done) pacing.outwardDone(app)
    }

    /** Sleep for [ms] milliseconds; if the user interjects, stop sleeping and throw [DwellInterrupted]. */
    private suspend fun pause(ms: Long) {
        waitOrSteer { sleep(ms) } ?: throw DwellInterrupted()
    }

    /** Sleep [wait] milliseconds, scrolling gently a few times per [plan] along the way; if the port says scrolling is not convenient right now (dialog, IME, focus), stop scrolling but still dwell the full time. Returns how many scrolls happened. */
    private suspend fun idleWait(port: ScreenPort, plan: IdleScroll, wait: Long): Int {
        val start = clock()
        var done = 0
        for (step in plan.plan(wait)) {
            val left = step.atMs - (clock() - start)
            if (left > 0) pause(left)
            val ok = try { port.idleScroll(step.fraction) } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
            if (!ok) break
            done++
            onIdleScroll()
        }
        val rest = wait - (clock() - start)
        if (rest > 0) pause(rest)
        return done
    }

    private fun tempoOf(app: String?): AppTempo = if (app == null) AppTempo.NORMAL else synchronized(tempos) { tempos.getOrPut(app) { tempo(app) } }
    private fun cappedOf(app: String): Boolean = synchronized(caps) { caps.getOrPut(app) { capped(app) } }
}
