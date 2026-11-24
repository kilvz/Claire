package com.wangzan101.mika.task

import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.wechat.WechatChannel

/**
 * At start it provides unlocking for scheduled tasks, authorized WeChat tasks, or UI tasks with the local switch on; the relevant authorization is still re-read when actually unlocking.
 * Other origins (MCP, subtasks) never get unlock authorization.
 */
internal fun taskUnlockAuthorization(
    origin: RunOrigin,
    peer: ChannelPeer?,
    localUnlockEnabled: () -> Boolean = { false },
    canUnlockWechat: (ChannelPeer?) -> Boolean,
): (() -> Boolean)? = when {
    origin == RunOrigin.Trigger -> ({ true })
    origin == RunOrigin.Channel && peer?.channelId == WechatChannel.ID && canUnlockWechat(peer) ->
        ({ canUnlockWechat(peer) })
    origin == RunOrigin.Ui && localUnlockEnabled() -> ({ localUnlockEnabled() })
    else -> null
}
