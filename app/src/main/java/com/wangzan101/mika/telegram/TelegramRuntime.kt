package com.wangzan101.mika.telegram

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.net.NetProxy
import com.wangzan101.mika.core.run.ChannelGateway
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.MemoryOwnerIdentity
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.task.TaskService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.math.min

sealed interface TelegramConnectionState {
    data object Off : TelegramConnectionState
    data object NeedsSetup : TelegramConnectionState
    data object Connecting : TelegramConnectionState
    data object Connected : TelegramConnectionState
    data class Failed(val message: String) : TelegramConnectionState
}

/** Single bot, private-chat text: the Telegram runtime. Listening lives in a foreground service; tasks still only enter TaskService. */
class TelegramRuntime(
    private val context: Context,
    val store: TelegramStore,
    client: OkHttpClient? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = client ?: NetProxy.shared.client(OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS)
        // Long polling waits up to 30 s server-side; the read timeout must cover it.
        .readTimeout(60, TimeUnit.SECONDS))
    private val api = TelegramApi(http)
    private val owners = MemoryOwnerIdentity()
    private val app get() = context.applicationContext as MikaApp
    private val gateway = ChannelGateway(owners,
        { threadId -> app.taskState.run(threadId)?.activeAsk },
        object : ChannelGateway.Route {
            override suspend fun newTask(peer: ChannelPeer, messageId: String, text: String) =
                TaskService.startChannel(context, peer, messageId, text)

            override suspend fun paused(peer: ChannelPeer, messageId: String) =
                com.wangzan101.mika.task.replyPaused(app.outbox, channel, peer, messageId)

            override suspend fun answer(threadId: String, askId: String, text: String): Boolean =
                app.taskState.run(threadId)?.answerFromChannel(text, askId) == true

            override suspend fun confirm(threadId: String, askId: String, choice: ConfirmChoice) = false
            override suspend fun stop(runId: RunId) = TaskService.cancel(context, runId.value)
            override suspend fun cancelAsk(threadId: String) {
                val run = app.taskState.run(threadId) ?: return
                run.activeAsk?.askId?.let(run::cancelAsk)
            }
        }, com.wangzan101.mika.settings.MikaPause.get(context))

    val channel = TelegramChannel(store, api,
        activeAsk = { threadId -> app.taskState.run(threadId)?.activeAsk },
        imageReader = TelegramImageFiles(context))

    private var registration: AutoCloseable? = null
    private var monitor: Job? = null
    private val network = TelegramNetwork(context) { restartMonitor() }
    private val _state = MutableStateFlow<TelegramConnectionState>(
        if (store.configured()) TelegramConnectionState.Off else TelegramConnectionState.NeedsSetup)
    val state: StateFlow<TelegramConnectionState> = _state.asStateFlow()

    fun channelFor(id: String) = channel.takeIf { id == TelegramChannel.ID }
    fun acknowledge(messageId: String) = store.acknowledge(messageId)

    @Synchronized fun sync(): Boolean {
        if (FeatureFlags.get(context).quitRequested()) return false
        if (!store.enabled() || !store.configured()) {
            _state.value = if (store.configured()) TelegramConnectionState.Off else TelegramConnectionState.NeedsSetup
            return false
        }
        if (registration == null) {
            owners.bind(ChannelPeer(TelegramChannel.ID, store.chatId(), ""))
            registration = gateway.start(channel)
        }
        network.start()
        if (monitor?.isActive != true) {
            _state.value = TelegramConnectionState.Connecting
            monitor = scope.launch { monitorLoop() }
        }
        return true
    }

    @Synchronized fun stop() {
        monitor?.cancel()
        monitor = null
        registration?.close()
        registration = null
        network.stop()
        _state.value = if (store.configured()) TelegramConnectionState.Off else TelegramConnectionState.NeedsSetup
    }

    fun ensureRunning() {
        if (FeatureFlags.get(context).quitRequested() || !store.enabled() || !store.configured()) return
        _state.value = TelegramConnectionState.Connecting
        runCatching { context.startForegroundService(Intent(context, TelegramService::class.java)) }
            .onFailure { _state.value = TelegramConnectionState.Failed(it.message ?: it::class.simpleName.orEmpty()) }
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled) check(store.configured()) { "Set a Telegram bot token and chat ID first" }
        store.setEnabled(enabled)
        if (enabled) ensureRunning() else {
            context.stopService(Intent(context, TelegramService::class.java))
            stop()
        }
    }

    /** Save the bot credentials from Settings; the token is encrypted by the store. */
    fun saveBot(token: String, chatId: String) {
        store.saveBot(token.trim(), chatId.trim())
        if (store.enabled()) restartMonitor()
    }

    fun clearBot() {
        context.stopService(Intent(context, TelegramService::class.java))
        owners.unbind(ChannelPeer(TelegramChannel.ID, store.chatId(), ""))
        stop()
        store.clear()
        _state.value = TelegramConnectionState.NeedsSetup
    }

    /**
     * Sends a test message straight to the configured chat, bypassing the
     * receive switch: it verifies the token and chat id before enabling.
     * Returns false for any failure; the token is never logged.
     */
    suspend fun sendTestMessage(): Boolean = withContext(Dispatchers.IO) {
        val token = store.token() ?: return@withContext false
        val chatId = store.chatId().takeIf { it.isNotBlank() } ?: return@withContext false
        try {
            api.sendMessage(token, chatId, tr(R.string.telegram_test_message))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * The bot's @username, fetched once from Telegram with the saved token
     * and cached in the store; null when there is no token or Telegram
     * cannot be reached. The token is never logged.
     */
    suspend fun botUsername(): String? = withContext(Dispatchers.IO) {
        store.botUsername().takeIf { it.isNotBlank() }?.let { return@withContext it }
        val token = store.token() ?: return@withContext null
        try {
            api.getMe(token)?.also { store.saveBotUsername(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized private fun restartMonitor() {
        if (registration == null || !store.enabled()) return
        monitor?.cancel()
        monitor = scope.launch { monitorLoop() }
    }

    private suspend fun monitorLoop() {
        var failures = 0
        while (currentCoroutineContext().isActive && store.enabled()) {
            try {
                channel.pollOnce()
                failures = 0
                _state.value = TelegramConnectionState.Connected
            } catch (e: CancellationException) {
                throw e
            } catch (e: TelegramApiException) {
                if (e.httpCode == 401 || e.httpCode == 403) {
                    store.setEnabled(false)
                    _state.value = TelegramConnectionState.NeedsSetup
                    context.stopService(Intent(context, TelegramService::class.java))
                    return
                }
                failures++
                _state.value = TelegramConnectionState.Failed(e.message.orEmpty())
                delay(backoff(failures))
            } catch (e: Exception) {
                failures++
                Log.w(TAG, "Telegram polling failed", e)
                _state.value = TelegramConnectionState.Failed(e.message ?: e::class.simpleName.orEmpty())
                delay(backoff(failures))
            }
        }
    }

    private fun backoff(failures: Int) = min(30_000L, 1_000L * (1L shl (failures - 1).coerceAtMost(5)))

    companion object { private const val TAG = "MikaTelegram" }
}

private class TelegramNetwork(context: Context, private val changed: () -> Unit) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private var started = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
    }

    @Synchronized fun start() {
        if (started) return
        runCatching { manager.registerDefaultNetworkCallback(callback) }.onSuccess { started = true }
    }

    @Synchronized fun stop() {
        if (!started) return
        runCatching { manager.unregisterNetworkCallback(callback) }
        started = false
    }
}
