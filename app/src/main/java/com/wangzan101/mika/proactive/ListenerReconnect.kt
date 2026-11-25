package com.wangzan101.mika.proactive

/**
 * What to do after asking the system to rebind when the listener still has not connected. Pure logic, carried out by [ProactiveRuntime].
 *
 * "Ask the system to rebind" only works for listeners the system remembers as paused, meaning Claire itself asked to unbind them (screen lock, full exit).
 * When the system itself failed to bind, this request has no effect: some systems refuse to bind the listener while the app is not running and is not allowed to autostart, and do not retry
 * (as happens after boot or when the process has been force-stopped); after that the user opens Claire, the listener is authorized, not connected and not "paused", and no request can connect it.
 * Then first ask the system to unbind by component name—which puts it into "paused"—and then ask to rebind, so the system actually binds it; Claire is running at that point, so it can.
 *
 * Do this only when all of these hold: watching, not locked (it should not be connected while locked), not connected, and notification access is still granted;
 * and the system has the unbind-by-component API (from [SDK_BY_NAME]). Earlier systems have no way to do it, so keep waiting for the system to bind on its own.
 * If the system is merely slow, doing this once more does no harm: the bind in progress still completes; for one already bound whose connected callback is just late,
 * it disconnects and reconnects once more, and duplicates from the catch-up are removed by deduplication.
 */
object ListenerReconnect {
    /** Wait this long after asking to rebind before checking again; a normal connection takes less than a second. */
    const val WAIT_MS = 3_000L
    /** The unbind-by-component API exists from this system version. */
    const val SDK_BY_NAME = 34

    enum class Step {
        /** Do nothing. */
        None,
        /** Ask the system to unbind by component name, then ask to rebind. */
        Reregister,
    }

    fun next(watching: Boolean, locked: Boolean, connected: Boolean, authorized: Boolean, sdk: Int): Step =
        if (watching && !locked && !connected && authorized && sdk >= SDK_BY_NAME) Step.Reregister else Step.None
}
