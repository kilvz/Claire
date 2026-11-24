package com.wangzan101.mika.task

import android.os.IBinder
import android.util.DisplayMetrics
import com.wangzan101.mika.browser.PageLoans
import com.wangzan101.mika.cap.phone.a11y.LazyPhoneScreen
import com.wangzan101.mika.cap.web.WebStack
import com.wangzan101.mika.cap.web.WebTab
import com.wangzan101.mika.core.device.access.AppAccessDenied
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.cap.phone.AppPacePlan
import com.wangzan101.mika.core.model.decision.DecisionModel
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.learn.knowledge.KnowledgeStore
import com.wangzan101.mika.core.learn.memory.MemoryStore
import com.wangzan101.mika.core.learn.memory.ProfileEntry
import com.wangzan101.mika.core.learn.memory.TaskMemoryStore
import com.wangzan101.mika.core.learn.memory.UserUtterances
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.run.ResourceScope
import com.wangzan101.mika.core.run.trace.RunTrace
import com.wangzan101.mika.core.model.usage.RunUsageTally
import com.wangzan101.mika.core.model.usage.DeepSeekRunCost
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.screen.user.UserScreenSource
import com.wangzan101.mika.record.DetailedTraces
import com.wangzan101.mika.record.RunHandle
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.settings.RunApproval
import com.wangzan101.mika.settings.MemorySwitches
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * State shared by every stage of assembling one run and released during wrap-up.
 * Each stage ([assembleModels], [assemblePhone], …) records what it built here; without "build it, then assign it to a variable", a cancellation landing in between cannot leak anything.
 */
internal class RunAssembly(
    val traces: DetailedTraces,
    // The whole run shares one reported token budget; subtasks inherit the one in the coroutine context and do not open a separate allowance.
    val tokenBudget: Long?,
    showCost: Boolean,
    // Ask / autonomous: taken once when the run starts, and follows the developer-option switch when it is toggled; handed back during wrap-up
    val approvalControl: com.wangzan101.mika.settings.ApprovalControl,
    // Side coroutines for the duration of the task (helper connection state, muting and moved-away monitoring, state publishing); all cancelled when the task ends
    val side: CoroutineScope,
) {
    // Virtual display, web display and the like: owned by it the moment they are built, all released together during wrap-up
    val resources = ResourceScope()
    val traceTicket = traces.ticket()
    // Metered model requests in this run (including side-channel calls), recorded into the run record during wrap-up
    val tally = RunUsageTally(tokenBudget, if (showCost) DeepSeekRunCost() else null)
    // The phone side (built-in helper, accessibility, virtual display) is attached on the first launch_app: tasks that only use web and sandbox do not need them
    var connection: com.wangzan101.mika.cap.phone.shell.ShellBinding? = null
    var smsLogin: com.wangzan101.mika.cap.phone.sms.AndroidSmsLogin? = null
    var watcher: DeviceStateWatcher? = null
    var mute: AudioMute? = null
    // What becomes of each app marked by the model when the task ends (on the phone port); handed to the built-in helper during wrap-up
    var afterTask: com.wangzan101.mika.core.screen.uistate.AfterTaskMarks? = null
    var shellDeath: IBinder.DeathRecipient? = null
    var shellBinder: IBinder? = null
    var record: RunHandle? = null
    var runStore: RunStore? = null
    // Created only on the first URL opening; if it was never created, there is nothing to close
    var webStack: WebStack? = null
    // The current surface (phone/web) needs the dispatcher; the picture-in-picture frame source switches along with it
    var dispatcher: ToolDispatcher? = null
    var mcpEvents: AutoCloseable? = null
    var liveFeed: AutoCloseable? = null
    var approval: RunApproval? = null
    // App access boundary: shared by tools and the preview (built when the phone port is attached); when the window has not changed the check result is reused, so the preview need not query the window every frame
    var accessGuard: com.wangzan101.mika.core.screen.uistate.AppAccessScreenPort? = null
    var smsPrivacy: com.wangzan101.mika.core.cap.phone.sms.SmsPrivateScreen? = null

    suspend fun previewAllowed(): Boolean {
        if (smsPrivacy?.imagesPrivate == true) return false
        val guard = accessGuard ?: return false
        return try {
            guard.checkAppAccess()
            true
        } catch (e: CancellationException) { throw e }
        catch (_: AppAccessDenied) { false }
    }
}

internal class RunModels(
    val model: ChatModel,
    // The user asked about a page on the browser page: that page is lent to this task as the current web tab
    val pageLoan: PageLoans.Loan<WebTab>?,
    val light: ChatModel?,
    val decision: DecisionModel?,
    val routedLight: ChatModel?,
    val vision: ChatModel?,
    val visionFallbacks: List<ChatModel>,
    val lightFallbacks: List<ChatModel>,
    val knowledge: KnowledgeStore,
    val skills: GuideCatalog,
)

internal class RunPhone(
    val appAccess: com.wangzan101.mika.settings.AppAccessStore,
    val metrics: DisplayMetrics,
    val pace: AppPacePlan,
    val shellUp: MutableStateFlow<Boolean>,
    val states: DeviceStateWatcher,
    val phoneScreen: LazyPhoneScreen,
    val accessedPhone: com.wangzan101.mika.core.screen.uistate.AppAccessScreenPort,
    val privatePhone: com.wangzan101.mika.core.cap.phone.sms.SmsPrivateScreen,
)

internal class RunRecord(val sink: RunSink, val trace: RunTrace?)

internal class RunMemory(
    val apps: List<Pair<String, String>>,
    val speed: SpeedFlags,
    val switches: MemorySwitches,
    val memory: MemoryStore?,
    val taskMemory: TaskMemoryStore?,
    val profileSnapshot: List<ProfileEntry>,
    val utterances: UserUtterances,
    val catalog: GuideCatalog,
)

internal class RunPrompt(val userScreens: UserScreenSource, val basePrompt: String, val personaLabel: String?, val runSpec: RunSpec)

internal class RunTools(val runApproval: RunApproval, val promptHook: com.wangzan101.mika.plugin.PluginPromptHook, val tools: ToolDispatcher)
