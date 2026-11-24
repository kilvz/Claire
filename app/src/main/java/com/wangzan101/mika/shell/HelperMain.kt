package com.wangzan101.mika.shell

import com.wangzan101.mika.cap.phone.shell.HelperProcess

/** Entry point for the built-in helper process: the launch command uses this fully qualified name (see HelperHandoff.HELPER_CLASS); the implementation lives in the phone capability. */
object HelperMain {
    @JvmStatic
    fun main(args: Array<String>) = HelperProcess.main(args)
}
