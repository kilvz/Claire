package com.wangzan101.mika.core.screen.uistate

import com.wangzan101.mika.core.device.access.AppAccessDenied

/** Best-effort fallbacks for screen reading must not downgrade a permission denial into having no content. */
internal fun Exception.rethrowAppAccess() {
    if (this is AppAccessDenied) throw this
}

/** An ordinary capture failure may be degraded; a permission denial and cancellation must propagate to the caller. */
internal suspend inline fun <T> screenAttempt(body: () -> T): T? = try {
    body()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    e.rethrowAppAccess()
    null
}
