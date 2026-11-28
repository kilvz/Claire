package com.wangzan101.mika.shell

import androidx.annotation.Keep
import com.wangzan101.mika.cap.phone.shell.ScreenInspect

/**
 * Read-only screen inspection entry point: the desktop CLI (`mika screen`) writes this full name in its command and it does not change with internal refactors; the implementation lives in the phone capability.
 * `CLASSPATH=<base.apk> app_process /system/bin com.wangzan101.mika.shell.ScreenInspectMain`
 */
@Keep
object ScreenInspectMain {
    @JvmStatic
    fun main(args: Array<String>) = ScreenInspect.main(args)
}
