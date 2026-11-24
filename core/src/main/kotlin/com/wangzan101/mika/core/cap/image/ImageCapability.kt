package com.wangzan101.mika.core.cap.image

import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ToolContext

/** Image capability: generation, editing, and saving to the album. Without a configured image model the whole capability is not installed and no tools are sent to the model. */
const val IMAGE = "image"

internal class ImageCapability(
    val port: ImagePort?,
    val saveEnabled: Boolean,
    private val askBeforeSave: Boolean,
    val maxPerTask: Int,
) : Capability {
    override val id = IMAGE
    override val folds = true

    private var used = 0

    override fun status(): CapabilityStatus =
        if (port != null) CapabilityStatus.Available else CapabilityStatus.Unavailable("no image model")

    override fun tools(): List<AgentTool> = ImageGroup.forTask(saveEnabled, askBeforeSave)

    override fun sections(env: PromptEnv): List<String> =
        if (env.isOpen(id) && tools().any { it.name in env.offered }) listOf(env.gate(Prompts.capability(IMAGE))) else emptyList()

    override fun onTaskStart(ctx: ToolContext) {
        ctx[KEY] = this
    }

    /** The number of images already generated/planned in this task; beyond the limit the tool refuses outright instead of spending the provider's money. */
    @Synchronized fun reserve(count: Int): Boolean {
        if (count < 1 || used + count > maxPerTask) return false
        used += count
        return true
    }

    @Synchronized fun refund(count: Int) {
        used = (used - count).coerceAtLeast(0)
    }

    @Synchronized fun used(): Int = used

    companion object {
        val KEY = CapabilityKey<ImageCapability>(IMAGE)
    }
}
