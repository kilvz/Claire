package com.wangzan101.mika.core.run.record

/** Receiver of run records. Implementations swallow write errors themselves: a recording failure must not affect the task. */
interface RunSink {
    fun event(e: RunEvent)

    /** [png] is the saved frame with id [mediaId]. */
    fun media(mediaId: Int, png: ByteArray) {}

    companion object {
        val NONE: RunSink = object : RunSink {
            override fun event(e: RunEvent) {}
        }
    }
}
