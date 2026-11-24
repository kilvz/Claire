package com.wangzan101.mika.mcp

import com.wangzan101.mika.core.mcp.McpRpc
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.core.remote.RemoteAccess
import com.wangzan101.mika.core.remote.RemoteActivity
import com.wangzan101.mika.core.remote.RemoteHost
import com.wangzan101.mika.core.remote.RemoteEndpoints
import com.wangzan101.mika.core.remote.RelayClient
import com.wangzan101.mika.core.remote.RelayGateway
import com.wangzan101.mika.settings.RelaySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.wangzan101.mika.core.mcp.McpUsb
import com.wangzan101.mika.core.device.shell.HelperHandoff
import com.wangzan101.mika.device.AndroidDeviceAccess
import com.wangzan101.mika.core.remote.RemoteTasks
import com.wangzan101.mika.core.remote.RunExportSource
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.task.RemoteRunExport
import com.wangzan101.mika.task.RemoteTaskHub
import com.wangzan101.mika.settings.McpSettings
import com.wangzan101.mika.settings.McpUsbSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/** The current state of computer access: off, listening on some port, or failed to start (port already taken, etc.). */
sealed interface McpServerState {
    data object Off : McpServerState
    data class Listening(val port: Int) : McpServerState
    data class Failed(val message: String) : McpServerState
}

/**
 * Switches and service for computer access (MCP): a single instance for the whole process. The listening scope of the LAN ([settings]) and USB ([usb]) is described in [McpModes],
 * the remote gateway ([relaySettings]) uses a separate outbound channel. When any one of them is on, the [McpService] foreground service is started,
 * with each switch deciding its own local listening and outbound connection; when all are off it stops. Tasks from all three modes share [tasks] and the same protocol entry point.
 */
class McpRuntime(
    private val context: Context, val settings: McpSettings, val usb: McpUsbSettings, private val tasks: RemoteTasks,
    val device: AndroidDeviceAccess,
    val relaySettings: RelaySettings,
    private val history: com.wangzan101.mika.core.remote.RemoteHistory? = null,
    calls: com.wangzan101.mika.core.calls.PhoneCallPort? = null,
    companion: com.wangzan101.mika.core.remote.RemoteCompanion? = null,
) {
    private val _state = MutableStateFlow<McpServerState>(McpServerState.Off)
    val state: StateFlow<McpServerState> = _state.asStateFlow()
    @Volatile private var host: RemoteHost? = null
    private var hostListen = McpModes.Listen.Off
    private val records: RunExportSource? = (tasks as? RemoteTaskHub)?.let { hub -> RemoteRunExport(hub) { RunStore(context) } }
    private val endpoints = RemoteEndpoints(tasks, BuildConfig.VERSION_NAME, device = device.access, history = history, records = records, calls = calls, companion = companion)
    private val relayAccess = RemoteAccess(token = { if (relaySettings.config.value.enabled) settings.token() else null })
    // Requests that arrive through the gateway only see screen capabilities if "allow waking and unlocking through the gateway" is enabled as well; the phone's remote screen-on and unlock permissions still have to be granted too.
    private val relay = RelayClient(CoroutineScope(SupervisorJob() + Dispatchers.IO), { req ->
        relayAccess.handle(req) { endpoints.handle(it, deviceAccess = relaySettings.config.value.deviceAccess, historyAccess = false) }
    })
    private val relayNetwork = RelayNetwork(context, relay::networkChanged)
    val relayState get() = relay.state
    private val active get() = listen() != McpModes.Listen.Off || relaySettings.config.value.enabled

    /** The most recently connected client and the last request; empty when not listening. */
    val activity: RemoteActivity get() = if (active) endpoints.activity.value else RemoteActivity()

    /** Pairing only stores credentials; the user must enable remote access separately. */
    suspend fun pairRelay(gateway: String, code: String) {
        check(!relaySettings.config.value.paired) { "Unpair the previous gateway first" }
        val remote = RelayGateway(RelayGateway.parse(gateway), RelayGateway.http())
        val paired = remote.pair(code)
        try { relaySettings.save(paired) }
        catch (e: Exception) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { runCatching { remote.unpair(paired.deviceToken) } }
            throw e
        }
    }

    fun setRelayDeviceAccess(on: Boolean) = relaySettings.setDeviceAccess(on)

    fun setRelayEnabled(on: Boolean) {
        if (!on) { relayNetwork.stop(); relay.stop() }
        try { relaySettings.setEnabled(on) } finally { if (!on) apply() }
        if (on) apply()
    }

    suspend fun unpairRelay() {
        setRelayEnabled(false)
        relaySettings.pairing()?.let { RelayGateway(RelayGateway.parse(it.gateway), RelayGateway.http()).unpair(it.deviceToken) }
        relaySettings.clear()
    }

    fun listen(): McpModes.Listen = McpModes.listen(settings.enabled.value, usb.enabled.value)

    /** The switch on the settings page for the LAN mode. */
    fun setEnabled(on: Boolean) {
        settings.setEnabled(on)
        apply()
    }

    /** The switch on the settings page for the USB mode (toggling it from the computer over adb goes through the [McpService] action, the same set). */
    fun setUsbEnabled(on: Boolean) {
        if (on) usb.enable() else usb.disable()
        apply()
    }

    /** Change the listening port: store it, and rewrite the script that runs on the computer for the new port; if any connection is on, restart the service to rebind on the new port and republish the connection info. */
    fun setPort(port: Int) {
        settings.setPort(port)
        writeUsbScript()
        apply()
    }

    /** A switch changed: if any connection is still on, have the service listen again according to the new switches; if all are off, stop the service. */
    private fun apply() {
        if (active) ensureRunning() else {
            context.stopService(Intent(context, McpService::class.java))
            _state.value = McpServerState.Off
        }
    }

    /**
     * If any of them is on, bring the service up (if it is already running, make it re-align with the switches). Starting a foreground service from the background may be refused by the system; in that case record it as not started
     * and start it again the next time Claire's UI is opened.
     */
    fun ensureRunning() {
        if (!active || com.wangzan101.mika.settings.FeatureFlags.get(context).quitRequested()) return
        runCatching { context.startForegroundService(Intent(context, McpService::class.java)) }
            .onFailure { e ->
                Log.w(TAG, "could not start computer access service", e)
                _state.value = McpServerState.Failed(e.message ?: e::class.simpleName.orEmpty())
            }
    }

    /**
     * Called by [McpService]: synchronizes local listening and the remote channel. Reuses the service when the listening scope is unchanged, and publishes the connection info when USB is on.
     * Returns false when neither local listening nor the remote channel is enabled or could be started.
     */
    @Synchronized internal fun sync(): Boolean {
        if (com.wangzan101.mika.settings.FeatureFlags.get(context).quitRequested()) return false
        val want = listen()
        // A changed port must also rebind (RemoteHost binds an exact port; boundPort is the port actually being listened on)
        if (host != null && (hostListen != want || host!!.boundPort != settings.port)) stopListener()
        if (want == McpModes.Listen.Off) _state.value = McpServerState.Off
        val ok = want != McpModes.Listen.Off && (host != null || startServer(want))
        if (ok && usb.enabled.value) runCatching { usb.publish(host!!.boundPort) }.onFailure { Log.w(TAG, "cannot write USB connection info", it) }
        else usb.unpublish()
        val remoteOn = relaySettings.config.value.enabled
        if (remoteOn) {
            val pairing = relaySettings.pairing()
            if (pairing != null) { relayNetwork.start(); relay.start(pairing) }
            else { relayNetwork.stop(); relay.stop(); relaySettings.setEnabled(false) }
        } else { relayNetwork.stop(); relay.stop() }
        return ok || remoteOn && relaySettings.config.value.enabled
    }

    private fun startServer(want: McpModes.Listen): Boolean {
        val network = want == McpModes.Listen.PrivateNetworks
        val h = RemoteHost(tasks, { if (settings.enabled.value) settings.token() else null }, settings.port, allowAllInterfaces = network,
            serverVersion = BuildConfig.VERSION_NAME, onError = { Log.w(TAG, "computer access server error", it) },
            loopbackToken = usb::token, device = device.access, endpoints = endpoints)
        return try {
            h.start()
            host = h
            hostListen = want
            _state.value = McpServerState.Listening(h.boundPort)
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not listen on port ${settings.port}", e)
            _state.value = McpServerState.Failed(e.message ?: e::class.simpleName.orEmpty())
            false
        }
    }

    @Synchronized internal fun stopServer() {
        relayNetwork.stop()
        relay.stop()
        stopListener()
    }

    private fun stopListener() {
        host?.stop()
        host = null
        hostListen = McpModes.Listen.Off
        if (_state.value is McpServerState.Listening) _state.value = McpServerState.Off
    }

    /** The "connect over USB" script for the computer to run: carries no secrets, written once when the app starts (skipped if external storage is unavailable). */
    fun writeUsbScript() {
        val dir = context.getExternalFilesDir(null) ?: return
        val f = File(dir, McpUsb.SCRIPT)
        val text = McpUsb.script(
            scriptPath = shortPath(f.absolutePath),
            helperScript = File(dir, HelperHandoff.USB_SCRIPT).absolutePath,
            infoPath = File(dir, McpUsb.INFO_FILE).absolutePath,
            component = "${context.packageName}/${McpService::class.java.name}",
            onAction = McpModes.ACTION_USB_ON, offAction = McpModes.ACTION_USB_OFF, port = settings.port,
        )
        if (runCatching { f.readText() }.getOrNull() == text) return
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            check(tmp.renameTo(f)) { "rename failed" }
        }
    }

    /** The command to run on the computer (this is exactly what the settings page shows). */
    fun usbCommand(): String {
        val path = context.getExternalFilesDir(null)?.let { File(it, McpUsb.SCRIPT).absolutePath }
            ?: "/storage/emulated/0/Android/data/${context.packageName}/files/${McpUsb.SCRIPT}"
        return "adb shell sh " + shortPath(path)
    }

    /** Rotate the token: the old one stops working immediately. */
    fun regenerateToken(): String = settings.regenerate()

    companion object {
        private const val TAG = "MikaMcp"

        /** The primary user's external storage is also called /sdcard in the shell; user-facing commands use the short form (same as the "start from the computer" script). */
        private fun shortPath(path: String) = HelperHandoff.usbCommand(path).removePrefix("adb shell sh ")

        /** Addresses the computer can connect to: LAN and private-network IPv4 addresses on interfaces that are up. */
        fun addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && RemoteAccess.privateAddress(it) }
                .mapNotNull { it.hostAddress }
                .distinct()
        }.getOrDefault(emptyList())

        fun url(address: String, port: Int) = "http://$address:$port${McpRpc.PATH}"

        /** Config snippet for an MCP client on the computer: the URL plus an Authorization header. */
        fun clientConfig(url: String, token: String): String = """
            {
              "mcpServers": {
                "mika": {
                  "type": "http",
                  "url": "$url",
                  "headers": { "Authorization": "Bearer $token" }
                }
              }
            }
        """.trimIndent()
    }
}
