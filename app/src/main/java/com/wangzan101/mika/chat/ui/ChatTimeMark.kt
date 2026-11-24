package com.wangzan101.mika.chat.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** A centered row of small, light time text between two turns spaced apart (see [timeMarkAt]). */
@Composable
internal fun TimeMarkLine(ms: Long, key: String) {
    val context = LocalContext.current
    Text(
        chatTime(context, ms),
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp).testTag(key),
        style = MikaType.Hint, color = MikaTheme.colors.ink3, textAlign = TextAlign.Center, maxLines = 1,
    )
}

/** Times in the conversation: today shows only the time, yesterday shows "Yesterday 21:30", earlier ones include the date. */
internal fun chatTime(context: Context, ms: Long): String =
    if (!DateUtils.isToday(ms) && DateUtils.isToday(ms + DateUtils.DAY_IN_MILLIS)) {
        context.getString(R.string.chat_time_yesterday, DateUtils.formatDateTime(context, ms, DateUtils.FORMAT_SHOW_TIME))
    } else {
        callTime(context, ms)
    }
