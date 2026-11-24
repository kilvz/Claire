package com.wangzan101.mika.chat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.wangzan101.mika.R
import com.wangzan101.mika.core.frontdesk.ActionKind
import com.wangzan101.mika.core.frontdesk.DeskTaskView
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** One question card at the end of the conversation for each task the front desk dispatched that needs you right now ([DeskTaskView.need] not empty); see [needCardKey] for the row key. */
internal fun LazyListScope.deskNeedRows(
    tasks: List<DeskTaskView>, highlightKey: String?, reduceMotion: Boolean,
    answer: (taskId: String, words: String) -> Unit, open: (taskId: String) -> Unit, cancel: (taskId: String) -> Unit,
) {
    tasks.filter { it.need != null }.forEach { view ->
        val key = needCardKey(view.taskId)
        item(key) {
            JumpHighlight(highlightKey == key, reduceMotion) {
                DeskNeedCard(view, key, { answer(view.taskId, it) }, { open(view.taskId) }, { cancel(view.taskId) })
            }
        }
    }
}

/**
 * A task is waiting on you: with options it says "Choose" (tapping one replies straight to the job, and if none fit you type below); when it only asks without options it says "Reply";
 * when it needs you to take over in the picture it says "Take over", and when it needs your confirmation or a prior action it says "Handle", both offering "Handle it" (enter this job's conversation) and "Stop this job".
 */
@Composable
private fun DeskNeedCard(view: DeskTaskView, key: String, onAnswer: (String) -> Unit, onOpen: () -> Unit, onCancel: () -> Unit) {
    val need = view.need ?: return
    val options = need.options.orEmpty()
    val asking = need.action == null
    val title = when {
        asking && options.isNotEmpty() -> R.string.desk_need_choose
        asking -> R.string.desk_need_answer
        need.action == ActionKind.Takeover -> R.string.desk_need_takeover
        else -> R.string.desk_need_handle
    }
    val foot = when {
        asking && options.isNotEmpty() -> R.string.desk_need_choose_foot
        asking -> R.string.desk_need_answer_foot
        need.action == ActionKind.Takeover -> R.string.desk_need_takeover_foot
        else -> null
    }
    val body = need.prompt ?: view.line.orEmpty()
    Box(Modifier.testTag(key)) {
        NeedCard(
            stringResource(title, view.title), body,
            footer = foot?.let { res ->
                {
                    Text(
                        stringResource(res), Modifier.fillMaxWidth(), style = MikaType.Hint, color = MikaTheme.colors.ink3,
                        textAlign = TextAlign.Center,
                    )
                }
            },
            options = if (asking && options.isNotEmpty()) ({
                options.forEach { o -> MikaButton(o, { onAnswer(o) }, Modifier.fillMaxWidth(), kind = ButtonKind.Tonal, size = ButtonSize.Medium) }
            }) else null,
            buttons = if (asking) null else ({
                CardButton(stringResource(R.string.desk_need_stop), onClick = onCancel)
                CardButton(stringResource(R.string.desk_need_go), primary = true, onClick = onOpen)
            }),
        )
    }
}
