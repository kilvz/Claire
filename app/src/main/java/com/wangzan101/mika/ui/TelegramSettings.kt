package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.telegram.TelegramConnectionState
import com.wangzan101.mika.telegram.TelegramRuntime
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.CapabilityTag
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.StateCard
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings home status pill; a saved bot is not the same as a working connection. */
@Composable
internal fun TelegramEntryStatus(state: TelegramConnectionState, configured: Boolean) {
    val (text, tone) = when (state) {
        TelegramConnectionState.Connected -> R.string.telegram_status_connected to PillTone.Ok
        TelegramConnectionState.Connecting -> R.string.telegram_status_connecting to PillTone.Accent
        TelegramConnectionState.Off -> R.string.telegram_status_paused to PillTone.Neutral
        is TelegramConnectionState.Failed -> R.string.telegram_status_failed to PillTone.Warn
        TelegramConnectionState.NeedsSetup -> if (configured) {
            R.string.telegram_status_needs_setup to PillTone.Warn
        } else R.string.telegram_status_disconnected to PillTone.Neutral
    }
    StatusPill(stringResource(text), tone)
}

/** Settings › Telegram: the bot token and chat id are saved here, messages are still handled by the same runtime. */
@Composable
fun TelegramSettingsScreen(runtime: TelegramRuntime, onBack: () -> Unit) {
    val state by runtime.state.collectAsState()
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var chatId by remember { mutableStateOf(runtime.store.chatId()) }
    var tokenSaved by remember { mutableStateOf(runtime.store.hasToken()) }
    var showToken by remember { mutableStateOf(false) }
    var saveBusy by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var saveIsError by remember { mutableStateOf(false) }
    var actionBusy by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var testBusy by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var testIsError by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val tokenMissingText = stringResource(R.string.telegram_token_missing)
    val chatIdMissingText = stringResource(R.string.telegram_chat_id_missing)
    val savedText = stringResource(R.string.telegram_saved)
    val saveFailedText = stringResource(R.string.telegram_save_failed)
    val testSentText = stringResource(R.string.telegram_test_sent)
    val testFailedText = stringResource(R.string.telegram_test_failed)
    val actionFailedText = stringResource(R.string.telegram_action_failed)
    val configured = tokenSaved && chatId.isNotBlank()
    val receiving = state !is TelegramConnectionState.Off && state !is TelegramConnectionState.NeedsSetup

    // A removal or a rejected token elsewhere resets the saved values; typing is not disturbed otherwise.
    LaunchedEffect(state) {
        tokenSaved = runtime.store.hasToken()
        if (runtime.store.chatId() != chatId) chatId = runtime.store.chatId()
    }

    fun saveBot() {
        if (saveBusy) return
        val tokenToSave = token.trim().ifBlank { runtime.store.token() }
        if (tokenToSave.isNullOrBlank()) {
            saveMessage = tokenMissingText
            saveIsError = true
            return
        }
        if (chatId.trim().isBlank()) {
            saveMessage = chatIdMissingText
            saveIsError = true
            return
        }
        saveBusy = true
        saveMessage = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { runtime.saveBot(tokenToSave, chatId.trim()) }
                token = ""
                showToken = false
                tokenSaved = true
                saveMessage = savedText
                saveIsError = false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                saveMessage = saveFailedText
                saveIsError = true
            } finally {
                saveBusy = false
            }
        }
    }

    fun setReceiving(target: Boolean) {
        if (actionBusy || testBusy) return
        actionBusy = true
        actionError = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { runtime.setEnabled(target) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                actionError = actionFailedText
            } finally {
                actionBusy = false
            }
        }
    }

    fun sendTestMessage() {
        if (testBusy || actionBusy) return
        testBusy = true
        testMessage = null
        scope.launch {
            try {
                val sent = withContext(Dispatchers.IO) { runtime.sendTestMessage() }
                testMessage = if (sent) testSentText else testFailedText
                testIsError = !sent
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                testMessage = testFailedText
                testIsError = true
            } finally {
                testBusy = false
            }
        }
    }

    PageScaffold(stringResource(R.string.telegram_title), onBack) {
        val status = telegramStateCopy(state, configured)
        StateCard(MikaIcons.Chats, status.first, status.second, status.third, Modifier.testTag("telegram-status"))

        GroupLabel(stringResource(R.string.telegram_how))
        GroupCard {
            Text(
                stringResource(R.string.telegram_how_body), Modifier.padding(16.dp),
                style = MikaType.Body, color = MikaTheme.colors.ink2,
            )
        }

        GroupLabel(stringResource(R.string.telegram_connection))
        GroupCard {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    token, { token = it; saveMessage = null }, Modifier.fillMaxWidth().testTag("telegram-token"),
                    label = { Text(stringResource(R.string.telegram_bot_token)) },
                    placeholder = { Text(stringResource(R.string.telegram_bot_token_hint)) },
                    supportingText = if (tokenSaved) {
                        { Text(stringResource(R.string.telegram_bot_token_saved)) }
                    } else null,
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true, enabled = !saveBusy,
                )
                TextButton({ showToken = !showToken }, Modifier.fillMaxWidth(), enabled = !saveBusy) {
                    Text(stringResource(if (showToken) R.string.telegram_hide_token else R.string.telegram_show_token))
                }
                OutlinedTextField(
                    chatId, { chatId = it; saveMessage = null }, Modifier.fillMaxWidth().testTag("telegram-chat-id"),
                    label = { Text(stringResource(R.string.telegram_chat_id)) },
                    placeholder = { Text(stringResource(R.string.telegram_chat_id_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, enabled = !saveBusy,
                )
                Text(
                    stringResource(R.string.telegram_chat_id_help), style = MikaType.Caption,
                    color = MikaTheme.colors.ink2,
                )
                MikaButton(
                    stringResource(R.string.telegram_save), ::saveBot, Modifier.fillMaxWidth(),
                    enabled = !saveBusy,
                )
                saveMessage?.let { FooterNote(it, color = if (saveIsError) MikaTheme.colors.bad else MikaTheme.colors.ink2) }
            }
        }

        if (configured) {
            GroupCard {
                ListRow(
                    MikaIcons.Message, stringResource(R.string.telegram_receive),
                    stringResource(R.string.telegram_receive_sub), first = true,
                    onClick = if (actionBusy) null else ({ setReceiving(!receiving) }),
                ) {
                    MikaSwitch(receiving, null, Modifier.testTag("telegram-enabled"), enabled = !actionBusy)
                }
                ListRow(MikaIcons.Key, stringResource(R.string.telegram_bot_row)) {
                    RowValue(stringResource(R.string.telegram_bot_saved))
                }
                ListRow(MikaIcons.Chats, stringResource(R.string.telegram_chat_row)) {
                    RowValue(chatId)
                }
                ListRow(MikaIcons.Shield, stringResource(R.string.telegram_message_scope)) {
                    RowValue(stringResource(R.string.telegram_owner_private))
                }
            }
            MikaButton(
                stringResource(R.string.telegram_test_message_button), ::sendTestMessage, Modifier.fillMaxWidth(),
                kind = ButtonKind.Secondary, enabled = !testBusy && !actionBusy,
            )
            testMessage?.let { FooterNote(it, color = if (testIsError) MikaTheme.colors.bad else MikaTheme.colors.ink2) }
            TextButton({ confirmRemove = true }, Modifier.fillMaxWidth(), enabled = !actionBusy && !testBusy) {
                Text(stringResource(R.string.telegram_remove), color = MikaTheme.colors.bad)
            }
        }
        actionError?.let { FooterNote(it, color = MikaTheme.colors.bad) }

        GroupLabel(stringResource(R.string.telegram_capabilities))
        GroupCard {
            ListRow(null, stringResource(R.string.telegram_capability_text), first = true) {
                CapabilityTag(stringResource(R.string.telegram_supported))
            }
            ListRow(null, stringResource(R.string.telegram_capability_replies)) {
                CapabilityTag(stringResource(R.string.telegram_supported))
            }
            ListRow(null, stringResource(R.string.telegram_capability_phone), stringResource(R.string.telegram_capability_phone_sub)) {
                CapabilityTag(stringResource(R.string.telegram_phone_only))
            }
            ListRow(null, stringResource(R.string.telegram_capability_later)) {
                CapabilityTag(stringResource(R.string.telegram_later))
            }
        }
        FooterNote(stringResource(R.string.telegram_footer))
    }

    if (confirmRemove) {
        MikaDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.telegram_remove_title)) },
            text = { Text(stringResource(R.string.telegram_remove_body)) },
            confirmButton = {
                TextButton({
                    confirmRemove = false
                    actionBusy = true
                    actionError = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { runtime.clearBot() }
                            token = ""
                            chatId = ""
                            tokenSaved = false
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            actionError = actionFailedText
                        } finally {
                            actionBusy = false
                        }
                    }
                }) { Text(stringResource(R.string.telegram_remove), color = MikaTheme.colors.bad) }
            },
            dismissButton = { TextButton({ confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun telegramStateCopy(state: TelegramConnectionState, configured: Boolean): Triple<String, String, TileTone> = when (state) {
    TelegramConnectionState.Connected -> Triple(
        stringResource(R.string.telegram_status_connected), stringResource(R.string.telegram_state_connected_detail), TileTone.Ok)
    TelegramConnectionState.Connecting -> Triple(
        stringResource(R.string.telegram_status_connecting), stringResource(R.string.telegram_state_connecting_detail), TileTone.Accent)
    TelegramConnectionState.Off -> Triple(
        stringResource(R.string.telegram_status_paused), stringResource(R.string.telegram_state_paused_detail), TileTone.Neutral)
    is TelegramConnectionState.Failed -> Triple(
        stringResource(R.string.telegram_status_failed), stringResource(R.string.telegram_state_failed_detail), TileTone.Bad)
    TelegramConnectionState.NeedsSetup -> if (configured) Triple(
        stringResource(R.string.telegram_status_needs_setup), stringResource(R.string.telegram_state_needs_setup_detail), TileTone.Warn)
    else Triple(
        stringResource(R.string.telegram_status_disconnected), stringResource(R.string.telegram_state_disconnected_detail), TileTone.Neutral)
}
