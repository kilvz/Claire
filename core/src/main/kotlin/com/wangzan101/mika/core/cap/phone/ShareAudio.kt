package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.Verb

/** Shares a workspace audio file to an app, opening its share page but never sending it. */
internal object ShareAudio : ShareFileTool("phone_share_audio", "audio", "/workspace/out/audio/line.wav") {
    override val verb = Verb.ShareAudio
    override val label = "Share audio to app"
    override fun available(ctx: ToolContext) = ctx.phone.screen.canShareAudio
    override suspend fun share(ctx: ToolContext, packageName: String, path: String, text: String?) =
        ctx.phone.screen.shareAudio(packageName, path, text)
}
