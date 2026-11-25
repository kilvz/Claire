package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.safety.gate.ConfirmationRequest
import com.wangzan101.mika.core.screen.observation.Observation
import com.wangzan101.mika.core.screen.observation.Place
import com.wangzan101.mika.core.safety.gate.IrrevAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Signal that terminate/interrupt-style tools give to the Agent loop. Normal completion does not go through tools: a reply without tool calls is the answer to the user. */
sealed interface Outcome {
    data class Failure(val message: String, val subtype: String = "interaction_failed", val recoverable: Boolean = false) : Outcome
    /** On payment, [item]/[amount]/[address] are listed separately on the takeover card, and the card itself is the confirmation of payment. */
    data class Takeover(
        val reason: String, val message: String,
        val item: String? = null, val amount: String? = null, val address: String? = null,
    ) : Outcome
    data class Input(val prompt: String, val options: List<String>? = null) : Outcome
    /** The model declared an irreversible action; [foregroundApp] is the foreground package name read at declaration time, null if unreadable. */
    data class Confirm(val request: ConfirmationRequest, val foregroundApp: String?) : Outcome
}

/**
 * [changed]: whether the screen changed around the action as verified by the port; null when not verified.
 * [frame]: screen capture for the run log only (not sent to the model); used when [image] is not empty.
 * [images]: images sent to the model with the result (may be several when recalling past screenshots); [image] is the first of them.
 * [record]: fields that only go into the run log and are not sent to the model (e.g. the port id behind a stable id).
 * [screenElements]: the complete element list of the screen bundled with a write operation's result (only the diff was sent); the anti-stall guard compares screens by it.
 * [imageIds]: screen ids corresponding one-to-one to [images] (only for UI screens; read image files are null, and missing ones count as null).
 * [observations]: what was read during this call (supplied by the capability, see [Observation]); the dispatcher records them into the evidence ledger and then substitutes the ledger entries (with ids).
 * [place]: where the task is after this call (given by the capability side from the result); null if not stated (the previous one carries over).
 * [waitMs]: time the capability deliberately paused within this call for pacing (the extra dwell added when a page is turned before it has been viewed long enough); the run log counts it as waiting.
 * [followUpTools]: actions made useful by this result; expose their tools for the next model turn.
 */
class ToolExecution(
    val content: JsonObject,
    image: ByteArray? = null,
    val changed: Boolean? = null,
    val outcome: Outcome? = null,
    val frame: ByteArray? = null,
    val images: List<ByteArray> = listOfNotNull(image),
    val record: JsonObject = JsonObject(emptyMap()),
    val screenElements: JsonArray? = null,
    val imageIds: List<Int?> = emptyList(),
    val observations: List<Observation> = emptyList(),
    val place: Place? = null,
    val waitMs: Long = 0,
    val followUpTools: List<Verb> = emptyList(),
    val playback: com.wangzan101.mika.core.run.record.PlaybackCapture? = null,
) {
    val image: ByteArray? get() = images.firstOrNull()

    /** Replace a few of them; the rest (including observed observations) stay as they are. */
    fun with(
        content: JsonObject = this.content, changed: Boolean? = this.changed, frame: ByteArray? = this.frame, images: List<ByteArray> = this.images,
        record: JsonObject = this.record, screenElements: JsonArray? = this.screenElements, imageIds: List<Int?> = this.imageIds,
        observations: List<Observation> = this.observations, place: Place? = this.place, waitMs: Long = this.waitMs,
        followUpTools: List<Verb> = this.followUpTools,
        playback: com.wangzan101.mika.core.run.record.PlaybackCapture? = this.playback,
    ) = ToolExecution(content, changed = changed, outcome = outcome, frame = frame, images = images, record = record,
        screenElements = screenElements, imageIds = imageIds, observations = observations, place = place, waitMs = waitMs, followUpTools = followUpTools, playback = playback)

    /** Additionally observed [more]. */
    fun observing(vararg more: Observation?): ToolExecution = more.filterNotNull().let { if (it.isEmpty()) this else with(observations = observations + it) }
}

/** Android package name: segments separated by dots, the first segment starting with a letter. Shared by launch_app, confirmation declarations and app deep links. */
val PACKAGE_NAME_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

/** Recall keeps the most recent screen reads; one recall recalls at most this many. */
internal const val RECALL_KEEP = 20
internal const val RECALL_MAX_IDS = 5

val FAILURE_SUBTYPES = listOf("blocked", "login_incomplete", "captcha_incomplete", "route_failed", "device_unavailable", "interaction_failed")
/** request_confirmation's target/content are truncated to this length (written by the model, the UI must display them verbatim). */
const val CONFIRM_FIELD_MAX = 200
/** Actions accepted by request_confirmation; payment goes through hand_to_user. */
val CONFIRM_ACTIONS = IrrevAction.entries.filter { it != IrrevAction.pay }.map { it.name }
val BARRIER_KINDS = listOf("popup", "login", "captcha", "access_blocked", "payment")
val TAKEOVER_REASONS = listOf("captcha", "login", "access_blocked", "payment", "decision", "other")
/** Takeover reason wordings commonly seen in skill docs plus the pre-rename reasons; they are mapped here to the reasons above, so imported docs and saved skills need no change. */
val TAKEOVER_REASON_ALIASES = mapOf("password_required" to "login", "2fa" to "login", "manual_review" to "other", "risk_control" to "access_blocked")

/** Outcome of a takeover. aborted means the user cancelled the whole task. */
enum class HandBackOutcome { complete, skipped, timeout, aborted }

/**
 * Result of a takeover, given to the model as the tool result of hand_to_user.
 * [userFinished]: the user says they have done it themselves; [durationMs] how long the user held it; [inputCount] how many operations the user made in that app;
 * [stoppedAt] the page of that app at the moment of hand-back. null when unknown.
 */
data class HandBack(
    val outcome: HandBackOutcome, val userFinished: Boolean = false,
    val durationMs: Long? = null, val inputCount: Int? = null, val stoppedAt: String? = null,
    /** For a web takeover hand-back, the address where the user last stopped; null for an app takeover. */
    val url: String? = null,
    /** Note the user wrote when handing back (the user's own words, usable as a source for memory). */
    val note: String? = null,
    /** 用户已交回操作权,目标 App 恢复失败时给模型的原因。 */
    val appReturnFailure: String? = null,
)


