package com.wangzan101.mika.core.device.shell

/**
 * Local abstract socket of the WebView debugging interface: one per process that has debugging enabled, named `webview_devtools_remote_<pid>`.
 * When the built-in helper connects on its behalf, it only accepts names of this shape, and the pid must belong to Claire's uid.
 */
object DevtoolsSocket {
    private const val PREFIX = "webview_devtools_remote_"
    private val NAME = Regex("""webview_devtools_remote_([1-9][0-9]{0,9})""")

    fun name(pid: Int): String = "$PREFIX$pid"

    /** The pid in the name; null when the name is not of this shape (extra prefix/suffix, leading zero, out of range). */
    fun pidOf(name: String): Int? = NAME.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

    /** The real uid from `/proc/<pid>/status` (first column of the Uid line); null when that line is absent. */
    fun procUid(status: String): Int? =
        status.lineSequence().firstOrNull { it.startsWith("Uid:") }
            ?.removePrefix("Uid:")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()
}
