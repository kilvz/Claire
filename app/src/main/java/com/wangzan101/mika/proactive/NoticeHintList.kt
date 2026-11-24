package com.wangzan101.mika.proactive

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.proactive.NoticeHints
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The entry point for "worth a reminder": the count on the capsule comes from here; tapping the number on the capsule, or the row on the
 * proactive page, writes those entries as one message from Claire in a chat ([openTalk]). It does not call the model or trigger the
 * notifications' own click or reply; what happens after it is written into the chat (the user replies, a task starts) is the same as any chat.
 */
object NoticeHintList {
    /** How many are unseen; 0 while the proactive switch is off, and the proactive runtime is not even created (see [FeatureFlags.proactiveChanges]). No timing, it only follows changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun unseenCount(context: Context): Flow<Int> = FeatureFlags.get(context).proactiveChanges().flatMapLatest { on ->
        if (on) (context.applicationContext as MikaApp).proactive.hints.map { it.unseen } else flowOf(0)
    }.distinctUntilChanged()

    /**
     * The user opened the reminder: see [ProactiveRuntime.openHintTalk]. Returns the id of the reminder chat (already made the current chat);
     * null when there is nothing to open or an error occurred. Call only where reminders are visible (the proactive runtime is on then).
     */
    suspend fun openTalk(context: Context): String? = try {
        (context.applicationContext as MikaApp).proactive.openHintTalk()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "could not open the notice talk: ${e.javaClass.simpleName}")
        null
    }

    private const val TAG = "MikaNoticeHint"
}

/**
 * The row on the proactive page (the entry when the capsule cannot show reminders: the switch is not "always show", or the person is inside
 * Claire): "Worth a reminder (N)", with the two numbers below being how many were seen and how many were handled unseen; tapping it opens the
 * same reminder chat, inside Claire.
 */
@Composable
internal fun NoticeHintEntry(state: NoticeHints.State) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    GroupCard {
        ListRow(
            null, stringResource(R.string.notice_hint_entry, state.unseen),
            stringResource(R.string.notice_hint_feedback, state.seenTotal, state.dismissedUnseenTotal),
            first = true,
            onClick = {
                scope.launch {
                    val id = NoticeHintList.openTalk(context) ?: return@launch
                    runCatching { context.startActivity(TaskService.openAppIntent(context, id)) }
                }
            },
        )
    }
}
