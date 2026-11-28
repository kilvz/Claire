package com.wangzan101.mika.task.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.icu.text.TimeZoneNames
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.uiLocale
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Time zone picker: shows cities and time zone names in the UI language, searchable by city, ID, time zone name or an offset such as "+8" or "GMT+8".
 * Localized names for hundreds of time zones are indexed once on a background thread; input only filters the index instead of looking up each name.
 */
@Composable
internal fun PlanTimezonePickerDialog(
    currentZoneId: String,
    onDismiss: () -> Unit,
    onPicked: (String) -> Unit,
) {
    val locale = uiLocale()
    var query by remember { mutableStateOf("") }
    // Already warmed up in the background when the editor opened, so usually the index is ready here; if not, build it in the background and then show it.
    val index by produceState(PlanZoneIndex.cached(locale), locale) {
        if (value == null) value = withContext(Dispatchers.Default) { PlanZoneIndex.get(locale) }
    }
    val zones = remember(index, query, currentZoneId) {
        index?.let { PlanTimeFormat.searchZones(it, query, currentZoneId) }
    }
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.plans_timezone)) },
        text = {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val listMaxHeight = maxHeight * 0.52f
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().testTag("plan-zone-search"),
                        placeholder = { Text(tr(R.string.plans_timezone_search)) },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    when {
                        zones == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), color = MikaTheme.colors.accent, strokeWidth = 2.dp)
                        }
                        zones.isEmpty() -> EmptyNote(tr(R.string.plans_timezone_empty))
                        else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = listMaxHeight).testTag("plan-zone-list")) {
                            items(zones, key = { it.id }) { entry ->
                                ListRow(
                                    icon = null,
                                    title = entry.city,
                                    subtitle = "${entry.displayName} · ${entry.offsetLabel}",
                                    first = entry === zones.first(),
                                    onClick = { onPicked(entry.id) },
                                    modifier = Modifier.testTag("plan-zone-${entry.id}"),
                                ) {
                                    if (entry.id == currentZoneId) {
                                        Icon(MikaIcons.Check, null, Modifier.size(18.dp), tint = MikaTheme.colors.accent)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("plan-zone-cancel")) { Text(tr(R.string.cancel)) }
        },
    )
}

/** How the selected time zone is described in the editor: localized city name + offset. */
internal fun zoneSummary(zoneId: String, locale: Locale): String {
    val city = runCatching { TimeZoneNames.getInstance(locale).getExemplarLocationName(zoneId) }.getOrNull()
    val offset = runCatching { PlanTimeFormat.zoneOffsetLabel(java.time.ZoneId.of(zoneId)) }.getOrNull()
    return listOfNotNull(city?.takeIf { it.isNotBlank() } ?: zoneId, offset).joinToString(" · ")
}

/**
 * Time zone index built per UI language and reused within the process. Looking up localized city and time zone names for hundreds of zones one by one takes hundreds of milliseconds to seconds on a device the first time,
 * so it is not looked up fresh each time the picker opens. Offsets change with daylight saving time, so the index is rebuilt after an hour. Only one is built at a time per language, and other callers wait for it.
 * [get] blocks, so call it only on a background thread (or a test thread).
 */
internal object PlanZoneIndex {
    private const val MAX_AGE_MS = 3_600_000L
    private class Built(val atMs: Long, val zones: List<ZoneEntry>)
    private val built = java.util.concurrent.ConcurrentHashMap<Locale, Built>()
    private val building = Any()

    /** Callable from the main thread: reads only a ready index and does not wait for one being built. */
    fun cached(locale: Locale, nowMs: Long = System.currentTimeMillis()): List<ZoneEntry>? =
        built[locale]?.takeIf { nowMs - it.atMs < MAX_AGE_MS }?.zones

    fun get(locale: Locale): List<ZoneEntry> = synchronized(building) {
        val nowMs = System.currentTimeMillis()
        cached(locale, nowMs)?.let { return it }
        val names = TimeZoneNames.getInstance(locale)
        val zones = PlanTimeFormat.zoneIndex(
            locale,
            now = java.time.Instant.ofEpochMilli(nowMs),
            cityName = { id -> runCatching { names.getExemplarLocationName(id) }.getOrNull() },
        )
        built[locale] = Built(nowMs, zones)
        return zones
    }
}
