package com.wangzan101.mika.settings.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.task.ui.RunMetricsCopy
import com.wangzan101.mika.task.ui.TraceSettingsRows

/** An engineering switch: [id] is used as a test tag, and [change] computes the whole set of switches after toggling this one. */
data class DevSwitch(
    val id: String, val title: String, val subtitle: String, val checked: Boolean, val change: (Boolean) -> SpeedFlags,
)

/** The two groups of developer options: speed (verified, mostly on by default) and experimental (off by default). The switch values and storage ([SpeedFlags]) are unchanged. */
object DeveloperSwitches {
    fun speed(flags: SpeedFlags): List<DevSwitch> = listOf(
        DevSwitch("adaptive-vision", tr(R.string.dev_adaptive), tr(R.string.dev_adaptive_sub), flags.adaptiveVision) { flags.copy(adaptiveVision = it) },
        DevSwitch("inline-screen", tr(R.string.dev_inline), tr(R.string.dev_inline_sub), flags.inlineScreen) { flags.copy(inlineScreen = it) },
        DevSwitch("batch-steps", tr(R.string.dev_batch), tr(R.string.dev_batch_sub), flags.batchSteps) { flags.copy(batchSteps = it) },
        DevSwitch("deep-links", tr(R.string.dev_deep_links), tr(R.string.dev_deep_links_sub), flags.deepLinks) { flags.copy(deepLinks = it) },
        DevSwitch("light-steps", tr(R.string.dev_light), tr(R.string.dev_light_sub), flags.lightSteps) { flags.copy(lightSteps = it) },
    )

    fun experimental(flags: SpeedFlags): List<DevSwitch> = listOf(
        DevSwitch("replay", tr(R.string.dev_replay), tr(R.string.dev_replay_sub), flags.replay) { flags.copy(replay = it) },
        DevSwitch("parallel-tasks", tr(R.string.dev_parallel), tr(R.string.dev_parallel_sub), flags.parallelTasks) { flags.copy(parallelTasks = it) },
        DevSwitch("structured-compaction", tr(R.string.dev_compaction), tr(R.string.dev_compaction_sub), flags.structuredCompaction) { flags.copy(structuredCompaction = it) },
        DevSwitch("tables-at-birth", tr(R.string.dev_screen_tables), tr(R.string.dev_screen_tables_sub), flags.tablesAtBirth) { flags.copy(tablesAtBirth = it) },
    )
}

/** The "way of working" group: the global ask/autonomous switch for the task run page. */
object AutonomyCopy {
    val GROUP: String get() = tr(R.string.dev_group)
    val TITLE: String get() = tr(R.string.dev_autonomy)
    val SUBTITLE: String get() = tr(R.string.dev_autonomy_sub)
}

val DEVELOPER_NOTE: String get() = tr(R.string.dev_note)

/** The "vision grounding verified" flag of the current vision model: [model] is its model name. */
data class GroundingSetting(val model: String, val verified: Boolean)

object GroundingCopy {
    val GROUP: String get() = tr(R.string.cap_vision)
    val TITLE: String get() = tr(R.string.dev_grounding)

    fun subtitle(model: String) = tr(R.string.dev_grounding_sub, model)
}

/**
 * 设置 › 高级与其他 › 开发者选项:速度和实验两组开关;
 * 有看图模型时另有它的「看图定位已验证」([grounding] 为 null 时不显示);最后是「运行统计」的入口([onOpenRunStats] 为 null 时不显示)。
 * [scrollState] 由切页的一方给进来,从「运行统计」「设备目录」回来才停在原来的位置。
 */
@Composable
fun DeveloperOptionsScreen(
    speed: SpeedFlags,
    onSpeed: (SpeedFlags) -> Unit,
    onBack: () -> Unit,
    grounding: GroundingSetting? = null,
    onGrounding: (Boolean) -> Unit = {},
    onOpenRunStats: (() -> Unit)? = null,
    deviceFiles: Boolean = false,
    onDeviceFiles: (Boolean) -> Unit = {},
    onDeviceDirectories: (() -> Unit)? = null,
    scrollState: ScrollState = rememberScrollState(),
) {
    PageScaffold(tr(R.string.settings_developer), onBack, scrollState = scrollState) {
        GroupLabel(tr(R.string.dev_speed))
        SwitchGroup(DeveloperSwitches.speed(speed), onSpeed)
        GroupLabel(tr(R.string.dev_experiments))
        SwitchGroup(DeveloperSwitches.experimental(speed), onSpeed) {
            ListRow(null, tr(R.string.dev_device_files), note = tr(R.string.dev_device_files_sub), onClick = { onDeviceFiles(!deviceFiles) }) {
                MikaSwitch(deviceFiles, onDeviceFiles, Modifier.testTag("dev-device-files"))
            }
            if (onDeviceDirectories != null) {
                ListRow(null, tr(R.string.device_dirs_entry), onClick = onDeviceDirectories) { RowChevron() }
            }
        }
        if (grounding != null) {
            GroupLabel(GroundingCopy.GROUP)
            GroupCard {
                ListRow(null, GroundingCopy.TITLE, GroundingCopy.subtitle(grounding.model), first = true, onClick = { onGrounding(!grounding.verified) }) {
                    MikaSwitch(grounding.verified, onGrounding, Modifier.testTag("dev-grounding-verified"))
                }
            }
        }
        GroupLabel(tr(R.string.dev_run_log))
        GroupCard {
            TraceSettingsRows()
            if (onOpenRunStats != null) {
                ListRow(null, RunMetricsCopy.STATS_TITLE, onClick = onOpenRunStats) { RowChevron() }
            }
        }
        FooterNote(DEVELOPER_NOTE)
    }
}

@Composable
private fun SwitchGroup(switches: List<DevSwitch>, onSpeed: (SpeedFlags) -> Unit, extra: @Composable () -> Unit = {}) {
    GroupCard {
        switches.forEachIndexed { i, s ->
            // The whole row is clickable: the switch is small, the row is large
            ListRow(null, s.title, s.subtitle, first = i == 0, onClick = { onSpeed(s.change(!s.checked)) }) {
                MikaSwitch(s.checked, { onSpeed(s.change(it)) }, Modifier.testTag("dev-${s.id}"))
            }
        }
        extra()
    }
}
