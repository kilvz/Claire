package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.uiLocale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.wangzan101.mika.core.run.record.RunMetrics
import com.wangzan101.mika.core.model.usage.DeepSeekCost
import com.wangzan101.mika.record.RunStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import com.wangzan101.mika.settings.ui.UsageCopy

/** Token usage on the chat page: the row at the end of each turn shows that turn's usage. */
object ChatTokensCopy {
    /** An amount such as "1.2 wan Token", worded exactly as on the Token usage page. */
    fun amount(n: Long): String = "${UsageCopy.tokens(n)} ${UsageCopy.UNIT}"

    fun cost(estimate: DeepSeekCost): String {
        val min = estimate.minCny?.toBigDecimalOrNull() ?: return tr(R.string.chat_task_cost_unavailable)
        val max = estimate.maxCny?.toBigDecimalOrNull() ?: return tr(R.string.chat_task_cost_unavailable)
        val formatter = NumberFormat.getCurrencyInstance(uiLocale()).apply {
            currency = Currency.getInstance("CNY")
            minimumFractionDigits = 2
            maximumFractionDigits = 2
            roundingMode = RoundingMode.HALF_UP
        }
        val lower = formatter.format(min)
        val upper = formatter.format(max)
        val amount = if (lower == upper) lower else "$lower–$upper"
        val result = tr(R.string.chat_task_cost_estimate, amount)
        return if (estimate.partial) tr(R.string.chat_task_cost_partial, result) else result
    }
}

/**
 * Reads the run summaries for these turns (the writer thread records them when run recording is off; right after a turn
 * ends they may not be written yet, so when the read is incomplete the missing turns are re-read after a short wait).
 * Fetched by run id instead of parsing the whole summary file; disk reads happen on the IO thread.
 * When the turns change, the values from the previous read are kept until the new read finishes, so the end-of-turn row does not flicker.
 */
@Composable
fun rememberRunMetrics(runIds: List<String>): Map<String, RunMetrics> {
    val context = LocalContext.current
    val store = remember { RunStore(context).metrics }
    var found by remember { mutableStateOf(emptyMap<String, RunMetrics>()) }
    LaunchedEffect(runIds) {
        val wanted = runIds.toSet()
        if (wanted.isEmpty()) {
            found = emptyMap()
            return@LaunchedEffect
        }
        // Keep the entries from the previous read that are still wanted; only fetch the missing ones
        var read = found.filterKeys { it in wanted }
        for (wait in RETRY_MS) {
            val missing = wanted - read.keys
            if (missing.isEmpty()) break
            read = read + withContext(Dispatchers.IO) { store.byIds(missing) }
            found = read
            if (read.keys.containsAll(wanted)) break
            delay(wait)
        }
        found = read
    }
    return found
}

/** Retry intervals when the read is incomplete; anything still missing after these attempts (too old and already pruned) is treated as missing. */
private val RETRY_MS = listOf(300L, 1_000L, 2_000L, 4_000L, 0L)
