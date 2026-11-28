package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.Verb

/** Shares a workspace image to an app, opening its share page but never sending it. */
internal object ShareImage : ShareFileTool("phone_share_image", "image",
    "/workspace/out/images/cat.png or /workspace/inbox/photo.jpg") {
    override val verb = Verb.ShareImage
    override val label = "Share image to app"
    override fun available(ctx: ToolContext) = ctx.phone.screen.canShareImage
    override suspend fun share(ctx: ToolContext, packageName: String, path: String, text: String?) =
        ctx.phone.screen.shareImage(packageName, path, text)
}
