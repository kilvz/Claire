package com.wangzan101.mika

import android.app.Notification
import android.app.Service
import android.os.Build
import android.content.pm.ServiceInfo

/**
 * Puts the service in the foreground and returns true on success. From Android [typeSince] onward it includes the foreground service type [type] (special-use types must be declared from Android 14, microphone types from Android 11);
 * older systems pass no type. If the system refuses the foreground transition (background start denied, missing permission) it returns false and hands off to [onFailure] instead of crashing the process.
 * The notification is built inside: a failure building it also counts as failing to enter the foreground.
 */
internal inline fun Service.enterForeground(
    id: Int,
    type: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
    typeSince: Int = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
    onFailure: (Throwable) -> Unit = {},
    notification: () -> Notification,
): Boolean = runCatching {
    val shown = notification()
    if (Build.VERSION.SDK_INT >= typeSince) startForeground(id, shown, type) else startForeground(id, shown)
}.onFailure(onFailure).isSuccess
