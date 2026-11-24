package com.wangzan101.mika.proactive

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.core.proactive.GrantResult
import com.wangzan101.mika.core.proactive.LearnLevel
import com.wangzan101.mika.core.proactive.NoticeFilter
import com.wangzan101.mika.core.proactive.SourceTier
import com.wangzan101.mika.core.proactive.NoticeRecord
import com.wangzan101.mika.core.proactive.TriageDecision
import com.wangzan101.mika.core.proactive.TriageGate
import com.wangzan101.mika.core.proactive.TriageMark
import com.wangzan101.mika.core.proactive.TriageNote
import com.wangzan101.mika.core.proactive.TriageTarget
import com.wangzan101.mika.core.proactive.TriageVerdict
import com.wangzan101.mika.core.model.usage.ProactiveBudget
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.settings.ui.TokenBudgetDialog
import com.wangzan101.mika.settings.ui.tokenBudgetNumber
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.DetailNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.core.proactive.ObservedContact
import com.wangzan101.mika.core.proactive.NoticeInbox
import com.wangzan101.mika.ui.components.SearchField
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › proactive capability. Only things the user must decide on:
 * the status card (master switch, one line of current state, a button when action is needed) → the entry to "What Claire Knows"
 * → settings (aggressiveness, sensitive-category inference, daily notification digest, daily limit) → which Apps are read (per App: off /
 * low / medium / high; the list can be long, so it comes after settings) → diagnostics (collapsed by default: listener state, counts, retry
 * failed, recent notifications) → footer notes (what is sent, what is stored, for how long). What has been digested, and deleting it, is on the
 * next page ([ProactiveLearnScreen]). Notification content and the model's stated reasons come from elsewhere and are always shown as plain
 * text: links are not detected and no formatting is rendered.
 */
@Composable
fun ProactiveScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MikaApp
    val runtime = app.proactive
    val enabled by runtime.enabled.collectAsState()
    val connected by runtime.isConnected.collectAsState()
    val allowed by runtime.allowed.state.collectAsState()
    val inbox by runtime.inbox.state.collectAsState()
    val consent by runtime.consent.collectAsState()
    val triage by runtime.triageState.collectAsState()
    val learnState by runtime.learnState.collectAsState()
    val level by runtime.learnLevel.collectAsState()
    val sensitiveOn by runtime.learnSensitive.collectAsState()
    val digestOn by runtime.learnDigest.collectAsState()
    val optOut by runtime.learnOptOut.collectAsState()
    val high by runtime.learnHigh.collectAsState()
    val topics by runtime.learnTopics.collectAsState()
    val contactsView by runtime.learnContactsView.collectAsState()
    val limit by runtime.triageLimit.collectAsState()
    val accessKnown by runtime.accessKnown.collectAsState(runtime.accessKnownNow())
    // The provider and model the decision pass and learning will actually use: fetched only while the feature is on (off, the model config is untouched; turning the switch on fetches it separately); re-read when returning to this page
    var target by remember { mutableStateOf<TriageTarget?>(null) }
    var targetRead by remember { mutableStateOf(0) }
    LaunchedEffect(enabled, consent, targetRead) { target = if (enabled) runtime.triageTarget() else null }
    // The provider and model shown to the user in the dialog; [confirmEnables] true means turning it on from off (only turned on after consent), otherwise it is a (re)confirmation while already on
    var confirmModel by remember { mutableStateOf<TriageTarget?>(null) }
    var confirmEnables by remember { mutableStateOf(false) }
    // Turning the master switch on: with a model configured, show the dialog first (only turned on after consent); with none, turn it on to capture and send nothing. Turning it off turns it off directly
    val toggle: (Boolean) -> Unit = { on ->
        if (!on) runtime.setEnabled(false)
        else {
            val t = runtime.triageTarget()
            if (t == null) runtime.setEnabled(true) else { confirmEnables = true; confirmModel = t }
        }
    }
    var editingBudget by remember { mutableStateOf(false) }
    var customBudget by remember { mutableStateOf("") }
    var authorized by remember { mutableStateOf(runtime.authorized()) }
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var addingApps by rememberSaveable { mutableStateOf(false) }
    var pickApp by remember { mutableStateOf<String?>(null) }
    // A change to "read or not, which tier" was not saved (storage full or the like): prompt to choose again. The prompt stays as long as the
    // list still has an unwritten "do not read" (including what a previous run left), regardless of the latest change — changing another App's
    // tier in between succeeded and must not clear the prompt
    var unsaved by remember { mutableStateOf(runtime.allowed.dirty) }
    var pickLevel by remember { mutableStateOf(false) }
    var confirmDigestOn by remember { mutableStateOf(false) }
    var confirmSensitiveOn by remember { mutableStateOf(false) }
    var confirmSensitiveOff by remember { mutableStateOf(false) }
    var diagOpen by rememberSaveable { mutableStateOf(false) }
    // "Grant via the Claire assistant": needs the assistant or a paired connection (the same condition as the restore switch); the button cannot be tapped while it runs
    val phone = remember { PhoneServices.of(context) }
    val shellStatus by phone.shellControl.status.collectAsState()
    val helperShell by phone.hub.shell.collectAsState()
    val helperUsable = helperShell != null || shellStatus != BuiltinShellStatus.NotPaired
    var confirmGrant by remember { mutableStateOf(false) }
    var granting by remember { mutableStateOf(false) }
    var grantMessage by remember { mutableStateOf<Int?>(null) }
    val uiScope = rememberCoroutineScope()
    // After a timeout the command may still take effect: re-read authorization when the listener connects
    LaunchedEffect(connected) { authorized = runtime.authorized() }
    // Re-read authorization when returning from the system settings; the topic and contact counts require the pools to be loaded (which tidies them up as well)
    LifecycleResumeEffect(Unit) {
        authorized = runtime.authorized()
        targetRead++
        runtime.onLearn { open() }
        onPauseOrDispose {}
    }
    LaunchedEffect(Unit) {
        // Claire's own packages (release, .dev, etc.) are not offered for selection
        apps = withContext(Dispatchers.IO) {
            runCatching { launchableApps(app.packageManager, app.packageName) }.getOrDefault(emptyList())
                .filterNot { NoticeFilter.isSelf(it.first) }
        }
    }
    val labels = apps.toMap()
    fun label(pkg: String) = labels[pkg] ?: pkg
    fun tierOf(pkg: String): SourceTier = when (pkg) { in optOut -> SourceTier.Low; in high -> SourceTier.High; else -> SourceTier.Mid }
    // The model config and usage ledger are touched only while the proactive capability is on
    var usageTick by remember { mutableStateOf(0) }
    if (enabled) {
        val version by remember { runtime.usageVersion() }.collectAsState()
        LaunchedEffect(version) { usageTick++ }
    }
    val (tokens, calls) = remember(enabled, usageTick, triage, learnState) { if (enabled) runtime.triageUsage() else 0L to 0L }
    val budgetOut = enabled && ProactiveBudget.exhausted(tokens, calls, limit) != null
    val timeFormat = remember { android.text.format.DateFormat.getTimeFormat(context) }
    val dateFormat = remember { android.text.format.DateFormat.getMediumDateFormat(context) }
    // "What Claire Knows" is a page below this one; it comes after every remember so this page's state survives coming back
    var knownOpen by rememberSaveable { mutableStateOf(false) }
    if (knownOpen) return ProactiveLearnScreen(onBack = { knownOpen = false })
    val gate = TriageGate.of(true, consent, target)

    PageScaffold(stringResource(R.string.proactive_title), onBack) {
        // ---- Status card: the switch and one line of current state; a button when the user must act ----
        GroupCard {
            ListRow(null, stringResource(R.string.proactive_switch), first = true, onClick = { toggle(!enabled) }) {
                MikaSwitch(enabled, toggle, Modifier.testTag("proactive-switch"))
            }
            if (enabled) when {
                // "App access" cannot be read: everything is suspended and no other state applies right now
                !accessKnown -> ListRow(null, stringResource(R.string.proactive_status_access))
                !authorized -> {
                    ListRow(null, stringResource(R.string.proactive_listener, stringResource(R.string.proactive_state_unauthorized))) {
                        TextButton({
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }) { Text(stringResource(R.string.proactive_open_access)) }
                    }
                    // The second way: instead of going to the system page, have the phone assistant grant it with shell identity; confirm first, and while it runs show a spinner and block taps
                    ListRow(
                        null, stringResource(R.string.proactive_helper_grant),
                        (grantMessage ?: R.string.proactive_helper_unavailable.takeIf { !helperUsable })?.let { stringResource(it) },
                        onClick = if (helperUsable && !granting) ({ confirmGrant = true }) else null,
                    ) {
                        if (granting) CircularProgressIndicator(Modifier.size(20.dp).testTag("proactive-helper-grant-busy"), strokeWidth = 2.dp)
                    }
                }
                gate == TriageGate.NoModel -> ListRow(null, stringResource(R.string.proactive_triage_no_model))
                gate == TriageGate.NeedsConsent -> {
                    // Not consented yet (turned on with no model configured), or consented but the provider or model changed
                    val ask = { target?.let { confirmEnables = false; confirmModel = it } }
                    ListRow(
                        null, stringResource(if (consent == null) R.string.proactive_status_consent else R.string.proactive_triage_reconfirm),
                        onClick = { ask() },
                    ) {
                        TextButton({ ask() }, Modifier.testTag("proactive-reconfirm")) { Text(stringResource(R.string.proactive_triage_reconfirm_action)) }
                    }
                }
                budgetOut -> ListRow(null, stringResource(R.string.proactive_learn_state_budget))
                // The decision pass's model is unreachable and it is paused: do not say "working"
                triage.status == TriageStatus.Unreachable -> ListRow(null, stringResource(R.string.proactive_triage_state_unreachable))
                !connected -> ListRow(null, stringResource(R.string.proactive_listener, stringResource(R.string.proactive_state_unbound)))
                else -> ListRow(null, stringResource(R.string.proactive_status_working, inbox.received))
            }
        }
        // The "worth a reminder" entry (used when the capsule cannot show it) and the two feedback counts
        if (enabled) NoticeHintEntry(runtime.hints.collectAsState().value)

        // ---- What has been digested is on the next page ----
        GroupCard {
            ListRow(
                null, stringResource(R.string.proactive_learn_title),
                stringResource(R.string.proactive_known_sub, topics.size, contactsView.contacts.size),
                first = true, onClick = { knownOpen = true },
            ) { RowChevron() }
        }

        // ---- Settings ----
        GroupLabel(stringResource(R.string.settings_title))
        GroupCard {
            // Aggressiveness: one choice adjusts a whole set of thresholds (how many days a topic counts, how many reminders per day, whether digested content is carried into tasks)
            ListRow(null, stringResource(R.string.proactive_learn_level), stringResource(levelDesc(level)), first = true, onClick = { pickLevel = true }) {
                RowValue(stringResource(levelName(level)))
            }
            // Sensitive-category inference: off by default; confirm before turning it on, and ask whether to delete the stored sensitive topics when turning it off
            val toggleSensitive: (Boolean) -> Unit = { on -> if (on) confirmSensitiveOn = true else confirmSensitiveOff = true }
            ListRow(
                null, stringResource(R.string.proactive_learn_sensitive), stringResource(R.string.proactive_learn_sensitive_sub),
                onClick = { toggleSensitive(!sensitiveOn) },
            ) {
                MikaSwitch(sensitiveOn, toggleSensitive, Modifier.testTag("proactive-learn-sensitive"))
            }
            // Daily notification digest: it stores raw notifications on this device, so it has its own explanation and confirmation before turning on; turning it off deletes the stored raw text
            val toggleDigest: (Boolean) -> Unit = { on -> if (on) confirmDigestOn = true else runtime.setLearnDigest(false) }
            ListRow(
                null, stringResource(R.string.proactive_learn_digest_switch),
                if (digestOn) stringResource(R.string.proactive_learn_digest_on, learnState.journal) else stringResource(R.string.proactive_learn_digest_sub),
                onClick = { toggleDigest(!digestOn) },
            ) {
                MikaSwitch(digestOn, toggleDigest, Modifier.testTag("proactive-learn-digest"))
            }
            ListRow(
                null,
                stringResource(
                    R.string.proactive_triage_budget, tokenBudgetNumber(tokens), tokenBudgetNumber(limit),
                    calls.toInt(), ProactiveBudget.callLimit(limit).toInt(),
                ),
                onClick = {
                    customBudget = ""
                    editingBudget = true
                },
            )
        }
        // At any level other than the most conservative, the "what being carried into tasks means and to whom it is sent" line stays here (it is in the dialog too)
        if (level != LearnLevel.Conservative) DetailNote(
            stringResource(R.string.proactive_learn_level_summary),
            stringResource(R.string.proactive_learn_level_note),
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )

        // ---- Which Apps are read: the chosen ones are listed here, each as low / medium / high; "choose more" expands all Apps ----
        GroupLabel(stringResource(R.string.proactive_apps, allowed.size))
        // When the list cannot be read it is treated as empty: say so, so the user does not think they never selected anything
        if (!runtime.allowed.readable) FooterNote(stringResource(R.string.proactive_apps_unreadable))
        if (unsaved) FooterNote(stringResource(R.string.proactive_apps_unsaved))
        GroupCard {
            val chosen = allowed.filterNot(NoticeFilter::isSelf).sortedBy { label(it).lowercase() }
            chosen.forEachIndexed { i, pkg ->
                ListRow(null, label(pkg), pkg.takeIf { it != label(pkg) }, first = i == 0, onClick = { pickApp = pkg }) {
                    RowValue(stringResource(tierName(tierOf(pkg))))
                }
            }
            ListRow(null, stringResource(R.string.proactive_apps_more), first = chosen.isEmpty(), onClick = { addingApps = !addingApps }) { RowChevron() }
        }
        if (addingApps) {
            SearchField(search, { search = it }, stringResource(R.string.appaccess_search), Modifier.testTag("proactive-search"))
            GroupCard(Modifier.padding(top = 8.dp)) {
                apps.filter { (pkg, name) -> pkg !in allowed && (pkg.contains(search, true) || name.contains(search, true)) }
                    .forEachIndexed { i, (pkg, name) -> ListRow(null, name, pkg, first = i == 0, onClick = { pickApp = pkg }) }
            }
        }
        FooterNote(stringResource(R.string.proactive_apps_sub))

        // ---- Diagnostics: for troubleshooting, collapsed by default ----
        GroupCard {
            ListRow(null, stringResource(R.string.proactive_diag), first = true, onClick = { diagOpen = !diagOpen }) { RowChevron() }
        }
        if (diagOpen) Diagnostics(
            runtime, enabled, connected, authorized, gate, inbox, triage, learnState, budgetOut, allowed, contactsView.contacts,
            ::label, timeFormat, dateFormat,
        )

        // ---- Footer: what is sent, what is stored, for how long; what happens when the switch is off ----
        FooterNote(stringResource(R.string.proactive_footer_send))
        FooterNote(stringResource(R.string.proactive_footer_store))
        FooterNote(stringResource(if (digestOn) R.string.proactive_footer_digest else R.string.proactive_footer_no_text))
        FooterNote(stringResource(R.string.proactive_off_note))
    }

    confirmModel?.let { t ->
        MikaDialog(
            onDismissRequest = { confirmModel = null },
            title = { Text(stringResource(R.string.proactive_triage_consent_title)) },
            text = { Text(consentText(t, digestOn, level)) },
            confirmButton = {
                // What is recorded is exactly the provider and model shown to the user in the dialog
                TextButton({
                    if (confirmEnables) runtime.setEnabled(true, t) else runtime.confirmModel(t)
                    confirmModel = null
                }, Modifier.testTag("proactive-consent")) { Text(stringResource(R.string.proactive_triage_reconfirm_action)) }
            },
            dismissButton = { TextButton({ confirmModel = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (editingBudget) TokenBudgetDialog(
        title = stringResource(R.string.proactive_triage_budget_title),
        description = stringResource(
            R.string.proactive_triage_budget_sub,
            ProactiveBudget.callLimit(limit).toInt(), ProactiveBudget.TRIAGE_SHARE_PERCENT.toInt(),
        ),
        current = limit,
        custom = customBudget,
        onCustom = { customBudget = it.filter(Char::isDigit).take(9) },
        onPick = { picked ->
            picked?.let(runtime::setTriageBudget)
            editingBudget = false
        },
        onDismiss = { editingBudget = false },
        presets = BUDGET_PRESETS,
        unlimited = false,
    )
    if (confirmGrant) {
        MikaDialog(
            onDismissRequest = { confirmGrant = false },
            title = { Text(stringResource(R.string.proactive_helper_grant)) },
            text = { Text(stringResource(R.string.proactive_helper_grant_body)) },
            confirmButton = {
                TextButton({
                    confirmGrant = false
                    granting = true
                    grantMessage = null
                    uiScope.launch {
                        val r = runtime.grantViaHelper()
                        authorized = runtime.authorized()
                        grantMessage = when (r) {
                            GrantResult.Granted -> null
                            GrantResult.HelperUnavailable -> R.string.proactive_helper_unavailable
                            is GrantResult.Refused, GrantResult.TimedOut -> R.string.proactive_helper_failed
                        }
                        granting = false
                    }
                }) { Text(stringResource(R.string.proactive_helper_grant_confirm)) }
            },
            dismissButton = { TextButton({ confirmGrant = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    // Whether an App is read and, if so, at which tier. Tapping an item sets it and closes the dialog, so the only button is "cancel" (put on
    // confirmButton: MikaDialog always draws that slot). Off and low delete its stored raw notifications and sentences, as each item's subtitle says
    pickApp?.let { pkg ->
        val current = if (pkg in allowed) tierOf(pkg) else null
        MikaDialog(
            onDismissRequest = { pickApp = null },
            title = { Text(stringResource(R.string.proactive_learn_tier_title, label(pkg))) },
            text = {
                Column {
                    RadioRow(
                        stringResource(R.string.proactive_app_off), selected = current == null,
                        onClick = { unsaved = !runtime.setAppReading(pkg, null) || runtime.allowed.dirty; pickApp = null },
                        subtitle = stringResource(R.string.proactive_app_off_sub), first = true,
                    )
                    SourceTier.entries.forEach { t ->
                        RadioRow(
                            stringResource(tierName(t)), selected = t == current,
                            onClick = { unsaved = !runtime.setAppReading(pkg, t) || runtime.allowed.dirty; pickApp = null },
                            subtitle = stringResource(tierDesc(t)),
                        )
                    }
                }
            },
            confirmButton = { TextButton({ pickApp = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (pickLevel) {
        MikaDialog(
            onDismissRequest = { pickLevel = false },
            title = { Text(stringResource(R.string.proactive_learn_level)) },
            text = {
                Column {
                    LearnLevel.entries.forEach { l ->
                        RadioRow(
                            stringResource(levelName(l)), selected = l == level,
                            onClick = { runtime.setLearnLevel(l); pickLevel = false },
                            subtitle = stringResource(levelDesc(l)), first = l == LearnLevel.entries.first(),
                        )
                    }
                    Text(stringResource(R.string.proactive_learn_level_note), Modifier.padding(top = 10.dp))
                }
            },
            confirmButton = { TextButton({ pickLevel = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmDigestOn) {
        MikaDialog(
            onDismissRequest = { confirmDigestOn = false },
            title = { Text(stringResource(R.string.proactive_learn_digest_switch)) },
            text = { Text(stringResource(R.string.proactive_learn_digest_body)) },
            confirmButton = {
                TextButton({
                    runtime.setLearnDigest(true)
                    confirmDigestOn = false
                }, Modifier.testTag("proactive-learn-digest-confirm")) { Text(stringResource(R.string.proactive_learn_sensitive_on_confirm)) }
            },
            dismissButton = { TextButton({ confirmDigestOn = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmSensitiveOn) {
        MikaDialog(
            onDismissRequest = { confirmSensitiveOn = false },
            title = { Text(stringResource(R.string.proactive_learn_sensitive)) },
            text = { Text(stringResource(R.string.proactive_learn_sensitive_on_body)) },
            confirmButton = {
                TextButton({
                    runtime.setLearnSensitive(true)
                    confirmSensitiveOn = false
                }, Modifier.testTag("proactive-learn-sensitive-confirm")) { Text(stringResource(R.string.proactive_learn_sensitive_on_confirm)) }
            },
            dismissButton = { TextButton({ confirmSensitiveOn = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmSensitiveOff) {
        // The default (primary button) is delete; the other keeps them but adds no more. Tapping outside the dialog does not flip the switch
        MikaDialog(
            onDismissRequest = { confirmSensitiveOff = false },
            title = { Text(stringResource(R.string.proactive_learn_sensitive_off_title)) },
            text = { Text(stringResource(R.string.proactive_learn_sensitive_off_body)) },
            confirmButton = {
                TextButton({
                    runtime.setLearnSensitive(false, deleteExisting = true)
                    confirmSensitiveOff = false
                }, Modifier.testTag("proactive-learn-sensitive-delete")) { Text(stringResource(R.string.proactive_learn_delete)) }
            },
            dismissButton = {
                TextButton({
                    runtime.setLearnSensitive(false, deleteExisting = false)
                    confirmSensitiveOff = false
                }) { Text(stringResource(R.string.proactive_learn_sensitive_keep)) }
            },
        )
    }
}

/**
 * The text in the confirmation dialog: who it is sent to, what is digested, where it is stored; then appended according to the settings right
 * now — with "daily notification digest" on, raw notifications are stored too, and at any level other than the most conservative, digested
 * content is also sent to the model that runs tasks. These settings remain after the master switch is turned off, so every confirmation says
 * what applies at that moment.
 */
@Composable
internal fun consentText(target: TriageTarget, digestOn: Boolean, level: LearnLevel): String = listOfNotNull(
    stringResource(R.string.proactive_consent_body, target.providerName, target.model),
    stringResource(R.string.proactive_footer_digest).takeIf { digestOn },
    stringResource(R.string.proactive_learn_level_note).takeIf { level != LearnLevel.Conservative },
).joinToString("\n\n")

internal fun levelName(l: LearnLevel): Int = when (l) {
    LearnLevel.Conservative -> R.string.proactive_learn_level_conservative
    LearnLevel.Balanced -> R.string.proactive_learn_level_balanced
    LearnLevel.Aggressive -> R.string.proactive_learn_level_aggressive
}

private fun levelDesc(l: LearnLevel): Int = when (l) {
    LearnLevel.Conservative -> R.string.proactive_learn_level_conservative_sub
    LearnLevel.Balanced -> R.string.proactive_learn_level_balanced_sub
    LearnLevel.Aggressive -> R.string.proactive_learn_level_aggressive_sub
}

private fun tierName(t: SourceTier): Int = when (t) {
    SourceTier.Low -> R.string.proactive_learn_tier_low
    SourceTier.Mid -> R.string.proactive_learn_tier_mid
    SourceTier.High -> R.string.proactive_learn_tier_high
}

private fun tierDesc(t: SourceTier): Int = when (t) {
    SourceTier.Low -> R.string.proactive_learn_tier_low_sub
    SourceTier.Mid -> R.string.proactive_learn_tier_mid_sub
    SourceTier.High -> R.string.proactive_learn_tier_high_sub
}

/** How many earlier text segments are shown at most under each notification in the inbox preview. */
private const val HISTORY_SHOWN = 5

/** The presets for the proactive daily budget; ranges follow TokenBudgetLimits. */
private val BUDGET_PRESETS = listOf(100_000L, 200_000L, 500_000L, 1_000_000L, 2_000_000L)

/**
 * Diagnostics (collapsed by default): listener state, today's counts, the state of the decision pass and learning, retrying failures, each
 * App's learning counters, and the recently received notifications with their decisions. For troubleshooting; no need to look at it normally.
 * The counters show no notification text; "recent notifications" shows those still in memory.
 */
@Composable
private fun Diagnostics(
    runtime: ProactiveRuntime,
    enabled: Boolean,
    connected: Boolean,
    authorized: Boolean,
    gate: TriageGate,
    inbox: NoticeInbox.Snapshot,
    triage: TriageState,
    learn: LearnState,
    budgetOut: Boolean,
    allowed: Set<String>,
    contacts: List<ObservedContact>,
    label: (String) -> String,
    timeFormat: java.text.DateFormat,
    dateFormat: java.text.DateFormat,
) {
    GroupCard {
        val state = when {
            connected -> R.string.proactive_state_connected
            !authorized -> R.string.proactive_state_unauthorized
            else -> R.string.proactive_state_unbound
        }
        ListRow(null, stringResource(R.string.proactive_listener, stringResource(state)), first = true)
        ListRow(null, stringResource(R.string.proactive_counts, inbox.received, inbox.kept))
        if (enabled && gate == TriageGate.Ready) {
            val c = triage.counts
            val counts = stringResource(R.string.proactive_triage_counts, c.pending + c.sending, c.judged, c.failed, c.cooling)
            val line = when {
                triage.status == TriageStatus.TokensUsedUp -> stringResource(R.string.proactive_triage_state_tokens)
                triage.status == TriageStatus.CallsUsedUp -> stringResource(R.string.proactive_triage_state_calls)
                triage.status == TriageStatus.Busy -> stringResource(R.string.proactive_triage_state_busy)
                triage.status == TriageStatus.Unreachable -> stringResource(R.string.proactive_triage_state_unreachable)
                triage.status == TriageStatus.Sending -> counts + " · " + stringResource(R.string.proactive_triage_sending)
                else -> counts
            }
            ListRow(null, line, counts.takeIf { it != line && triage.status != TriageStatus.Sending })
            // Offer this entry even when paused for unreachability: that batch was not recorded as failed (the failure count is 0), and without it the user can only wait
            if (c.failed > 0 || triage.status == TriageStatus.Unreachable) {
                ListRow(null, stringResource(R.string.proactive_triage_retry), onClick = { runtime.retryTriage() })
            }
            val learnCounts = stringResource(R.string.proactive_learn_state, learn.pending, learn.buffered)
            val learnLine = when {
                budgetOut || learn.status == LearnStatus.BudgetUsedUp -> stringResource(R.string.proactive_learn_state_budget)
                learn.status == LearnStatus.Busy -> stringResource(R.string.proactive_triage_state_busy)
                learn.status == LearnStatus.StoreUnavailable -> stringResource(R.string.proactive_learn_state_store)
                learn.status == LearnStatus.Sending -> learnCounts + " · " + stringResource(R.string.proactive_learn_state_sending)
                else -> learnCounts
            }
            val last = learn.lastDistilledAtMs?.let {
                stringResource(R.string.proactive_learn_last, dateFormat.format(java.util.Date(it)) + " " + timeFormat.format(java.util.Date(it)))
            } ?: stringResource(R.string.proactive_learn_never)
            ListRow(null, learnLine, if (learnLine == learnCounts) last else "$learnCounts · $last")
        }
    }

    // How much was learned per App (names are not shown): many more contacts than names means notification keys are not one per conversation
    val counted = allowed.filterNot(NoticeFilter::isSelf).sortedBy { label(it).lowercase() }
    if (counted.isNotEmpty()) {
        GroupLabel(stringResource(R.string.proactive_learn_apps))
        GroupCard {
            counted.forEachIndexed { i, pkg ->
                val f = learn.feed[pkg] ?: LearnFeed()
                val mine = contacts.filter { it.packageName == pkg }
                val reasons = f.contactDropped.entries.sortedBy { it.key.ordinal }.joinToString(", ") { "${it.key.name} ${it.value}" }
                ListRow(
                    null, label(pkg),
                    stringResource(R.string.proactive_learn_feed, f.captured, f.distilled, f.batches, f.failed, f.accepted, f.droppedTotal),
                    first = i == 0,
                    detail = stringResource(
                        R.string.proactive_learn_contacts_feed, mine.size, mine.map { it.name }.distinct().size, f.contactsAccepted, f.contactDroppedTotal,
                    ) + if (reasons.isEmpty()) "" else " ($reasons)",
                )
            }
        }
    }

    GroupLabel(stringResource(R.string.proactive_inbox))
    GroupCard {
        val latest = inbox.records.asReversed().take(20)
        if (latest.isEmpty()) EmptyNote(stringResource(R.string.proactive_inbox_empty))
        latest.forEach { r ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    "${label(r.packageName)} · ${timeFormat.format(java.util.Date(r.postedAtMs))}",
                    style = MikaType.Hint, color = MikaTheme.colors.ink3,
                )
                if (r.title.isNotBlank()) Text(r.title, style = MikaType.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // Text shown before the same notification was reposted (the last segment is the body below, so it is not repeated); only the latest HISTORY_SHOWN segments are shown, earlier ones are just counted
                val earlier = inbox.history[r.key].orEmpty().dropLast(1)
                if (earlier.size > HISTORY_SHOWN) {
                    Text(stringResource(R.string.proactive_history_more, earlier.size - HISTORY_SHOWN), style = MikaType.Hint, color = MikaTheme.colors.ink3)
                }
                earlier.takeLast(HISTORY_SHOWN).forEach { line ->
                    Text(line.text, style = MikaType.Hint, color = MikaTheme.colors.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (r.said.isNotBlank()) Text(r.said, style = MikaType.Body, color = MikaTheme.colors.ink2, maxLines = 6, overflow = TextOverflow.Ellipsis)
                if (enabled) triageLabel(triage.marks, r)?.let { tag ->
                    Text(tag, style = MikaType.Hint, color = MikaTheme.colors.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("proactive-triage-label"))
                }
            }
        }
    }
}

/**
 * The decision label for one notification: decision · reason (or a built-in explanation); a failure says failed; one not judged yet has no
 * label. Reasons are shown as plain text. When new content arrives after a judge (or a judge failure) (cooling down, or cooled down and waiting
 * to be judged again), the previous result is still shown, immediately followed by a short "new content, judged later" (placed before the
 * reason, so a long reason being truncated never cuts it off).
 */
@Composable
private fun triageLabel(marks: Map<String, TriageMark>, r: NoticeRecord): String? {
    val any = marks[r.key] ?: return null
    if (any.postedAtMs != r.postedAtMs) {
        val newer = stringResource(R.string.proactive_triage_newer)
        return when (any) {
            is TriageMark.Done -> verdictLabel(any.verdict, newer)
            is TriageMark.Failed -> stringResource(R.string.proactive_triage_failed) + " · " + newer
        }
    }
    if (any is TriageMark.Failed) return stringResource(R.string.proactive_triage_failed)
    return verdictLabel((any as TriageMark.Done).verdict, null)
}

/** Decision · [hint] (if any) · reason (or a built-in explanation). */
@Composable
private fun verdictLabel(v: TriageVerdict, hint: String?): String {
    val decision = stringResource(when (v.decision) {
        TriageDecision.Ignore -> R.string.proactive_triage_ignore
        TriageDecision.Remember -> R.string.proactive_triage_remember
        TriageDecision.Surface -> R.string.proactive_triage_surface
    })
    val why = when (v.note) {
        TriageNote.NoVerdict -> stringResource(R.string.proactive_triage_no_verdict)
        TriageNote.SurfaceCapped -> stringResource(R.string.proactive_triage_capped)
        null -> v.reason
    }
    return listOfNotNull(decision, hint, why.takeIf { it.isNotBlank() }).joinToString(" · ")
}
