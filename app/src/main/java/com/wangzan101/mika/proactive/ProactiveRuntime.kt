package com.wangzan101.mika.proactive

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.format.DateUtils
import android.util.Log
import com.wangzan101.mika.R
import com.wangzan101.mika.core.proactive.CatchUp
import com.wangzan101.mika.core.proactive.GrantResult
import com.wangzan101.mika.core.proactive.ListenerGrant
import com.wangzan101.mika.core.proactive.LiveUpdates
import com.wangzan101.mika.core.proactive.NoticeDedup
import com.wangzan101.mika.core.proactive.NoticeFilter
import com.wangzan101.mika.core.proactive.NoticeHints
import com.wangzan101.mika.core.proactive.NoticeInbox
import com.wangzan101.mika.core.proactive.LearnLevel
import com.wangzan101.mika.core.proactive.SourceTier
import com.wangzan101.mika.core.proactive.NoticeRecord
import com.wangzan101.mika.core.proactive.NoticeShapes
import com.wangzan101.mika.core.proactive.NoticeView
import com.wangzan101.mika.core.proactive.NoticeSource
import com.wangzan101.mika.core.proactive.NoticeTalk
import com.wangzan101.mika.core.proactive.TriageConsent
import com.wangzan101.mika.core.proactive.TriageTarget
import com.wangzan101.mika.core.device.shell.ShellCommandRunner
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.KeystoreKeyCipher
import com.wangzan101.mika.settings.ui.classifierLanguage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The proactive capability (entry on the settings home page, off by default): reads the notifications of the Apps the user selected, filters
 * and deduplicates them, and puts them into an in-memory inbox. Once the user has confirmed which provider and which model notification text may
 * be sent to (a lightweight one, not the main model): [TriageRunner] judges each App's batches as ignore, note or worth a reminder, with results
 * kept in memory only; [LearnRunner] distills topics, contacts and message digests at each level from them, encrypted on this device. Before
 * that confirmation, or when the provider and model actually used differ from the confirmed ones, nothing is sent.
 *
 * Do not read while the screen is off or locked: when locked, record the time and ask the system to unbind the listener; after unlocking, ask the system to rebind, and on connection catch up on what was posted while locked and is still in the notification shade.
 * When a rebind was requested but the listener did not connect (the system itself failed to bind it, so the request had no effect), make the system register it again, see [ListenerReconnect].
 * Lock state is watched by [ProactiveLockWatch]: broadcasts only mean "look again", and the state is read from the system each time; nothing is scheduled while the screen is off, so the phone does not wake for this feature.
 * All handling runs on one serial coroutine; system callbacks only post work to it.
 */
class ProactiveRuntime(
    context: Context,
    private val flags: FeatureFlags,
    val allowed: NoticeAllowStore,
    /** The current state of "App access"; when it cannot be read the whole proactive capability is suspended (see [AppGate]). */
    private val access: () -> AppGate,
    /** Emits once whenever "App access" changes (the current value is given first on collection): after a disable, what was already captured is dropped too; when it goes from unreadable to readable, everything is re-checked. */
    private val accessChanges: Flow<AppGate>,
    /** The shell command channel used by "grant via the Claire assistant"; obtained only when the user taps the button, and phone capabilities are not touched while the feature is off. */
    private val shellRunner: () -> ShellCommandRunner,
    /** The model, usage and "a task is running" state the decision pass needs; untouched while the decision pass is off. */
    private val triageHooks: TriageHooks,
) {
    private val app = context.applicationContext
    private val prefs = ProactivePrefs(app.getSharedPreferences(ProactivePrefs.FILE, Context.MODE_PRIVATE))
    // Apps chosen "do not read" last time whose list file could not be written: apply them first, then do the rest (no step below reads the list before that)
    init { AllowEdits.replay(allowed, prefs) }
    private val power = app.getSystemService(PowerManager::class.java)
    private val keyguard = app.getSystemService(KeyguardManager::class.java)
    private val component = ComponentName(app, NoticeListenerService::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val lock = ProactiveLockWatch({ ProactiveLockWatch.Reading(power.isInteractive, keyguard.isKeyguardLocked) }, scope)

    val inbox = NoticeInbox()
    private val dedup: NoticeDedup = NoticeDedup.decode(prefs.dedup)
    /** Status displays that Apps repost over and over (navigation prompts and the like) are recognized and not captured; in memory only. */
    private val live = LiveUpdates()
    private val policy = ProactiveLockPolicy(prefs.lastLockedAtMs)

    private val _enabled = MutableStateFlow(false)
    /** The master switch (available in this build and turned on). The decision pass and learning look only at it and [consent]. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _consent = MutableStateFlow(prefs.consent)
    /** The provider and model the user consented to send notification text to (shared by the decision pass and learning); without it nothing is sent. */
    val consent: StateFlow<TriageConsent?> = _consent.asStateFlow()
    private val _triageLimit = MutableStateFlow(flags.proactiveDailyTokenBudget())
    /** The proactive capability's daily token limit. */
    val triageLimit: StateFlow<Long> = _triageLimit.asStateFlow()
    /** Shared by the decision pass and learning: only one proactive background request at a time. */
    private val requests = RequestSlot()
    private val triage = TriageRunner(
        inbox, triageHooks,
        on = { _enabled.value },
        consent = { _consent.value },
        budgetLimit = flags::proactiveDailyTokenBudget,
        language = ::triageLanguage,
        log = { Log.i(TAG, it) },
        surfaceCap = { _learnLevel.value.surfaceCap },
        suspended = { !access().known },
        slot = requests,
    )
    val triageState = triage.state

    private val _learnOptOut = MutableStateFlow(prefs.learnOptOut)
    /** Apps whose source tier is "low": their notifications are read, but nothing is learned from them. */
    val learnOptOut: StateFlow<Set<String>> = _learnOptOut.asStateFlow()
    private val _learnSensitive = MutableStateFlow(prefs.learnSensitive)
    /** The "sensitive-category inference" switch; off by default. */
    val learnSensitive: StateFlow<Boolean> = _learnSensitive.asStateFlow()
    private val _learnLevel = MutableStateFlow(LearnLevel.ofWire(prefs.learnLevel))
    /** Aggressiveness (the most conservative level by default). */
    val learnLevel: StateFlow<LearnLevel> = _learnLevel.asStateFlow()
    private val _learnHigh = MutableStateFlow(prefs.learnHigh)
    /** Apps whose source tier is set to "high". */
    val learnHigh: StateFlow<Set<String>> = _learnHigh.asStateFlow()
    private val _learnDigest = MutableStateFlow(prefs.learnDigest)
    /** The "daily notification digest" switch (off by default): while on, raw notifications are stored encrypted on this device for at most two days. */
    val learnDigest: StateFlow<Boolean> = _learnDigest.asStateFlow()
    private val learn = LearnRunner(
        triageHooks,
        on = { _enabled.value },
        consent = { _consent.value },
        budgetLimit = flags::proactiveDailyTokenBudget,
        language = ::triageLanguage,
        eligible = { pkg -> BlockedApps.learnEligible(pkg, allowed.current(), _learnOptOut.value, access().blocked) },
        suspended = { !access().known },
        slot = requests,
        sensitive = { _learnSensitive.value },
        store = TopicPoolFile(app.filesDir, KeystoreKeyCipher(TopicPoolFile.KEY_ALIAS)),
        digest = { _learnDigest.value },
        journalStore = NoticeJournalFile(app.filesDir, KeystoreKeyCipher(NoticeJournalFile.KEY_ALIAS)),
        digestStore = DigestFile(app.filesDir, KeystoreKeyCipher(DigestFile.KEY_ALIAS)),
        appLabel = { com.wangzan101.mika.task.ConfirmationNotifier.appLabel(app, it) },
        contactStore = ContactPoolFile(app.filesDir, KeystoreKeyCipher(ContactPoolFile.KEY_ALIAS)),
        purges = prefs,
        log = { Log.i(TAG, it) },
    )
    val learnState = learn.state
    val learnTopics = learn.topics
    val learnTopicsBlocked = learn.topicsBlocked
    val learnDigestRemoved = learn.digestRemoved
    val learnDigestRemovedDays = learn.digestRemovedDays
    val learnContactsView = learn.contactsView
    /** The sentences accumulated by the daily notification digest (segmented digests). */
    val learnDigestNotes = learn.digestNotes
    /** The daily digests, newest first. */
    val learnDigestDays = learn.digestDays
    val learnDigestWeeks = learn.digestWeeks
    val learnDigestMonths = learn.digestMonths

    @Volatile private var service: NoticeListenerService? = null
    private val connected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = connected.asStateFlow()

    /** Whether the "App access" settings can be read (used by the status card on the settings page; accurate even when not watching). When they cannot be read the whole proactive capability is suspended. */
    val accessKnown: Flow<Boolean> = accessChanges.map { it.known }.distinctUntilChanged()
    fun accessKnownNow(): Boolean = access().known
    /** The watch that only exists while running (feature on and not in "fully exited"): lock broadcasts and state collection. */
    private var watching: Job? = null
    private var receiver: BroadcastReceiver? = null
    /** The check after a rebind request that waits to see whether it connected (see [reconnectLater]). */
    private var reconnecting: Job? = null

    /** The master switch: the entry is on the settings home page, visible and switchable in every build. */
    private fun wanted(): Boolean = flags.proactive()

    /**
     * Called once when the feature is on and the process starts, or the user returns from "fully exited" (MikaApp calls it only while the
     * switch is on). With [paused] true (during a full exit) it only aligns the component state with the switch and does not start watching.
     */
    fun start(paused: Boolean) {
        val on = wanted()
        _enabled.value = on
        if (on) enableComponent()
        if (on && !paused) scope.launch { begin() }
    }

    /**
     * Called on "full exit": stop watching and ask the system to unbind; the switch and the whitelist are untouched. If the system later starts
     * the process for another service and binds the listener again, [onConnected] sees there is no watch and unbinds again; when the user
     * reopens Claire, [start] restores it.
     */
    fun pause() {
        scope.launch {
            end()
            unbind()
        }
    }

    /**
     * The master switch on the settings page: the only way the switch is changed. On turning on, [target] is the provider and model shown to
     * the user in the confirmation dialog, and consent is recorded against it (null when no model is configured: start capturing with the
     * switch on, send nothing, and confirm on the page once a model is configured). On turning off, consent is cleared as well: turning it on
     * again requires a new confirmation. Turning it off does not delete what has been digested (topics, contacts, digests at each level): the
     * user clears those; raw notifications and unprocessed in-memory items are cleared.
     */
    fun setEnabled(on: Boolean, target: TriageTarget? = null) {
        flags.setProactive(on)
        setConsent(TriageConsent.afterSwitch(on, target, _consent.value))
        _enabled.value = on
        scope.launch {
            if (on) {
                // Nothing that was already piling up in the shade before it was turned on is captured
                prefs.enabledAtMs = System.currentTimeMillis()
                enableComponent()
                begin()
            } else {
                // Turning it off only asks the system to unbind; the component is not disabled: on a real device (Android 16) disabling the
                // component revokes notification access, and turning it on again would require the user to grant it anew. After unbinding the
                // system sends no more notifications; if the process restarts and the system rebinds, [onConnected] unbinds again.
                end()
                unbind()
                inbox.clear()
                triage.clear()
                learn.clear()
                dedup.clear()
                live.clear()
                prefs.clearDedup()
            }
        }
    }

    /**
     * Whether Claire has notification access in the system settings. It asks the notification service itself rather than reading the
     * `enabled_notification_listeners` setting: on a real device that setting keeps the old value after the access has been revoked.
     */
    fun authorized(): Boolean = runCatching {
        app.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
    }.getOrDefault(false)

    private val granting = Mutex()

    /**
     * Called after the user taps "grant via the Claire assistant" on the proactive page and confirms: grants the listener notification access
     * through the built-in assistant (or a paired connection) with shell identity. It is only triggered by user action, never called
     * automatically in the background, and it runs to completion even if the user leaves the page (a successful grant needs a rebind). Only one
     * runs at a time (later ones queue; if access is already granted by the time they run, they count as success). Once it takes effect, the
     * system is asked to rebind along the unlock path, with no need to leave the page. The log records only the result category and truncated
     * command output, never notification content.
     */
    suspend fun grantViaHelper(): GrantResult = execScope.async { granting.withLock { grantLocked() } }.await()

    private suspend fun grantLocked(): GrantResult {
        val command = ListenerGrant.command(component.packageName, component.className)
        val r = ListenerGrant.run(
            command,
            // Calls through the command channel block and ignore cancellation: run them on a separate coroutine so a timeout only abandons the wait without having to wait for them to return
            exec = { cmd -> execScope.async { shellRunner().exec(cmd) }.await() },
            authorized = ::authorized,
            ensureEnabled = ::enableComponent,
            onGranted = { scope.launch { if (watching != null && !connected.value) run(listOf(ProactiveLockPolicy.Action.Rebind)) } },
        )
        when (r) {
            is GrantResult.Refused -> Log.i(TAG, "helper grant refused: ${r.output}")
            else -> Log.i(TAG, "helper grant: ${r.javaClass.simpleName}")
        }
        return r
    }

    private val execScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- Decision pass ----

    /** The provider and model the decision pass will actually use; null when no model is configured. Only fetched by this page while the proactive capability is on. */
    fun triageTarget(): TriageTarget? = triageHooks.target()

    /**
     * The user confirmed in the dialog that notifications may be sent to [target] (master switch on but not yet consented, or after the
     * provider or model changed): consent is recorded against it, and the decision pass and learning carry on.
     */
    fun confirmModel(target: TriageTarget) {
        setConsent(TriageConsent.of(target))
        scope.launch { triage.poke(); learn.poke() }
    }

    private fun setConsent(consent: TriageConsent?) {
        prefs.consent = consent
        _consent.value = consent
    }

    /**
     * Whether this App's notifications are read and, if so, at which tier: [tier] null means do not read (remove it from the list and reset
     * its tier); otherwise add it to the list and set this tier. When it is not read, or set to "low", its captured events, stored raw
     * notifications and the sentences in digests at each level that were based on it are removed too — this also runs while the master switch is off.
     *
     * Returns whether the list was saved. When it was not saved (storage full or the like): additions are not added, and tiers and stored data
     * are untouched; "do not read" still takes effect and the deletions still happen (that is what the user asked for, regardless of whether the
     * file was written), and it is recorded to be redone after a restart. The page uses this to ask the user to try again.
     */
    fun setAppReading(packageName: String, tier: SourceTier?): Boolean {
        val saved = AllowEdits.set(allowed, prefs, packageName, tier != null)
        if (!saved && tier != null) return false
        storeTier(packageName, tier ?: SourceTier.Mid)
        scope.launch { if (tier == null || tier == SourceTier.Low) learn.forgetApps(setOf(packageName)) else learn.poke() }
        return saved
    }

    /** The "sensitive-category inference" switch; when turning it off, [deleteExisting] true deletes the sensitive topics already stored (otherwise they are kept but no more are added). */
    fun setLearnSensitive(on: Boolean, deleteExisting: Boolean = false) {
        prefs.learnSensitive = on
        _learnSensitive.value = on
        scope.launch { if (!on && deleteExisting) learn.deleteSensitive() }
    }

    /**
     * The "derived from notifications" section this task should carry (the daily digest and what is known about the user, where eligible);
     * null while the proactive capability or learning is off, at the most conservative level, or when nothing qualifies. It reads what has been
     * accumulated and does not call the model; read on the serial coroutine.
     */
    suspend fun observedForTasks(): String? {
        if (!FeatureFlags.get(app).proactive()) return null
        // Starting a task must not be held up by the proactive serial coroutine: if it is busy (reading or writing files), wait a moment, and if it does not come back in time, carry nothing this time
        return kotlinx.coroutines.withTimeoutOrNull(OBSERVED_WAIT_MS) { scope.async { learn.observed(_learnLevel.value, ::learnTier) }.await() }
    }

    /**
     * The source tasks use to read the last two days of notifications (the notices_read tool). If any of the proactive capability, learning or
     * "daily notification digest" is off, or the aggressiveness is the most conservative level: null, and the tool does not appear. Reading
     * filters again by the switches and tiers as of that moment (the user may have changed settings while the task is running).
     */
    fun noticeSource(): NoticeSource? {
        if (!tasksMayReadNotices(_enabled.value && FeatureFlags.get(app).proactive(), _learnDigest.value, _learnLevel.value)) return null
        return object : NoticeSource {
            override val zone: java.time.ZoneId get() = java.time.ZoneId.systemDefault()
            override fun appLabel(packageName: String) = com.wangzan101.mika.task.ConfirmationNotifier.appLabel(app, packageName)
            // Waited too long, the stored copy cannot be read, or it was turned off while the task runs: null, and the tool reports a read failure (not "nothing was received")
            override suspend fun read(fromMs: Long, toMs: Long): NoticeView? =
                kotlinx.coroutines.withTimeoutOrNull(NOTICES_WAIT_MS) {
                    scope.async { learn.notices(fromMs, toMs, _learnLevel.value, ::learnTier) }.await()
                }
            override suspend fun digests(level: com.wangzan101.mika.core.proactive.DigestLevel): com.wangzan101.mika.core.proactive.DigestsView? =
                kotlinx.coroutines.withTimeoutOrNull(NOTICES_WAIT_MS) {
                    scope.async { learn.digests(level, _learnLevel.value, ::learnTier) }.await()
                }
        }
    }

    /** Aggressiveness: this only swaps a set of thresholds; what has been accumulated is untouched. */
    fun setLearnLevel(level: LearnLevel) {
        prefs.learnLevel = level.wire
        _learnLevel.value = level
    }

    /** This App's source tier: "low" when in "do not learn from it", "high" when raised, "medium" otherwise. */
    fun learnTier(packageName: String): SourceTier = when (packageName) {
        in _learnOptOut.value -> SourceTier.Low
        in _learnHigh.value -> SourceTier.High
        else -> SourceTier.Mid
    }

    /** Records the tier (the state flow changes first; the serial coroutine catches up afterwards and reads the updated value). */
    private fun storeTier(packageName: String, tier: SourceTier) {
        val high = if (tier == SourceTier.High) _learnHigh.value + packageName else _learnHigh.value - packageName
        prefs.learnHigh = high
        _learnHigh.value = high
        val low = if (tier == SourceTier.Low) _learnOptOut.value + packageName else _learnOptOut.value - packageName
        prefs.learnOptOut = low
        _learnOptOut.value = low
    }

    /** The "daily notification digest" switch; turning it off deletes the stored raw notifications too (sentences already digested and the digests at each level are kept); done on the serial coroutine. */
    fun setLearnDigest(on: Boolean) {
        prefs.learnDigest = on
        _learnDigest.value = on
        scope.launch { learn.poke() }
    }

    /** Actions from the learning page (loading the topic pool, deleting a topic and the like) are handed to the serial coroutine. */
    internal fun onLearn(block: LearnRunner.() -> Unit) {
        scope.launch { learn.block() }
    }

    /** For the page: which notifications a segmented digest sentence was written from. Waiting too long (the serial coroutine is busy reading or writing files) counts as unreadable. */
    internal suspend fun learnRaw(note: com.wangzan101.mika.core.proactive.DigestNote): RawNotices =
        kotlinx.coroutines.withTimeoutOrNull(NOTICES_WAIT_MS) { scope.async { learn.rawFor(note) }.await() } ?: RawNotices.Unavailable

    fun retryTriage() {
        scope.launch { triage.retryFailed() }
    }

    fun setTriageBudget(limit: Long) {
        flags.setProactiveDailyTokenBudget(limit)
        _triageLimit.value = flags.proactiveDailyTokenBudget()
        scope.launch { triage.budgetChanged(); learn.budgetChanged() }
    }

    /** Tokens used and requests made by the proactive capability today. */
    fun triageUsage(): Pair<Long, Long> = triageHooks.tokensToday() to triageHooks.callsToday()

    fun usageVersion(): StateFlow<Long> = triageHooks.usageVersion()

    /** Reasons are written in the UI language (the same wording as App classification). */
    private fun triageLanguage(): String {
        val tag = runCatching { AppLanguage.current(app).tag }.getOrNull()
        val locale = tag?.let(java.util.Locale::forLanguageTag) ?: app.resources.configuration.locales[0]
        return classifierLanguage(locale)
    }

    // ---- Callbacks forwarded from the service ----

    internal fun onConnected(s: NoticeListenerService) = scope.launch {
        service = s
        connected.value = true
        if (watching == null) {
            // The feature is off, or the system started the process during a full exit: do not read, ask the system to unbind
            unbind()
            return@launch
        }
        run(policy.connected(readLocked(), System.currentTimeMillis()))
    }

    internal fun onDisconnected(s: NoticeListenerService) = scope.launch {
        if (service === s) {
            service = null
            connected.value = false
        }
    }

    internal fun onPosted(sbn: StatusBarNotification) = scope.launch {
        if (watching == null) return@launch
        if (readLocked()) {
            run(policy.ignoredWhileLocked(sbn.postTime, connected.value))
            return@launch
        }
        ingest(sbn)
    }

    internal fun onSeen(key: String, reason: Int) = scope.launch {
        hintBoard.dismissed(key)
        inbox.remove(key)
        learn.removed(key, reason)
        // The user dealt with this one: the next one under the same key starts counting from scratch and is not taken as "the same one reposting" (see [LiveUpdates.ended])
        live.ended(key)
    }

    // ---- Internals ----

    private fun begin() {
        if (watching != null) return
        val r = object : BroadcastReceiver() {
            // Broadcasts arrive on the main thread: hand them to the serial coroutine to read, so they never race with polling and [readLocked]
            override fun onReceive(context: Context, intent: Intent) {
                scope.launch { lock.refresh() }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(r, filter)
        }
        receiver = r
        lock.start()
        var handled = lock.locked.value
        run(policy.start(handled, connected.value))
        watching = scope.launch {
            // Only act on values different from the last: changes that happen after watching starts but before collection begins are not missed either
            launch { lock.locked.collect { locked ->
                if (locked == handled) return@collect
                handled = locked
                run(if (locked) policy.locked(System.currentTimeMillis(), connected.value) else policy.unlocked(connected.value))
            } }
            // For an App that was unchecked, its captured records are removed too
            launch {
                allowed.state.collect { set ->
                    inbox.removePackages { it in set }
                    // The decision batch in flight for an unchecked App is cancelled too (the same as disabling it under "App access")
                    triage.dropApps { it in set }
                    learn.poke()
                }
            }
            // For an App disabled under "App access", its captured records, pending decisions and pending learning are removed and the request
            // in flight cancelled. When those settings cannot be read nothing is deleted (we do not know what is disabled); the decision pass and
            // learning are only re-evaluated: they stop the request in flight and wait
            launch { accessChanges.collect { gate -> BlockedApps.accessChanged(gate, inbox, triage, learn) } }
            // The decision pass runs only while watching; it shares the same lock state as the watch
            launch { triage.watch(lock.locked) { readLocked() } }
            launch { learn.watch(lock.locked) { readLocked() } }
        }
    }

    private fun end() {
        reconnecting?.cancel()
        reconnecting = null
        watching?.cancel()
        watching = null
        lock.stop()
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver = null
    }

    private fun readLocked(): Boolean {
        lock.refresh()
        return lock.locked.value
    }

    private fun run(actions: List<ProactiveLockPolicy.Action>) {
        for (a in actions) when (a) {
            is ProactiveLockPolicy.Action.PersistLockedAt -> prefs.lastLockedAtMs = a.atMs
            ProactiveLockPolicy.Action.Unbind -> unbind()
            ProactiveLockPolicy.Action.Rebind -> {
                runCatching { NotificationListenerService.requestRebind(component) }
                    .onFailure { Log.i(TAG, "rebind refused: ${it.javaClass.simpleName}") }
                reconnectLater()
            }
            ProactiveLockPolicy.Action.CatchUp -> catchUp()
        }
    }

    /**
     * Look again a moment after a rebind request: if it still has not connected, the system most likely failed to bind it itself (see [ListenerReconnect]), so the request had no effect,
     * so switch to unbinding by component name and then rebinding. Each rebind request gets this one retry; if it still fails, wait for the next unlock or for Claire to be opened.
     */
    private fun reconnectLater() {
        reconnecting?.cancel()
        reconnecting = scope.launch {
            delay(ListenerReconnect.WAIT_MS)
            val step = ListenerReconnect.next(watching != null, readLocked(), connected.value, authorized(), Build.VERSION.SDK_INT)
            // The second condition is the same as in [ListenerReconnect.next]; it is repeated here only so the compile-time version check can see it
            if (step != ListenerReconnect.Step.Reregister || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return@launch
            runCatching {
                NotificationListenerService.requestUnbind(component)
                NotificationListenerService.requestRebind(component)
            }.onSuccess { Log.i(TAG, "listener not bound after rebind, asked the system to register it again") }
                .onFailure { Log.i(TAG, "re-register refused: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * After an unbind request the system stops sending callbacks, and the disconnected callback may arrive late: treat the request as already disconnected,
     * so that an unlock during this window is not treated as "still connected" and skipped for rebinding.
     */
    private fun unbind() {
        val s = service ?: return
        if (s.unbindQuietly()) {
            service = null
            connected.value = false
        }
    }

    private fun catchUp() {
        // The listener is not connected, or the shade cannot be read: this attempt caught up nothing and it is still owed (retried on the next unlock, connect or new notification)
        val s = service ?: return
        val active = s.activeOrNull() ?: return Unit.also { Log.i(TAG, "catch-up read failed, still owed") }
        // The shade was read: reconcile it with the inbox first — the listener was unbound during the locked spell, so notifications that were
        // dealt with, withdrawn or timed out had no callback; without reconciling they would linger in the inbox and in the reminder count, and
        // opening it would write a reminder that has long been gone. Reconcile against every key in the shade (not just the Apps we read from);
        // what is dropped this way does not count as the user's "open" or "dismiss" and is not recorded for learning
        // opening it would write a reminder that has long been gone. Reconcile against every key in the shade (not just the Apps we read from);
        try {
            val dropped = inbox.retainKeys(active.mapNotNullTo(HashSet()) { it.key })
            if (dropped > 0) Log.i(TAG, "catch-up dropped $dropped records no longer in the shade")
            val records = active.mapNotNull { sbn -> gatedRecord(sbn) }
            // Leave a few seconds of slack before the lock time: callbacks queued before the unbind and dropped by the system are still caught up, and duplicates are stopped by dedup
            CatchUp.select(records, prefs.enabledAtMs, policy.lockedAtMs - CATCH_UP_SLACK_MS).forEach(::admit)
        } finally {
            // What is owed is "read the shade once": once it was read, it counts as repaid. An error capturing one entry must not keep it owed forever (retrying on every notification and always getting stuck on the same entry)
            policy.caughtUp()
        }
    }

    /** [sbn] arrived while unlocked (the caller has already checked the lock state). */
    private fun ingest(sbn: StatusBarNotification) {
        try {
            val r = gatedRecord(sbn) ?: return
            // Notifications arriving in real time also respect "nothing from before it was turned on" (the system occasionally reposts old notifications)
            if (r.postedAtMs > prefs.enabledAtMs) admit(r)
        } finally {
            // The last catch-up failed to read and is still owed: retry now that a notification arrived and the listener is connected (capture the new one first; duplicates are stopped by dedup)
            if (policy.catchUpOwed) run(policy.retry(locked = false, connected = connected.value))
        }
    }

    /** The package-name gate comes before reading notification content: anything not on the whitelist (or Claire itself, or disabled by App access) is not even read. */
    private fun gatedRecord(sbn: StatusBarNotification): NoticeRecord? {
        val pkg = sbn.packageName ?: return null
        if (!BlockedApps.readable(pkg, allowed.current(), access())) return null
        return NoticeMapper.read(sbn)?.copy(importance = service?.importanceOf(sbn.key))?.toRecord()
    }

    private fun admit(raw: NoticeRecord) {
        inbox.noteReceived()
        // After cleaning, split the conversation, the speaker and the utterance according to each App's convention (QQ, WeChat; see [NoticeShapes]); the split form is used from here on
        val r = NoticeShapes.apply(NoticeFilter.sanitize(raw))
        if (!BlockedApps.admits(r, allowed.current(), access())) return
        // Status displays reposted over and over by an App (navigation prompts and the like) are not captured; when one is first recognized, the entries captured before it are removed too (see [LiveUpdates])
        when (live.observe(r)) {
            LiveUpdates.Verdict.Normal -> {}
            LiveUpdates.Verdict.Live -> return
            LiveUpdates.Verdict.BecameLive -> {
                // Log only which App and whether it is silent, never the content
                Log.i(TAG, "status display, not taken: ${r.packageName} silent=${r.silent}")
                hintBoard.withdraw(r.key)
                inbox.remove(r.key)
                learn.dropLive(r.key, r.packageName, r.postedAtMs - LiveUpdates.WINDOW_MS)
                return
            }
        }
        val admitted = dedup.admit(r) ?: return
        prefs.dedup = dedup.encode()
        inbox.add(r)
        // Learning gets the messages seen for the first time this round (from messaging-style notifications), not just the notification text
        learn.capture(r, admitted.newMessages)
    }

    /**
     * The listener component is disabled by default in the manifest (people who never used this feature do not see Claire in the system's
     * "notification access" list); it is enabled the first time the feature is turned on and never disabled again (disabling makes the system
     * revoke the access, see [setEnabled]).
     */
    private fun enableComponent() {
        val pm = app.packageManager
        runCatching {
            if (pm.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            }
        }.onFailure { Log.w(TAG, "cannot enable the notification listener", it) }
    }

    // ---- Worth a reminder (see [NoticeHints]): in memory only, the capsule shows just the count; only on opening is it written as one message from Claire in a chat (see [NoticeTalks]) ----

    private val hintBoard = NoticeHints()
    /** The few entries judged worth a reminder and still in the inbox, and how many are unseen. */
    val hints: StateFlow<NoticeHints.State> = hintBoard.state

    private val talks = NoticeTalks(com.wangzan101.mika.chat.ConversationStore.shared(app), prefs)

    /**
     * The user opened a reminder (the number on the floating window, or the row on the proactive page): the unseen entries are written as one
     * message from Claire, put into today's reminder chat, that chat is made the current one, and those entries are marked as seen (see
     * [NoticeTalks.open]). Returns the id of that chat; null when there is nothing to open or it cannot be written. Does not call the model.
     */
    suspend fun openHintTalk(): String? {
        val now = System.currentTimeMillis()
        return try {
            talks.open(hintBoard, now) { fresh ->
                NoticeTalk.compose(fresh.map { talkItem(it, now) }, talkCopy())?.let {
                    NoticeTalks.Draft(
                        it.text, tr(R.string.notice_talk_title, DateUtils.formatDateTime(app, now, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR)),
                        shown = it.shown,
                    )
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not open the notice talk: ${e.javaClass.simpleName}")
            null
        }
    }

    /** The App name is read from the system by package name (not from the text in the notification); a time on the same day is written as hour and minute only. */
    private fun talkItem(h: com.wangzan101.mika.core.proactive.NoticeHint, now: Long) = NoticeTalk.Item(
        app = com.wangzan101.mika.task.ConfirmationNotifier.appLabel(app, h.packageName),
        time = DateUtils.formatSameDayTime(h.postedAtMs, now, java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).toString(),
        title = h.title, text = h.text, reason = h.reason,
    )

    private fun talkCopy() = NoticeTalk.Copy(
        intro = { tr(R.string.notice_talk_intro, it) },
        reason = { tr(R.string.notice_hint_reason, it) },
        more = { tr(R.string.notice_talk_more, it) },
        outro = tr(R.string.notice_talk_outro),
    )

    init {
        // Reconcile only when the inbox, the decision marks or the master switch changes; no timing
        scope.launch {
            combine(inbox.state, triage.state, _enabled) { box, t, on -> Triple(box.records, t.marks, on) }
                .collect { (records, marks, on) -> hintBoard.sync(records, marks, on) }
        }
    }

    companion object {
        /** How long starting a task waits at most for the "derived from notifications" section. */
        private const val OBSERVED_WAIT_MS = 2_000L
        /** How long reading notifications for a task waits at most (file reads and decryption happen on the proactive coroutine). */
        private const val NOTICES_WAIT_MS = 5_000L

        /** Whether a task may have the read-notifications tool: the proactive capability and "daily notification digest" are both on, and the aggressiveness is not the most conservative level. */
        internal fun tasksMayReadNotices(proactive: Boolean, digest: Boolean, level: LearnLevel): Boolean =
            proactive && digest && level.intoTasks.isNotEmpty()
        private const val TAG = "MikaProactive"
        private const val CATCH_UP_SLACK_MS = 5_000L

        fun create(
            context: Context, access: () -> AppGate, accessChanges: Flow<AppGate>, triage: TriageHooks,
            shellRunner: () -> ShellCommandRunner,
        ) = ProactiveRuntime(
            context, FeatureFlags.get(context),
            NoticeAllowStore(File(context.applicationContext.filesDir, "proactive-allow.json")), access, accessChanges, shellRunner, triage,
        )
    }
}
