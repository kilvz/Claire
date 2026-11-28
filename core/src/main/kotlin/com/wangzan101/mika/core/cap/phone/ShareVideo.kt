package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.Verb

/** Shares a workspace video to an app, opening its share page but never sending it. */
internal object ShareVideo : ShareFileTool("phone_share_video", "video", "/workspace/out/video/clip.mp4") {
    override val verb = Verb.ShareVideo
    override val label = "Share video to app"
    override fun available(ctx: ToolContext) = ctx.phone.screen.canShareVideo
    override suspend fun share(ctx: ToolContext, packageName: String, path: String, text: String?) =
        ctx.phone.screen.shareVideo(packageName, path, text)
}
