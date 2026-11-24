package com.wangzan101.mika.cap.phone.setup

import android.content.Context
import com.wangzan101.mika.core.device.shell.HelperHandoff
import com.wangzan101.mika.core.device.shell.RootGrant
import com.wangzan101.mika.core.device.shell.RootStart
import com.wangzan101.mika.core.device.shell.SuAnswer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * The two authorizations available when the phone already has root capability. Detecting su only looks at file capability; it never executes su and does not identify the root implementation.
 * After the user explicitly authorizes it the first time, the phone helper automatically prefers to be started through su under the shell identity, with no visible switch;
 * root commands are a separate switch, off by default. After a denial or no reply, only an explicit user action requests it again.
 */
class RootControl(context: Context, private val scope: CoroutineScope, private val changed: () -> Unit) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** [startRequested] only records whether the user ever tapped the helper authorization; actually auto-starting also requires [startGrant] to be [RootGrant.Granted]. */
    data class State(
        val suPresent: Boolean = false,
        val startRequested: Boolean = false,
        val startGrant: RootGrant = RootGrant.Unknown,
        val commands: Boolean = false,
        val commandsGrant: RootGrant = RootGrant.Unknown,
    )

    private val _state = MutableStateFlow(
        State(
            suPresent = false,
            startRequested = prefs.getBoolean(KEY_START, false),
            startGrant = RootGrant.of(answer(KEY_START_ANSWER)),
            commands = prefs.getBoolean(KEY_COMMANDS, false),
            commandsGrant = RootGrant.of(answer(KEY_COMMANDS_ANSWER)),
        ),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    /** Whether su is present: checked on the spot the first time it is used (only whether a few files exist), updated by [refresh] afterwards. */
    @Volatile private var suFound: Boolean? = null
    /** Each new authorization action invalidates the old request; after unpairing, a late su answer must not turn auto-start back on either. */
    private val startRequest = AtomicInteger(0)
    private val commandsRequest = AtomicInteger(0)

    private fun suPresent(): Boolean = suFound ?: (RootShell.su() != null).also { found ->
        suFound = found
        _state.update { it.copy(suPresent = found) }
    }

    /** Look for su again (when the App starts or the settings page opens). This only detects files and never requests root authorization. */
    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val before = suFound
            val found = RootShell.su() != null
            suFound = found
            _state.update { it.copy(suPresent = found) }
            if (before != null && before != found && _state.value.startRequested) changed()
        }
    }

    /** What the watchdog sees: root start is Ready only when the user has requested it and su has confirmed the shell identity. */
    fun rootStart(): RootStart = RootStart.of(_state.value.startRequested, suPresent(), answer(KEY_START_ANSWER))

    /** su's answer when the watchdog starts the helper; null forgets the previous answer after an explicit user retry. */
    fun setStartAnswer(a: SuAnswer?) {
        if (a == null) prefs.edit().remove(KEY_START_ANSWER).apply()
        else prefs.edit().putString(KEY_START_ANSWER, a.name).apply()
        _state.update { it.copy(startGrant = RootGrant.of(a)) }
    }

    /** su turned out to be gone while starting the helper through it or running commands. */
    fun suGone() {
        if (suFound == false) return
        suFound = false
        _state.update { it.copy(suPresent = false) }
    }

    /**
     * The user tapped "auto-start the phone helper", or retries after a failure: this is the only entry point for the first and any later su request.
     * Repeated taps do nothing while granted or already asking; denials, silence and a wrong identity are all explained in settings.
     */
    fun requestStart() {
        val current = _state.value
        if (current.startGrant == RootGrant.Granted || current.startGrant == RootGrant.Pending) return
        val request = startRequest.incrementAndGet()
        prefs.edit().putBoolean(KEY_START, true).remove(KEY_START_ANSWER).apply()
        _state.update { it.copy(startRequested = true, startGrant = RootGrant.Pending) }
        scope.launch {
            val a = withContext(Dispatchers.IO) { RootShell.ask(HelperHandoff.SHELL_UID, ASK_TIMEOUT_MS) }
            if (request != startRequest.get()) return@launch
            if (a == null) {
                // su disappeared during authorization: do not stay in Pending forever, otherwise the authorization could not be tapped again even after su comes back
                setStartAnswer(null)
                suGone()
            } else setStartAnswer(a)
            changed()
        }
    }

    /** Unpairing stops phone operation: clear the auto-start request so the watchdog does not start the helper again. */
    fun turnOffStart() {
        startRequest.incrementAndGet()
        prefs.edit().putBoolean(KEY_START, false).remove(KEY_START_ANSWER).apply()
        _state.update { it.copy(startRequested = false, startGrant = RootGrant.Unknown) }
    }

    /** The Agent may use root tools: the user turned it on, su has agreed, and su is still present. */
    fun commandsAllowed(): Boolean = _state.value.commands && answer(KEY_COMMANDS_ANSWER) == SuAnswer.Granted && suPresent()

    /** Turn "allow Claire to run root commands" on or off. Turning it on asks su once as root and turns on only if su agrees. */
    fun setCommands(on: Boolean) {
        if (!on) {
            commandsRequest.incrementAndGet()
            prefs.edit().putBoolean(KEY_COMMANDS, false).apply()
            _state.update { it.copy(commands = false, commandsGrant = RootGrant.Unknown) }
            return
        }
        if (_state.value.commandsGrant == RootGrant.Pending) return
        val request = commandsRequest.incrementAndGet()
        _state.update { it.copy(commandsGrant = RootGrant.Pending) }
        scope.launch {
            val a = withContext(Dispatchers.IO) { RootShell.ask(0, ASK_TIMEOUT_MS) }
            if (request != commandsRequest.get()) return@launch
            if (a == null) suGone()
            val ok = a == SuAnswer.Granted
            prefs.edit().putBoolean(KEY_COMMANDS, ok).putString(KEY_COMMANDS_ANSWER, a?.name).apply()
            _state.update { it.copy(commands = ok, commandsGrant = RootGrant.of(a)) }
        }
    }

    /** su denied while running a root command: turn this option off and record it (it is asked again the next time it is turned on). */
    fun commandsDenied(a: SuAnswer) {
        commandsRequest.incrementAndGet()
        prefs.edit().putBoolean(KEY_COMMANDS, false).putString(KEY_COMMANDS_ANSWER, a.name).apply()
        _state.update { it.copy(commands = false, commandsGrant = RootGrant.of(a)) }
    }

    private fun answer(key: String): SuAnswer? = prefs.getString(key, null)?.let { n -> SuAnswer.entries.firstOrNull { it.name == n } }

    private companion object {
        const val PREFS = "root"
        const val KEY_START = "start"
        const val KEY_START_ANSWER = "startAnswer"
        const val KEY_COMMANDS = "commands"
        const val KEY_COMMANDS_ANSWER = "commandsAnswer"
        /** How long to wait for the system permission dialog when the user explicitly requests authorization, enough time to read it and tap once. */
        const val ASK_TIMEOUT_MS = 60_000L
    }
}
