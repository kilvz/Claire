package com.wangzan101.mika.chat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.AutoArchive
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.RadioRow

/** Auto-archive period in days: off, or archive after this many days without new messages. Picking takes effect immediately, and an archived conversation can be restored from the archive view. */
@Composable
internal fun AutoArchiveDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_auto_archive)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_auto_archive_hint), Modifier.padding(bottom = 8.dp))
                AutoArchive.DAYS.forEachIndexed { index, days ->
                    RadioRow(
                        title = if (days <= 0) stringResource(R.string.state_off)
                        else pluralStringResource(R.plurals.settings_auto_archive_days, days, days),
                        selected = current == days,
                        onClick = { onPick(days) },
                        first = index == 0,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok_got_it)) } },
    )
}
