package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.ui.components.DirectionalIcon
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.task.ConfirmationCopy
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaCard
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.viewer.FileViewer
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voice.VoicePlayback
import kotlinx.coroutines.launch
import com.wangzan101.mika.settings.ui.StatusCopy
import com.wangzan101.mika.ui.MarkdownText

/**
 * Which task the buttons on the action cards (takeover, allow once, pause) apply to: a question in the chat page itself uses [of],
 * while a running task (by its id) is provided by the main chat's question card, and tapping only affects that one.
 */
internal class NeedOps(
    val cancel: () -> Unit, val resume: () -> Unit, val takeOver: () -> Unit, val handBack: () -> Unit,
    val finishedMyself: () -> Unit, val skip: () -> Unit, val approve: (Int) -> Unit, val decline: (Int) -> Unit,
) {
    companion object {
        fun of(vm: ChatViewModel) = NeedOps(vm::cancel, vm::resume, vm::takeOver, vm::handBack, vm::finishedMyself, vm::skip, vm::approve, vm::decline)
    }
}

/** The three cards for takeover, allow once and pause; when [title] is given it overrides the default title (the main chat's question card carries the task's name). Other states do not use this. */
@Composable
internal fun NeedStatusCard(status: ExchangeStatus, stage: Takeover.Stage, ops: NeedOps, title: String? = null) {
    when (status) {
        is ExchangeStatus.NeedsTakeover -> {
            val label = takeoverReasonLabel(status.reason)
            val heading = title ?: if (label != null) stringResource(R.string.takeover_title_reason, stringResource(label)) else stringResource(R.string.takeover_title)
            val handedOver = stage == Takeover.Stage.HandedOver
            val text = if (handedOver) stringResource(R.string.takeover_handed_over_hint) else status.message
            // The item, amount and address being paid for each get their own row: this card is the confirmation before paying
            val body = listOfNotNull(text, StatusCopy.paymentLines(status.item, status.amount, status.address)).joinToString("\n")
            NeedCard(heading, body, footer = { TextLink(stringResource(R.string.action_cancel_task), ops.cancel) }) {
                if (handedOver) {
                    CardButton(stringResource(R.string.action_finished_myself), onClick = ops.finishedMyself)
                    CardButton(stringResource(R.string.action_hand_back), primary = true, onClick = ops.handBack)
                } else {
                    CardButton(stringResource(R.string.action_skip), onClick = ops.skip)
                    CardButton(stringResource(R.string.action_take_over), primary = true, onClick = ops.takeOver)
                }
            }
        }
        // Only actions that spend money need your approval, and each approval covers only this one time
        is ExchangeStatus.NeedsConfirmation -> NeedCard(status.title, status.body + "\n" + ConfirmationCopy.NOTE) {
            CardButton(stringResource(R.string.chat_refuse), onClick = { ops.decline(status.key) })
            CardButton(stringResource(R.string.chat_allow_once), primary = true, onClick = { ops.approve(status.key) })
        }
        is ExchangeStatus.Paused -> NeedCard(title ?: stringResource(if (status.resumable) R.string.chat_paused else R.string.chat_waiting_for_you), status.why) {
            CardButton(stringResource(R.string.action_cancel_task), onClick = ops.cancel)
            if (status.resumable) CardButton(stringResource(R.string.chat_continue), primary = true, onClick = ops.resume)
        }
        else -> Unit
    }
}

@StringRes
fun takeoverReasonLabel(reason: String): Int? = when (reason) {
    "captcha" -> R.string.takeover_reason_captcha
    "login" -> R.string.takeover_reason_login
    "access_blocked" -> R.string.takeover_reason_access_blocked
    "payment" -> R.string.takeover_reason_payment
    "decision" -> R.string.takeover_reason_decision
    else -> null
}

/** The current state of one question-and-answer exchange: what it needs from you is a white card ([NeedCard]), while results are laid out directly on the background. */
@Composable
internal fun StatusCard(
    status: ExchangeStatus, stage: Takeover.Stage, vm: ChatViewModel, runId: String?,
    /** Used by the answer's long-press menu to identify this entry (see [MessageArea]). */
    key: String,
    /** Opening a /workspace reference card in the answer must go through this conversation's workspace. */
    conversationId: String?,
    recover: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    when (status) {
        ExchangeStatus.Working -> Unit
        ExchangeStatus.WaitingUnlock -> NeedCard(stringResource(R.string.chat_wait_unlock), stringResource(R.string.chat_unlock_body))
        is ExchangeStatus.NeedsTakeover -> NeedStatusCard(status, stage, NeedOps.of(vm))
        is ExchangeStatus.NeedsInput -> {
            val options = status.options.orEmpty()
            val note = stringResource(R.string.no_answer_note, TaskService.INPUT_TIMEOUT_MINUTES)
            NeedCard(
                stringResource(R.string.input_title), stringResource(R.string.chat_input_body, status.prompt, note),
                options = if (options.isEmpty()) null else ({
                    options.forEach { o -> MikaButton(o, { vm.answer(o) }, Modifier.fillMaxWidth(), kind = ButtonKind.Tonal, size = ButtonSize.Medium) }
                }),
            ) {
                if (options.isEmpty()) CardButton(stringResource(R.string.action_cancel_task), onClick = vm::cancel)
            }
        }
        is ExchangeStatus.NeedsConfirmation, is ExchangeStatus.Paused -> NeedStatusCard(status, stage, NeedOps.of(vm))
        // Queued and waiting to start: it starts automatically when its turn comes, and continue cannot jump the queue, so only cancel is offered
        is ExchangeStatus.Queued -> NeedCard(stringResource(R.string.chat_queued), status.why) {
            CardButton(stringResource(R.string.action_cancel_task), onClick = vm::cancel)
        }
        // Long-pressing the whole answer opens a menu; copy and quote take only the answer text, without the notice above it. Same grey block as the front desk's replies: no header, no tail buttons, short ones wrap to the text width
        is ExchangeStatus.Done -> Box(Modifier.padding(bottom = ReplyGap)) {
            AnswerArea(key, status.answer, Modifier.answerSurface()) {
                Column {
                    status.unverifiedLine?.let { line ->
                        Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(18.dp).background(MikaTheme.colors.warnSoft, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(MikaIcons.Exclaim, null, Modifier.size(11.dp), tint = MikaTheme.colors.warn)
                            }
                            Text(line, style = MikaType.Caption, fontWeight = FontWeight.SemiBold, color = MikaTheme.colors.ink2)
                        }
                    }
                    MarkdownText(status.answer, AnswerText.copy(color = MikaTheme.colors.ink))
                    OutputRefs(status.answer, conversationId) { item ->
                        val id = conversationId ?: return@OutputRefs
                        scope.launch { if (!AttachFiles.openSent(context, id, item.path)) Toast.makeText(context, AttachCopy.GONE, Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        }
        is ExchangeStatus.Failed -> Column(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(MikaTheme.colors.bad, CircleShape))
                Text(stringResource(R.string.chat_not_done), style = MikaType.BodyStrong, color = MikaTheme.colors.ink)
            }
            MarkdownText(status.reason, AnswerText.copy(color = MikaTheme.colors.ink), Modifier.padding(top = 6.dp).answerSurface())
            RecoveryActions(runId, recover)
        }
        // The wrap-up added after stopping is treated as this turn's answer and is placed below "Stopped"; while it is still being written, a single line of small text is shown
        is ExchangeStatus.Cancelled -> Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Text(stringResource(R.string.chat_stopped), Modifier.padding(start = 2.dp), style = MikaType.Body, color = MikaTheme.colors.ink2)
            if (status.summarizing) Text(
                StatusCopy.STOP_SUMMARIZING, Modifier.padding(start = 2.dp, top = 4.dp), style = MikaType.Caption, color = MikaTheme.colors.ink3,
            )
            status.summary?.let { summary ->
                Box(Modifier.padding(top = 8.dp)) {
                    AnswerArea(key, summary, Modifier.answerSurface()) {
                        Column {
                            MarkdownText(summary, AnswerText.copy(color = MikaTheme.colors.ink))
                            OutputRefs(summary, conversationId) { item ->
                                val id = conversationId ?: return@OutputRefs
                                scope.launch { if (!AttachFiles.openSent(context, id, item.path)) Toast.makeText(context, AttachCopy.GONE, Toast.LENGTH_SHORT).show() }
                            }
                        }
                    }
                }
            }
            RecoveryActions(runId, recover)
        }
    }
}

/** Audio generated in this turn: name/start of the script, duration, play/stop; tapping the whole card plays it, tapping again stops. */
@Composable
internal fun GeneratedAudioCards(audios: List<com.wangzan101.mika.core.chat.GeneratedAudio>, vm: ChatViewModel) {
    val playback by vm.voice.collectAsState()
    val playing = playback is VoicePlayback.Playing || playback is VoicePlayback.Loading
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        audios.forEach { a ->
            Row(
                Modifier.fillMaxWidth().cardSurface(c, MikaShapes.Card).clip(MikaShapes.Card)
                    .clickable(role = Role.Button) { if (playing) vm.stopSpeaking() else vm.playAudio(a.path) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(36.dp).background(c.neutralSoft, FileTileShape), contentAlignment = Alignment.Center) {
                    Icon(MikaIcons.Sound, null, Modifier.size(20.dp), tint = c.ink)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        a.text?.lineSequence()?.firstOrNull()?.take(80)?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_audio),
                        style = FileName, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            a.durationMs?.let { secondsLabel(it) },
                            stringResource(if (playing) R.string.chat_audio_stop else R.string.chat_audio_play),
                        ).joinToString(" · "),
                        style = FileMeta, color = if (playing) c.accent else c.ink3, maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 这一轮生成的视频:首帧封面、时长、点开用系统播放器。 */
@Composable
internal fun GeneratedVideoCards(videos: List<com.wangzan101.mika.core.chat.GeneratedVideo>, vm: ChatViewModel) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        videos.forEach { v ->
            val poster by produceState<android.graphics.Bitmap?>(null, v.path) { value = vm.loadVideoPoster(v.path) }
            Row(
                Modifier.fillMaxWidth().cardSurface(c, MikaShapes.Card).clip(MikaShapes.Card)
                    .clickable(role = Role.Button) { vm.playVideo(v.path) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(64.dp, 40.dp).background(c.neutralSoft, FileTileShape).clip(FileTileShape),
                    contentAlignment = Alignment.Center,
                ) {
                    val frame = poster
                    if (frame != null) Image(frame.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Icon(MikaIcons.Camera, null, Modifier.size(20.dp), tint = c.ink)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        v.prompt?.lineSequence()?.firstOrNull()?.take(80)?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.chat_video),
                        style = FileName, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            v.durationMs?.let { secondsLabel(it) },
                            v.width.takeIf { it > 0 }?.let { w -> "$w×${v.height}" },
                            stringResource(R.string.chat_video_play),
                        ).joinToString(" · "),
                        style = FileMeta, color = c.ink3, maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun secondsLabel(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** After a terminal state the old run context has already been released: both buttons start a new run, and the only difference is whether the confirmed progress from the summary is carried over. */
@Composable
private fun RecoveryActions(runId: String?, recover: (Boolean) -> Unit) {
    if (runId == null) return
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CardButton(stringResource(R.string.chat_restart_task), onClick = { recover(false) })
        CardButton(stringResource(R.string.chat_resume_checkpoint), primary = true, onClick = { recover(true) })
    }
}

/**
 * The "needs you" card (needs your attention, question, confirmation, pause, lock screen, blocked): a white card with an orange dot before the title and the explanation below;
 * [options] is a column of tappable answers; buttons sit side by side, secondary on the left (light) and primary on the right (blue); [footer] at the very bottom.
 */
@Composable
internal fun NeedCard(
    title: String,
    body: String,
    footer: (@Composable () -> Unit)? = null,
    options: (@Composable () -> Unit)? = null,
    buttons: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = MikaTheme.colors
    MikaCard(Modifier.padding(top = 8.dp, bottom = 12.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(c.warnSolid, CircleShape))
                Text(title, style = NeedTitle, color = c.ink)
            }
            Text(body, Modifier.padding(top = 6.dp), style = CardBody, color = c.ink2)
            if (options != null) {
                Column(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { options() }
            }
            if (buttons != null) {
                Row(
                    Modifier.padding(top = 14.dp).fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) { buttons() }
            }
            footer?.let { Box(Modifier.padding(top = 10.dp).fillMaxWidth(), contentAlignment = Alignment.Center) { it() } }
        }
    }
}

@Composable
internal fun RowScope.CardButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
    MikaButton(
        text, onClick, Modifier.weight(1f).fillMaxHeight(),
        kind = if (primary) ButtonKind.Primary else ButtonKind.Tonal, size = ButtonSize.Medium, maxLines = 2,
    )
}

@Composable
internal fun TextLink(text: String, onClick: () -> Unit) {
    Text(text, Modifier.clip(MikaShapes.Pill).clickable(onClick = onClick).padding(4.dp), style = MikaType.Caption, color = MikaTheme.colors.ink3)
}

/** A file exported to "Downloads/Claire": a small card with name, size and location; tapping opens it in the app when viewable ([FileViewer]), otherwise it is handed to a system app that can open it. */
@Composable
internal fun FileCards(files: List<SharedFile>) {
    val context = LocalContext.current
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in files) {
            Row(
                Modifier.fillMaxWidth().cardSurface(c, MikaShapes.Card).clip(MikaShapes.Card)
                    .clickable(onClickLabel = stringResource(R.string.chat_open), role = Role.Button) { openShared(context, f) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp).background(c.neutralSoft, FileTileShape), contentAlignment = Alignment.Center) {
                    Icon(MikaIcons.Doc, null, Modifier.size(20.dp), tint = c.ink)
                }
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = FileName, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${ChatViewModel.sizeLabel(f.size)} · ${f.savedAs.substringBeforeLast('/')}",
                        style = FileMeta, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                DirectionalIcon(MikaIcons.Chevron, null, Modifier.size(MikaSize.SmallIcon), tint = c.ink3)
            }
        }
    }
}

private fun openShared(context: Context, f: SharedFile) = FileViewer.open(context, Uri.parse(f.uri), f.name)

// ---------- Composer ----------
