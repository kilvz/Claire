package com.wangzan101.mika.chat.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.MarkdownText
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/**
 * The rows a front desk turn takes in the conversation (following the prototype): the user's words (absent on a report turn), and the front desk's reply (the same faint grey block as other answers, optionally with a small tool tag on top).
 * Tasks dispatched by the front desk are not drawn as cards in the conversation; see the progress row above the input bar, and a question card at the end of the conversation when it needs you. Row keys match [chatItemKeys].
 */
internal fun LazyListScope.deskExchange(
    index: Int, ex: Exchange, conversationId: String?, reduceMotion: Boolean, highlightKey: String?,
) {
    if (ex.goal.isNotEmpty()) item("u$index") {
        JumpHighlight(highlightKey == "u$index", reduceMotion) { UserBubble(ex.goal, conversationId, key = "u$index") }
    }
    item("r$index") {
        JumpHighlight(highlightKey == "r$index", reduceMotion) { DeskReply(ex, "r$index", reduceMotion) }
    }
}

/**
 * The front desk's reply: a faint grey block, hugging the text width when short and filling it when long; while thinking the block shows three dots plus "Thinking", growing as it speaks (with a cursor at the end);
 * a turn that ended without saying anything (only dispatched tasks) is not drawn at all.
 */
@Composable
private fun DeskReply(ex: Exchange, key: String, reduceMotion: Boolean) {
    if (!deskReplyShown(ex)) return
    val c = MikaTheme.colors
    val text = ex.deskReply.orEmpty()
    val working = ex.status == ExchangeStatus.Working
    Column(Modifier.fillMaxWidth().padding(bottom = ReplyGap).testTag("desk-reply")) {
        ex.deskTool?.let { tool ->
            Text(
                tool, Modifier.padding(bottom = 4.dp).background(c.fill, MikaShapes.Chip).padding(horizontal = 8.dp, vertical = 1.dp),
                style = MikaType.Micro, color = c.ink3, maxLines = 1,
            )
        }
        when {
            text.isNotBlank() && working -> MarkdownText(text, AnswerText.copy(color = c.ink), Modifier.answerSurface(), streaming = true)
            text.isNotBlank() -> AnswerArea(key, text, Modifier.answerSurface()) { MarkdownText(text, AnswerText.copy(color = c.ink)) }
            working -> Thinking(reduceMotion)
        }
        if (ex.status is ExchangeStatus.Failed) {
            Text(stringResource(R.string.desk_reply_failed), Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3)
        }
    }
}

/** Not speaking yet: three bobbing dots plus "Thinking" in a grey block. */
@Composable
private fun Thinking(reduceMotion: Boolean) {
    val c = MikaTheme.colors
    Row(Modifier.answerSurface(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThinkingDots(reduceMotion)
        Text(stringResource(R.string.desk_thinking), style = MikaType.Caption, color = c.ink3, maxLines = 1)
    }
}

/** The front desk is thinking: three small dots bobbing in turn; static when animation is off. */
@Composable
private fun ThinkingDots(reduceMotion: Boolean) {
    val c = MikaTheme.colors
    Row(
        Modifier.padding(vertical = 6.dp, horizontal = 2.dp).clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(3) { i ->
            val phase = if (reduceMotion) 1f else rememberInfiniteTransition(label = "desk-think").animateFloat(
                0f, 1f,
                infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse, StartOffset(i * 150)),
                label = "desk-think-$i",
            ).value
            Box(
                Modifier.size(6.dp).graphicsLayer {
                    alpha = 0.25f + 0.75f * phase
                    translationY = -3.dp.toPx() * phase
                }.background(c.ink3, CircleShape),
            )
        }
    }
}
