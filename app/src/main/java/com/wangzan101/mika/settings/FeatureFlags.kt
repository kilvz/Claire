package com.wangzan101.mika.settings

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.capsule.CapsuleCardScale
import com.wangzan101.mika.capsule.CapsuleDock
import com.wangzan101.mika.capsule.CardDocking
import com.wangzan101.mika.capsule.CapsuleIdleOpacity
import com.wangzan101.mika.capsule.CapsuleMode
import com.wangzan101.mika.capsule.DockSide
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.cap.phone.OperatingPace
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.chat.AutoArchive
import com.wangzan101.mika.core.model.usage.ProactiveBudget
import com.wangzan101.mika.core.model.usage.TokenBudgetLimits
import com.wangzan101.mika.task.ParallelCapability
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalTime

/** Feature flags. Speed flags are stored in the format of [SpeedFlags.describe], and the evaluation runner can override them via parameters. */
class FeatureFlags(private val prefs: SharedPreferences) {
    fun scheduledQuietHours(): ScheduledQuietHours {
        val parts = prefs.getString(SCHEDULED_QUIET_HOURS, null)?.split(',')
        if (parts?.size != 3) return ScheduledQuietHours.DEFAULT
        return runCatching {
            ScheduledQuietHours(parts[0].toBooleanStrict(), LocalTime.parse(parts[1]), LocalTime.parse(parts[2]))
        }.getOrDefault(ScheduledQuietHours.DEFAULT)
    }

    fun setScheduledQuietHours(hours: ScheduledQuietHours) {
        prefs.edit().putString(SCHEDULED_QUIET_HOURS, "${hours.enabled},${hours.start},${hours.end}").apply()
    }

    fun personalStart(): Boolean = prefs.getBoolean(PERSONAL_START, false)

    fun setPersonalStart(on: Boolean) {
        prefs.edit().putBoolean(PERSONAL_START, on).apply()
    }

    /** Applies each default-value upgrade not done yet (see [SPEED_UPGRADES]) once; once upgraded, it stays off if the user manually turns it off. */
    fun speedFlags(): SpeedFlags {
        val flags = prefs.getString(SPEED_FLAGS, null)?.let { runCatching { SpeedFlags.parse(it) }.getOrNull() } ?: SpeedFlags.OFF
        val pending = SPEED_UPGRADES.filter { (key, _) -> !prefs.getBoolean(key, false) }
        if (pending.isEmpty()) return flags
        val upgraded = pending.fold(flags) { f, (_, upgrade) -> upgrade(f) }
        setSpeed(upgraded)
        return upgraded
    }

    fun setSpeed(flags: SpeedFlags) {
        val edit = prefs.edit().putString(SPEED_FLAGS, flags.describe())
        SPEED_UPGRADES.forEach { (key, _) -> edit.putBoolean(key, true) }
        edit.commit()
    }

    /** How many parallel tasks may run at once (user setting, 1–3); the real ceiling also depends on the device, see ParallelCapability. */
    fun parallelMax(): Int = prefs.getInt(PARALLEL_MAX, ParallelCapability.DEFAULT_MAX)

    fun setParallelMax(max: Int) {
        prefs.edit().putInt(PARALLEL_MAX, max).commit()
    }

    /** Maximum number of steps per task; null means unlimited. Unlimited by default, effective from the next task. */
    fun taskStepLimit(): Int? = prefs.getInt(TASK_STEP_LIMIT, 0).takeIf { it in 1..MAX_TASK_STEP_LIMIT }

    fun setTaskStepLimit(limit: Int?) {
        prefs.edit().putInt(TASK_STEP_LIMIT, (limit ?: 0).coerceIn(0, MAX_TASK_STEP_LIMIT)).commit()
    }

    /**
     * Maximum minutes a scheduled task may run per invocation (nobody is present, so nothing stops it if it gets stuck); null means unlimited. Unlimited by default and, like the step limit, effective from the next trigger.
     * Only the tiers in [TRIGGER_RUN_MINUTES] are accepted; any other stored value counts as unlimited.
     */
    fun triggerRunMinutes(): Int? = prefs.getInt(TRIGGER_RUN_LIMIT, 0).takeIf { it in TRIGGER_RUN_MINUTES }

    fun setTriggerRunMinutes(minutes: Int?) {
        prefs.edit().putInt(TRIGGER_RUN_LIMIT, minutes?.takeIf { it in TRIGGER_RUN_MINUTES } ?: 0).commit()
    }

    /**
     * Maximum cumulative minutes of sandbox commands in one task (work like installing dependencies or compiling easily exceeds it); only the tiers in [SANDBOX_BUDGET_MINUTES] are accepted,
     * when unset or another value is stored the default is used (matching [SANDBOX_BUDGET_MS]), effective from the next task.
     */
    fun sandboxBudgetMinutes(): Int =
        prefs.getInt(SANDBOX_BUDGET_LIMIT, 0).takeIf { it in SANDBOX_BUDGET_MINUTES } ?: DEFAULT_SANDBOX_BUDGET_MINUTES

    fun setSandboxBudgetMinutes(minutes: Int) {
        prefs.edit().putInt(SANDBOX_BUDGET_LIMIT, minutes.takeIf { it in SANDBOX_BUDGET_MINUTES } ?: 0).commit()
    }

    /**
     * Maximum size in MB of a single uploaded file (chat attachments, WeChat images, files attached to tasks sent from a computer); only the tiers in [ATTACHMENT_FILE_LIMIT_MB] are accepted,
     * when unset or another value is stored the default is used (matching Attachments.MAX_BYTES), and a change takes effect on the next import.
     */
    fun attachmentMaxMb(): Int =
        prefs.getInt(ATTACHMENT_FILE_LIMIT, 0).takeIf { it in ATTACHMENT_FILE_LIMIT_MB } ?: DEFAULT_ATTACHMENT_FILE_LIMIT_MB

    fun attachmentMaxBytes(): Long = attachmentMaxMb().toLong() shl 20

    fun setAttachmentMaxMb(mb: Int) {
        prefs.edit().putInt(ATTACHMENT_FILE_LIMIT, mb.takeIf { it in ATTACHMENT_FILE_LIMIT_MB } ?: 0).commit()
    }

    /**
     * Maximum size in GB one workspace (the conversation sandbox directory) may occupy; only the tiers in [WORKSPACE_LIMIT_GB] are accepted,
     * when unset or another value is stored the default is used (matching Attachments.WORKSPACE_MAX).
     */
    fun workspaceMaxGb(): Int =
        prefs.getInt(WORKSPACE_LIMIT, 0).takeIf { it in WORKSPACE_LIMIT_GB } ?: DEFAULT_WORKSPACE_LIMIT_GB

    fun workspaceMaxBytes(): Long = workspaceMaxGb().toLong() shl 30

    /** Size tiers shared by chat attachments and device-file imports; every read reflects the current settings. */
    fun attachmentLimits() = com.wangzan101.mika.cap.sandbox.AttachmentLimits(attachmentMaxBytes(), workspaceMaxBytes())

    fun setWorkspaceMaxGb(gb: Int) {
        prefs.edit().putInt(WORKSPACE_LIMIT, gb.takeIf { it in WORKSPACE_LIMIT_GB } ?: 0).commit()
    }

    /** Total reported input-plus-output tokens allowed per task; null means unlimited, effective from the next task. */
    fun taskTokenBudget(): Long? = prefs.getLong(TASK_TOKEN_BUDGET, 0L).takeIf(TokenBudgetLimits::valid)

    fun setTaskTokenBudget(limit: Long?) {
        prefs.edit().putLong(TASK_TOKEN_BUDGET, limit?.takeIf(TokenBudgetLimits::valid) ?: 0L).commit()
    }

    /** Reported tokens the proactive capability may use per day (local calendar day, summed across all providers); falls back to the default when unset or invalid, and there is no "unlimited". */
    fun proactiveDailyTokenBudget(): Long =
        ProactiveBudget.limitOf(prefs.getLong(PROACTIVE_DAILY_TOKEN_BUDGET, 0L))

    /** Values outside the [TokenBudgetLimits] range (including null) are stored as "unset", which reads back as the default. */
    fun setProactiveDailyTokenBudget(limit: Long?) {
        prefs.edit().putLong(PROACTIVE_DAILY_TOKEN_BUDGET, limit?.takeIf(TokenBudgetLimits::valid) ?: 0L).commit()
    }

    /** Keep the screen on while working; on by default, the user can turn it off. */
    fun keepScreenOn(): Boolean = prefs.getBoolean(KEEP_SCREEN_ON, true)

    fun setKeepScreenOn(on: Boolean) {
        prefs.edit().putBoolean(KEEP_SCREEN_ON, on).commit()
    }

    /** Hide this activity in Recents; changing it is synchronized with the system by RecentsExclusion. */
    fun excludeFromRecents(): Boolean = prefs.getBoolean(EXCLUDE_FROM_RECENTS, false)

    fun setExcludeFromRecents(on: Boolean): Boolean = prefs.edit().putBoolean(EXCLUDE_FROM_RECENTS, on).commit()

    fun showTaskCost(): Boolean = prefs.getBoolean(SHOW_TASK_COST, false)

    fun setShowTaskCost(on: Boolean) {
        prefs.edit().putBoolean(SHOW_TASK_COST, on).apply()
    }

    /** Mute the app being operated while working; on by default and the user can turn it off. */
    fun muteTarget(): Boolean = prefs.getBoolean(MUTE_TARGET, true)

    fun setMuteTarget(on: Boolean) {
        prefs.edit().putBoolean(MUTE_TARGET, on).commit()
    }

    /** Pace of operating inside apps: fast, standard (default), human-like (see [OperatingPace]). */
    fun operatingPace(): OperatingPace = OperatingPace.parse(prefs.getString(OPERATING_PACE, null)) ?: OperatingPace.DEFAULT

    fun setOperatingPace(pace: OperatingPace) {
        prefs.edit().putString(OPERATING_PACE, pace.name).commit()
    }

    /**
     * Per-app operating-pace overrides (package name → tier). When the list has never been initialized, apps marked careful in the built-in app knowledge are first seeded as "human-like"
     * (see [GuideCatalog.carefulApps]); after that the runtime only consults this list and the pace from app knowledge no longer participates.
     * A parse failure is treated as uninitialized (re-seeded on the next read).
     */
    fun appPaceOverrides(): Map<String, OperatingPace> {
        val stored = prefs.getString(APP_PACE_OVERRIDES, null)
        if (stored != null) {
            val parsed = runCatching { parseAppPaceOverrides(stored) }.getOrNull()
            if (parsed != null) return parsed
        }
        val seeded = GuideCatalog.loadBundled().carefulApps().associateWith { OperatingPace.HUMAN }
        setAppPaceOverrides(seeded)
        return seeded
    }

    /** Writes the whole map back (called after the settings page adds, removes or edits entries); an empty map is persisted too (the user deleted everything = explicitly no longer distinguishing by app, so do not re-seed). */
    fun setAppPaceOverrides(overrides: Map<String, OperatingPace>) {
        val json = overrides.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { (pkg, pace) ->
            "\"${pkg.replace("\\", "\\\\").replace("\"", "\\\"")}\":\"${pace.name}\""
        }
        prefs.edit().putString(APP_PACE_OVERRIDES, json).commit()
    }

    private fun parseAppPaceOverrides(json: String): Map<String, OperatingPace> =
        kotlinx.serialization.json.Json.parseToJsonElement(json).let { o ->
            buildMap {
                for ((k, v) in (o as kotlinx.serialization.json.JsonObject)) {
                    OperatingPace.parse((v as kotlinx.serialization.json.JsonPrimitive).content)?.let { pace -> put(k, pace) }
                }
            }
        }

    /** UI color scheme: follow the system, always light, always dark (see [ThemeMode]). The UI switches on the spot following [themeModeChanges], with no rebuild needed. */
    fun themeMode(): ThemeMode = ThemeMode.parse(prefs.getString(THEME_MODE, null))

    /** Current appearance value, re-emitted on every subsequent change (whether changed from the settings page or inside a task). */
    fun themeModeChanges(): Flow<ThemeMode> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == THEME_MODE) trySend(themeMode())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(themeMode())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(THEME_MODE, mode.name).commit()
    }

    /** Accent color (see [ThemeAccent]), blue by default. Like the appearance, it switches on the spot following [themeAccentChanges], with no rebuild needed. */
    fun themeAccent(): ThemeAccent = ThemeAccent.parse(prefs.getString(THEME_ACCENT, null))

    /** Current accent color, re-emitted on every subsequent change. */
    fun themeAccentChanges(): Flow<ThemeAccent> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == THEME_ACCENT) trySend(themeAccent())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(themeAccent())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun setThemeAccent(accent: ThemeAccent) {
        prefs.edit().putString(THEME_ACCENT, accent.name).commit()
    }

    /** How the background image is displayed (see [SkinSpec]); stored values outside the range are pulled back into range. Switches on the spot following [skinChanges], with no rebuild needed. */
    fun skin(): SkinSpec = SkinSpec(
        version = prefs.getLong(SKIN_VERSION, 0L),
        scrim = prefs.getInt(SKIN_SCRIM, SkinSpec.DEFAULT_SCRIM).coerceIn(SkinSpec.MIN_SCRIM, SkinSpec.MAX_SCRIM),
        blur = SkinBlur.parse(prefs.getString(SKIN_BLUR, null)),
        focus = prefs.getFloat(SKIN_FOCUS, 0f).coerceIn(-1f, 1f),
        accentFromImage = prefs.getBoolean(SKIN_ACCENT_FOLLOW, true),
        hue = if (prefs.contains(SKIN_HUE)) prefs.getFloat(SKIN_HUE, 0f) else null,
    )

    fun skinChanges(): Flow<SkinSpec> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in SKIN_KEYS) trySend(skin())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(skin())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun setSkin(spec: SkinSpec) {
        prefs.edit()
            .putLong(SKIN_VERSION, spec.version)
            .putInt(SKIN_SCRIM, spec.scrim.coerceIn(SkinSpec.MIN_SCRIM, SkinSpec.MAX_SCRIM))
            .putString(SKIN_BLUR, spec.blur.name)
            .putFloat(SKIN_FOCUS, spec.focus.coerceIn(-1f, 1f))
            .putBoolean(SKIN_ACCENT_FOLLOW, spec.accentFromImage)
            .also { e -> if (spec.hue == null) e.remove(SKIN_HUE) else e.putFloat(SKIN_HUE, spec.hue) }
            .commit()
    }

    /**
     * Allow Claire to change her own settings (see SelfConfig in core): models and keys, and deleting data, are not included.
     * Off by default; only when it is on do tasks have the settings_list and settings_set tools.
     */
    fun selfConfig(): Boolean = prefs.getBoolean(SELF_CONFIG, false)

    fun setSelfConfig(on: Boolean) {
        prefs.edit().putBoolean(SELF_CONFIG, on).commit()
    }

    /**
     * Allow Claire to operate her own UI (see SelfPort in core): open Claire's pages, open workspace files in the in-app viewer,
     * send notifications, write to the clipboard. Off by default; the model cannot change this switch.
     */
    fun selfOperate(): Boolean = prefs.getBoolean(SELF_OPERATE, false)

    fun setSelfOperate(on: Boolean) {
        prefs.edit().putBoolean(SELF_OPERATE, on).commit()
    }

    /**
     * Allow the user to interact directly with the virtual-screen mini window inside the conversation (tap / swipe / back) without moving the whole app to the main screen.
     * Off by default: when on, once the mini window is expanded, taps on the picture are really injected into the virtual screen, and a slip of the finger may hit the wrong spot in the task the AI is running.
     */
    fun pipOperate(): Boolean = prefs.getBoolean(PIP_OPERATE, false)

    fun setPipOperate(on: Boolean) {
        prefs.edit().putBoolean(PIP_OPERATE, on).commit()
    }

    /**
     * Allow external scripts to create scheduled tasks via broadcast (com.wangzan101.mika.SCHEDULE); off by default.
     * When on, requests are still rate-limited (a few per minute) and tagged with the source "external script" so incidents can be traced.
     */
    fun externalSchedule(): Boolean = prefs.getBoolean(EXTERNAL_SCHEDULE, false)

    fun setExternalSchedule(on: Boolean) {
        prefs.edit().putBoolean(EXTERNAL_SCHEDULE, on).commit()
    }

    /** Device-file import is an experimental feature, off by default, effective from the next task. */
    fun deviceFiles(): Boolean = prefs.getBoolean(DEVICE_FILES, false)

    fun setDeviceFiles(on: Boolean) {
        prefs.edit().putBoolean(DEVICE_FILES, on).commit()
    }

    /** Auto-archive: conversations without new messages for this many days are archived automatically; 0 disables it (default). */
    fun autoArchiveDays(): Int = prefs.getInt(AUTO_ARCHIVE_DAYS, 0).takeIf { it in AutoArchive.DAYS } ?: 0

    fun setAutoArchiveDays(days: Int) {
        prefs.edit().putInt(AUTO_ARCHIVE_DAYS, days.takeIf { it in AutoArchive.DAYS } ?: 0).commit()
    }

    /** Proactive capability (see [com.wangzan101.mika.proactive.ProactiveRuntime]): reads notifications from the selected apps; off by default. */
    fun proactive(): Boolean = prefs.getBoolean(PROACTIVE, false)

    fun setProactive(on: Boolean) {
        prefs.edit().putBoolean(PROACTIVE, on).commit()
    }

    /** Current value of the proactive switch, re-emitted on every subsequent change (the capsule only fetches the count of "worth reminding" items based on it, and the proactive capability is not created while it is off). */
    fun proactiveChanges(): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == PROACTIVE) trySend(proactive())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(proactive())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** Persona: on by default. The switch is kept so it can be turned off wholesale if problems appear (when off, the UI hides the entry point and tasks ignore the binding attached to a conversation). */
    fun persona(): Boolean = prefs.getBoolean(PERSONA, true)

    fun setPersona(on: Boolean) {
        prefs.edit().putBoolean(PERSONA, on).commit()
    }
    /** Floating capsule: off, shown while a task is running (default), always shown. */
    fun capsuleMode(): CapsuleMode = CapsuleMode.parse(prefs.getString(CAPSULE_MODE, null))

    fun setCapsuleMode(mode: CapsuleMode) {
        val previous = capsuleMode()
        val edit = prefs.edit().putString(CAPSULE_MODE, mode.name)
        val resume = if (mode != CapsuleMode.Off) mode else previous
        if (resume != CapsuleMode.Off) edit.putString(CAPSULE_RESUME_MODE, resume.name)
        edit.commit()
    }

    /** Quick Settings restores the user's previous non-off mode. */
    fun toggleCapsule() {
        val saved = CapsuleMode.parse(prefs.getString(CAPSULE_RESUME_MODE, null))
        setCapsuleMode(if (capsuleMode() == CapsuleMode.Off) saved.takeIf { it != CapsuleMode.Off } ?: CapsuleMode.Always else CapsuleMode.Off)
    }

    fun capsuleHideAtEdge(): Boolean = prefs.getBoolean(CAPSULE_HIDE_AT_EDGE, false)
    fun setCapsuleHideAtEdge(on: Boolean) { prefs.edit().putBoolean(CAPSULE_HIDE_AT_EDGE, on).commit() }
    fun capsuleHideAtEdgeChanges(): Flow<Boolean> = booleanChanges(CAPSULE_HIDE_AT_EDGE, ::capsuleHideAtEdge)

    fun capsuleHideFullscreen(): Boolean = prefs.getBoolean(CAPSULE_HIDE_FULLSCREEN, false)
    fun setCapsuleHideFullscreen(on: Boolean) { prefs.edit().putBoolean(CAPSULE_HIDE_FULLSCREEN, on).commit() }
    fun capsuleHideFullscreenChanges(): Flow<Boolean> = booleanChanges(CAPSULE_HIDE_FULLSCREEN, ::capsuleHideFullscreen)

    private fun booleanChanges(prefKey: String, value: () -> Boolean): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == null || changedKey == prefKey) trySend(value())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(value())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** Current value of the capsule switch, re-emitted on every subsequent change. */
    fun capsuleModeChanges(): Flow<CapsuleMode> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // key is null when all settings are cleared
            if (key == null || key == CAPSULE_MODE) trySend(capsuleMode())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(capsuleMode())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** Opacity of the capsule when idle; only 20/40/70/100 are accepted, default 40. */
    fun capsuleIdleOpacity(): CapsuleIdleOpacity = CapsuleIdleOpacity.parse(prefs.getInt(CAPSULE_IDLE_OPACITY, CapsuleIdleOpacity.DEFAULT.percent))

    fun setCapsuleIdleOpacity(opacity: CapsuleIdleOpacity) {
        prefs.edit().putInt(CAPSULE_IDLE_OPACITY, opacity.percent).commit()
    }

    fun capsuleIdleOpacityChanges(): Flow<CapsuleIdleOpacity> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == CAPSULE_IDLE_OPACITY) trySend(capsuleIdleOpacity())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(capsuleIdleOpacity())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** Scale tier for the whole expanded capsule card; only 85/100/115/130 are accepted, default 100 (standard). */
    fun capsuleCardScale(): CapsuleCardScale = CapsuleCardScale.parse(prefs.getInt(CAPSULE_CARD_SCALE, CapsuleCardScale.DEFAULT.percent))

    fun setCapsuleCardScale(scale: CapsuleCardScale) {
        prefs.edit().putInt(CAPSULE_CARD_SCALE, scale.percent).commit()
    }

    fun capsuleCardScaleChanges(): Flow<CapsuleCardScale> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == CAPSULE_CARD_SCALE) trySend(capsuleCardScale())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(capsuleCardScale())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /**
     * The user tapped "quit completely"; this lasts until Claire's UI is opened next time ([com.wangzan101.mika.MikaQuit]).
     * In the meantime the process may be restarted by the accessibility service: the capsule is not drawn, background services are not restarted, and no auto-recovery is attempted, until the user opens the app themselves.
     */
    fun quitRequested(): Boolean = prefs.getBoolean(QUIT_REQUESTED, false)

    fun setQuitRequested(on: Boolean) {
        prefs.edit().putBoolean(QUIT_REQUESTED, on).commit()
    }

    /** Current value of the quit flag, re-emitted on every subsequent change. */
    fun quitChanges(): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == QUIT_REQUESTED) trySend(quitRequested())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(quitRequested())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** Where the capsule docks: left or right side, and its height as a fraction of the movable range; sits in the top-right corner before it has ever been dragged. */
    fun capsuleDock(): CapsuleDock = CapsuleDock(
        if (prefs.getString(CAPSULE_SIDE, null) == DockSide.Left.name) DockSide.Left else DockSide.Right,
        prefs.getFloat(CAPSULE_HEIGHT, 0f).coerceIn(0f, 1f),
    )

    fun setCapsuleDock(dock: CapsuleDock) {
        prefs.edit().putString(CAPSULE_SIDE, dock.side.name).putFloat(CAPSULE_HEIGHT, dock.fraction).apply()
    }

    /**
     * Where the expanded card docks: the fraction of the movable range at which its top edge sits (see [com.wangzan101.mika.capsule.CardDocking.release]); before anything has been recorded, the tallest card sits in the middle.
     * The fraction previously recorded for the fixed-height card is exactly its top-edge fraction, so it is read the same way.
     */
    fun capsuleCardFraction(): Float = prefs.getFloat(CAPSULE_CARD_HEIGHT, CardDocking.DEFAULT_FRACTION).coerceIn(0f, 1f)

    fun setCapsuleCardFraction(fraction: Float) {
        prefs.edit().putFloat(CAPSULE_CARD_HEIGHT, fraction.coerceIn(0f, 1f)).apply()
    }

    companion object {
        const val PREFS = "feature_flags"
        private const val SCHEDULED_QUIET_HOURS = "scheduled_quiet_hours"
        /** Maximum value that can be set for the per-task step limit; 0/null means unlimited. */
        const val MAX_TASK_STEP_LIMIT = 500
        /** Selectable tiers (in minutes) for the maximum scheduled-task run time. */
        val TRIGGER_RUN_MINUTES = listOf(15, 30, 60, 120)
        /** Selectable tiers (in minutes) for the cumulative sandbox-command time per task. */
        val SANDBOX_BUDGET_MINUTES = listOf(30, 60, 120, 240)
        /** Used when the cumulative sandbox-command time per task is unset (or an out-of-tier value was stored); matches core's [SANDBOX_BUDGET_MS]. */
        val DEFAULT_SANDBOX_BUDGET_MINUTES = (com.wangzan101.mika.core.cap.sandbox.SANDBOX_BUDGET_MS / 60_000L).toInt()
        /** Selectable tiers (in MB) for the size of a single uploaded file. */
        val ATTACHMENT_FILE_LIMIT_MB = listOf(200, 512, 1024, 2048)
        /** Used when the single-file limit is unset (or an out-of-tier value was stored); matches Attachments.MAX_BYTES. */
        val DEFAULT_ATTACHMENT_FILE_LIMIT_MB = (com.wangzan101.mika.cap.sandbox.Attachments.MAX_BYTES shr 20).toInt()
        /** Selectable tiers (in GB) for the space of a single workspace. */
        val WORKSPACE_LIMIT_GB = listOf(4, 8, 16, 32)
        /** Used when the workspace limit is unset (or an out-of-tier value was stored); matches Attachments.WORKSPACE_MAX. */
        val DEFAULT_WORKSPACE_LIMIT_GB = (com.wangzan101.mika.cap.sandbox.Attachments.WORKSPACE_MAX shr 30).toInt()
        private const val SPEED_FLAGS = "speed_flags"
        /**
         * Upgrades to the speed-flag defaults, each applied once in order (the key records whether it has been done): v2 turns on tiered screen reading, continuous operations and on-demand image viewing;
         * v3 turns on experience replay (routes are only remembered after learning switched to judging by outcomes); v4 turns on splitting work between large and small models (idles when no lightweight model is configured, saves money when one is);
         * v5 turns on compressing UI elements into tables (repeated fields are compressed into columns on the first screen, saving tokens).
         */
        private val SPEED_UPGRADES: List<Pair<String, (SpeedFlags) -> SpeedFlags>> = listOf(
            "speed_defaults_applied_v2" to { f -> f.copy(inlineScreen = true, batchSteps = true, adaptiveVision = true) },
            "speed_defaults_applied_v3" to { f -> f.copy(replay = true) },
            "speed_defaults_applied_v4" to { f -> f.copy(lightSteps = true) },
            "speed_defaults_applied_v5" to { f -> f.copy(tablesAtBirth = true) },
        )
        private const val PARALLEL_MAX = "parallel_max"
        private const val TASK_STEP_LIMIT = "task_step_limit"
        private const val TRIGGER_RUN_LIMIT = "trigger_run_limit_minutes"
        private const val SANDBOX_BUDGET_LIMIT = "sandbox_budget_limit_minutes"
        private const val ATTACHMENT_FILE_LIMIT = "attachment_file_limit_mb"
        private const val WORKSPACE_LIMIT = "workspace_limit_gb"
        private const val TASK_TOKEN_BUDGET = "task_token_budget"
        private const val PROACTIVE_DAILY_TOKEN_BUDGET = "proactive_daily_token_budget"
        private const val KEEP_SCREEN_ON = "keep_screen_on"
        private const val EXCLUDE_FROM_RECENTS = "exclude_from_recents"
        private const val SHOW_TASK_COST = "show_task_cost"
        private const val MUTE_TARGET = "mute_target"
        private const val OPERATING_PACE = "operating_pace"
        private const val APP_PACE_OVERRIDES = "app_pace_overrides"
        private const val AUTO_ARCHIVE_DAYS = "auto_archive_days"
        private const val SELF_CONFIG = "self_config"
        private const val SELF_OPERATE = "self_operate"
        private const val DEVICE_FILES = "device_files"
        private const val PIP_OPERATE = "pip_operate"
        private const val EXTERNAL_SCHEDULE = "external_schedule"
        private const val PROACTIVE = "proactive"
        private const val PERSONA = "persona"
        private const val THEME_MODE = "theme_mode"
        private const val PERSONAL_START = "personal_start"
        private const val THEME_ACCENT = "theme_accent"
        private const val SKIN_VERSION = "skin_version"
        private const val SKIN_SCRIM = "skin_scrim"
        private const val SKIN_BLUR = "skin_blur"
        private const val SKIN_FOCUS = "skin_focus"
        private const val SKIN_ACCENT_FOLLOW = "skin_accent_follow"
        private const val SKIN_HUE = "skin_hue"
        private val SKIN_KEYS = setOf(SKIN_VERSION, SKIN_SCRIM, SKIN_BLUR, SKIN_FOCUS, SKIN_ACCENT_FOLLOW, SKIN_HUE)
        private const val CAPSULE_MODE = "capsule_mode"
        private const val CAPSULE_RESUME_MODE = "capsule_resume_mode"
        private const val CAPSULE_HIDE_AT_EDGE = "capsule_hide_at_edge"
        private const val CAPSULE_HIDE_FULLSCREEN = "capsule_hide_fullscreen"
        private const val CAPSULE_IDLE_OPACITY = "capsule_idle_opacity"
        private const val CAPSULE_CARD_SCALE = "capsule_card_scale"
        private const val QUIT_REQUESTED = "quit_requested"
        private const val CAPSULE_SIDE = "capsule_side"
        private const val CAPSULE_HEIGHT = "capsule_height"
        private const val CAPSULE_CARD_HEIGHT = "capsule_card_height"

        fun get(context: Context) = FeatureFlags(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}
