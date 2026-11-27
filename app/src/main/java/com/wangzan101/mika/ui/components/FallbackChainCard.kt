package com.wangzan101.mika.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** One item in a fallback chain: [name] is the model name (shown monospaced), [detail] says which provider/service it belongs to, and a true [missing] renders it faded. */
data class FallbackChainItem(val name: String, val detail: String = "", val missing: Boolean = false)

/**
 * The collapsible "Primary ➔ Backup 1 ➔ Backup 2 …" card shared by chat-model and voice fallback chains.
 * Tap a row to change the model; on the right are up/down/remove (48dp touch targets), and screen readers expose those
 * three actions on the row itself. Removal asks for confirmation first.
 * Test tags are `[tagPrefix]-<position>`, `-up`, `-down`, `-remove`; the trailing "Add" is `[tagPrefix]-<chain length>`.
 *
 * @param title Card title (usually `model_fallback_chain_title` with the role name).
 * @param removeBody Body of the removal confirmation, parameterized by the model name to remove.
 * @param footer Explanation below the card when it is expanded and has backups.
 */
@Composable
fun FallbackChainCard(
    title: String,
    primary: FallbackChainItem?,
    fallbacks: List<FallbackChainItem>,
    max: Int,
    tagPrefix: String,
    icon: ImageVector,
    removeBody: @Composable (model: String) -> String,
    footer: String,
    onPick: (slot: Int) -> Unit,
    onRemove: (slot: Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
) {
    val hasAny = fallbacks.isNotEmpty()
    var expanded by rememberSaveable(tagPrefix) { mutableStateOf(false) }
    var removingSlot by rememberSaveable(tagPrefix) { mutableStateOf<Int?>(null) }
    val c = MikaTheme.colors

    GroupCard {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .testTag("$tagPrefix-header")
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(if (hasAny) c.accent.copy(alpha = 0.12f) else c.neutralSoft, MikaShapes.Tile),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = if (hasAny) c.accent else c.ink3, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        title,
                        style = MikaType.BodyStrong,
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (hasAny) {
                        Box(
                            Modifier
                                .background(c.accent.copy(alpha = 0.12f), MikaShapes.Pill)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text(
                                stringResource(R.string.model_fallback_active_count, fallbacks.size),
                                style = MikaType.Micro.copy(fontWeight = FontWeight.SemiBold),
                                color = c.accent,
                                maxLines = 1,
                            )
                        }
                    }
                }
                val previewText = when {
                    expanded -> stringResource(R.string.model_fallback_collapse_hint)
                    hasAny -> {
                        val head = primary?.name?.substringAfterLast('/') ?: stringResource(R.string.model_fallback_primary_badge)
                        (listOf(head) + fallbacks.map { it.name.substringAfterLast('/') }).joinToString(" ➔ ")
                    }
                    else -> stringResource(R.string.model_fallback_empty_preview)
                }
                Text(
                    previewText,
                    Modifier.padding(top = 2.dp),
                    style = MikaType.Caption,
                    color = c.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                MikaIcons.Chevron,
                contentDescription = stringResource(if (expanded) R.string.collapse else R.string.expand),
                tint = c.ink3,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(if (expanded) 90f else 0f),
            )
        }

        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .background(c.fill, MikaShapes.Card)
                    .border(0.5.dp, c.edge, MikaShapes.Card)
                    .clip(MikaShapes.Card),
            ) {
                if (primary != null) {
                    ChainRow(
                        number = 1,
                        badge = stringResource(R.string.model_fallback_primary_badge),
                        badgeAccent = true,
                        item = primary,
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                    )
                }

                fallbacks.forEachIndexed { slot, item ->
                    Hairline(start = 14.dp)
                    val canUp = slot > 0
                    val canDown = slot < fallbacks.lastIndex
                    val moveUp = stringResource(R.string.model_fallback_move_up)
                    val moveDown = stringResource(R.string.model_fallback_move_down)
                    val remove = stringResource(R.string.model_fallback_remove_desc)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(slot) }
                            // Screen readers: up, down and remove are available on this row without hunting for the small buttons on the right
                            .semantics {
                                customActions = listOfNotNull(
                                    CustomAccessibilityAction(moveUp) { onMove(slot, slot - 1); true }.takeIf { canUp },
                                    CustomAccessibilityAction(moveDown) { onMove(slot, slot + 1); true }.takeIf { canDown },
                                    CustomAccessibilityAction(remove) { removingSlot = slot; true },
                                )
                            }
                            .testTag("$tagPrefix-$slot")
                            .padding(start = 14.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ChainRow(
                            number = slot + 2,
                            badge = stringResource(R.string.model_fallback_backup_badge, slot + 1),
                            badgeAccent = false,
                            item = item,
                            Modifier.weight(1f).padding(vertical = 7.dp),
                        )
                        Row {
                            // With only one backup there is nothing to move, so take no space
                            if (fallbacks.size > 1) {
                                ChainIconButton(
                                    MikaIcons.Up, stringResource(R.string.model_fallback_move_up_desc, slot + 1), canUp,
                                    Modifier.testTag("$tagPrefix-$slot-up"),
                                ) { onMove(slot, slot - 1) }
                                ChainIconButton(
                                    MikaIcons.Down, stringResource(R.string.model_fallback_move_down_desc, slot + 1), canDown,
                                    Modifier.testTag("$tagPrefix-$slot-down"),
                                ) { onMove(slot, slot + 1) }
                            }
                            ChainIconButton(MikaIcons.Close, remove, true, Modifier.testTag("$tagPrefix-$slot-remove")) {
                                removingSlot = slot
                            }
                        }
                    }
                }

                if (fallbacks.size < max) {
                    Hairline(start = 14.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(fallbacks.size) }
                            .testTag("$tagPrefix-${fallbacks.size}")
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .background(c.accent.copy(alpha = 0.08f), MikaShapes.Tile),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(MikaIcons.Plus, contentDescription = null, tint = c.accent, modifier = Modifier.size(15.dp))
                        }
                        Text(
                            if (fallbacks.isEmpty()) stringResource(R.string.model_fallback_add_first)
                            else stringResource(R.string.model_fallback_add_another, fallbacks.size + 1, max),
                            style = MikaType.BodyStrong,
                            color = c.accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    val pendingRemoveSlot = removingSlot
    if (pendingRemoveSlot != null && pendingRemoveSlot in fallbacks.indices) {
        MikaDialog(
            onDismissRequest = { removingSlot = null },
            title = { Text(stringResource(R.string.model_fallback_remove_title)) },
            text = { Text(removeBody(fallbacks[pendingRemoveSlot].name)) },
            confirmButton = {
                TextButton(onClick = {
                    removingSlot = null
                    onRemove(pendingRemoveSlot)
                }) {
                    Text(stringResource(R.string.model_fallback_remove_confirm), color = c.bad)
                }
            },
            dismissButton = {
                TextButton(onClick = { removingSlot = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (hasAny && expanded) FooterNote(footer)
}

/** Left half of a row: position badge + "Primary/Backup #n" + model name + source. */
@Composable
private fun ChainRow(number: Int, badge: String, badgeAccent: Boolean, item: FallbackChainItem, modifier: Modifier) {
    val c = MikaTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .size(28.dp)
                .background(if (badgeAccent) c.accent.copy(alpha = 0.14f) else c.surface, MikaShapes.Tile),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$number",
                style = MikaType.Caption.copy(fontWeight = FontWeight.Bold),
                color = if (badgeAccent) c.accent else c.ink2,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(badge, style = MikaType.Hint, color = if (badgeAccent) c.accent else c.ink3, maxLines = 1)
            Text(
                item.name,
                Modifier.padding(top = 1.dp),
                style = MikaType.BodyStrong,
                fontFamily = FontFamily.Monospace,
                color = if (item.missing) c.ink3 else c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.detail.isNotEmpty()) {
                Text(
                    item.detail,
                    Modifier.padding(top = 1.dp),
                    style = MikaType.Caption,
                    color = c.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Icon buttons on the right of a backup row: 48dp touch target, 16dp icon; faded and disabled when unavailable. */
@Composable
private fun ChainIconButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = MikaTheme.colors
    Box(
        modifier
            .size(48.dp)
            .clip(MikaShapes.Pill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) c.ink3 else c.ink3.copy(alpha = 0.35f),
            modifier = Modifier.size(16.dp),
        )
    }
}
