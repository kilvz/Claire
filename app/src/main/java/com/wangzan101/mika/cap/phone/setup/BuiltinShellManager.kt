package com.wangzan101.mika.cap.phone.setup

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import com.wangzan101.mika.core.device.adb.AdbConnectPort
import com.wangzan101.mika.core.device.adb.AdbConnection
import com.wangzan101.mika.core.device.adb.ConnectCandidates
import com.wangzan101.mika.core.device.adb.AdbPairing
import com.wangzan101.mika.core.device.adb.AdbServiceTypes
import com.wangzan101.mika.core.device.adb.AdbTls
import com.wangzan101.mika.core.device.adb.LocalPortScan
import com.wangzan101.mika.core.device.adb.NioPortScanner
import com.wangzan101.mika.core.device.adb.PortCache
import com.wangzan101.mika.core.device.shell.BuiltinShellEngine
import com.wangzan101.mika.core.device.shell.BuiltinShellFailure
import com.wangzan101.mika.core.device.shell.BuiltinShellPlatform
import com.wangzan101.mika.core.device.shell.HelperHandoff
import com.wangzan101.mika.core.device.shell.PermissionCommands
import com.wangzan101.mika.core.device.shell.RecoveryCapability
import com.wangzan101.mika.core.device.shell.RecoveryConsent
import com.wangzan101.mika.core.device.shell.WirelessDebuggingAutoOn
import com.wangzan101.mika.core.device.shell.ShellCommandRunner
import com.wangzan101.mika.core.device.shell.ShellTransport
import com.wangzan101.mika.core.device.shell.ShizukuPlatformState
import com.wangzan101.mika.core.device.shell.SuAnswer
import com.wangzan101.mika.core.device.shell.SuProbe
import com.wangzan101.mika.cap.phone.shell.HelperHub
import com.wangzan101.mika.cap.phone.shell.adb.AndroidAdb
import com.wangzan101.mika.cap.phone.shell.adb.NsdAdbServiceFinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * The real implementation of the built-in helper: pairing, starting the helper through the local adbd or su, and the watchdog. Created once by [com.wangzan101.mika.cap.phone.PhoneServices];
 * [start] begins watching the helper when the App starts; [hub] is the endpoint where the helper hands over its Binder; [consent] is the user's choice for those capabilities ("auto-enable wireless debugging" reads it).
 */
class BuiltinShellManager(context: Context, private val hub: HelperHub, private val consent: () -> RecoveryConsent) {
    private val app = context.applicationContext
    private var started = false

    private val _scanningPorts = MutableStateFlow(false)
    /** Currently scanning this device for the wireless debugging port (the guide page shows "searching on this device…"). */
    val scanningPorts: StateFlow<Boolean> = _scanningPorts.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The "start the phone helper with root" and "allow Claire to run root commands" switches on a rooted phone; when either changes the watchdog retries under the new conditions. */
    val root = RootControl(app, scope) { engine.rootChanged() }

    /** The official Shizuku API channel: checks installation / running / authorization and binds the UserService. */
    val shizuku = ShizukuController(app, hub, scope) { engine.shizukuChanged() }

    private val _transport = MutableStateFlow(
        ShellTransport.parse(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TRANSPORT, null)),
    )
    val transport: StateFlow<ShellTransport> = _transport.asStateFlow()

    private val platform: AndroidPlatform by lazy {
        AndroidPlatform(
            app, hub, root, shizuku, scope,
            { _transport.value = it },
            { lastConnectCandidates = it },
            { _scanningPorts.value = it },
            { lastHelperLaunchStage = it },
        )
    }

    /** How many candidates each source offered on the last connect-port search (for diagnostics); null when no connection has been made in this process yet. */
    @Volatile var lastConnectCandidates: ConnectCandidates? = null
        private set

    /** The last confirmed stage reached when starting the helper; contains no nonce, address, command line or raw exception text. */
    @Volatile var lastHelperLaunchStage: String? = null
        private set

    /** Turns wireless debugging on for the user when the helper must be started: the App writes the system setting itself (no other connection is available at that point). */
    val wirelessAutoOn: WirelessDebuggingAutoOn by lazy {
        WirelessDebuggingAutoOn(
            wants = { consent().wants(RecoveryCapability.AutoWirelessDebugging) },
            canWrite = { app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED },
            read = platform::wirelessDebugging,
            write = { DeveloperSettings.turnOnWirelessDebugging(app.contentResolver) },
        )
    }

    /** Pairing client plus watchdog. */
    val engine: BuiltinShellEngine by lazy {
        BuiltinShellEngine(
            platform, hub.shell, scope, Dispatchers.IO, autoOn = wirelessAutoOn,
        )
    }

    /**
     * Used by recovery and the authorization switches: run through the helper when it is there (a helper started from a computer over USB works without a paired connection);
     * only when it is absent does each command connect to the paired adbd on its own and disconnect when done.
     */
    val commandRunner = ShellCommandRunner { command ->
        withContext(Dispatchers.IO) {
            if (hub.shell.value == null && engine.shizukuStart) {
                engine.need()
                withTimeoutOrNull(SHIZUKU_COMMAND_WAIT_MS) { hub.shell.first { it != null } }
            }
            val running = hub.shell.value
            if (running != null) {
                running.runCommand(command)?.getString("output").orEmpty()
            } else if (engine.transport == ShellTransport.Shizuku) {
                throw IOException("Shizuku helper is not running")
            } else {
                platform.connect().use { it.exec(command).readText() }
            }
        }
    }

    /** The "start from a computer" script: rewritten as the helper comes and goes. */
    val usbScript = UsbStartScript(app, hub)

    /** Something needs the helper and there is none: the watchdog starts one (pairing required), and the script is also prepared for the computer. */
    fun need() {
        engine.need()
        usbScript.refresh()
    }

    /** Refresh the Shizuku state; notify the engine right away if it changed. */
    fun refreshShizuku(): ShizukuPlatformState {
        val before = shizuku.state.value
        val after = shizuku.refresh()
        if (after != before) engine.shizukuChanged()
        return after
    }

    /** Refresh the Shizuku state on a background thread; notify the engine right away if it changed. */
    fun refreshShizukuAsync() {
        scope.launch(Dispatchers.IO) { refreshShizuku() }
    }

    /** The user entered the wireless debugging port on the guide page: use it on the next connection (only once; once connected it is stored as the cache), then retry. */
    suspend fun connectWithPort(port: Int) {
        platform.manualPort.set(port)
        engine.retry()
    }

    @Synchronized
    fun start() {
        if (started || Build.VERSION.SDK_INT < MIN_SDK) return
        started = true
        usbScript.start()
        root.refresh()
        refreshShizukuAsync()
        val e = engine
        // As soon as wireless debugging is turned on, try to reconnect; when it is turned off the status becomes "wireless debugging off" right away
        app.contentResolver.registerContentObserver(
            DeveloperSettings.wirelessDebuggingUri, false,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = e.wirelessDebuggingChanged()
            },
        )
        // When stuck at "no Wi-Fi" or wireless debugging off, retry as soon as any Wi-Fi (internet not required) connects
        runCatching {
            app.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = e.wifiAvailable()
                },
            )
        }
        e.start()
        shizuku.start(refreshNow = false)
    }

    private class AndroidPlatform(
        private val app: Context,
        private val hub: HelperHub,
        private val root: RootControl,
        private val shizuku: ShizukuController,
        private val scope: CoroutineScope,
        private val onTransportChanged: (ShellTransport) -> Unit,
        private val candidates: (ConnectCandidates) -> Unit,
        private val scanning: (Boolean) -> Unit,
        private val launchStage: (String) -> Unit,
    ) : BuiltinShellPlatform {
        /** The port the user entered; the next connection takes it. */
        val manualPort = AtomicReference<Int?>(null)
        private val scan = LocalPortScan(NioPortScanner(), ::localHosts, { LocalPortScan.parseRange(portRangeText()) })
        private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private val finder = NsdAdbServiceFinder(app)
        private val portCache = object : PortCache {
            override fun load() = prefs.getInt("port", 0).takeIf { it > 0 }
            override fun save(port: Int) { prefs.edit().putInt("port", port).apply() }
        }

        override fun paired() = Build.VERSION.SDK_INT >= MIN_SDK && prefs.getBoolean("paired", false)

        override fun setPaired(paired: Boolean) {
            prefs.edit().putBoolean("paired", paired).apply()
        }

        override fun rootStart() = root.rootStart()

        override fun setRootAnswer(answer: SuAnswer?) = root.setStartAnswer(answer)

        override fun requestRootStart(): Boolean {
            root.requestStart()
            return true
        }

        override fun transport(): ShellTransport =
            ShellTransport.parse(prefs.getString(KEY_TRANSPORT, null))

        override fun setTransport(transport: ShellTransport) {
            prefs.edit().putString(KEY_TRANSPORT, transport.name).apply()
            onTransportChanged(transport)
        }

        override fun shizukuState(): ShizukuPlatformState = shizuku.state.value

        override suspend fun launchHelperViaShizuku(): BuiltinShellFailure? = shizuku.launchHelper()

        /**
         * Start the same helper through su under the shell identity: `su 2000 -c '<id -u; the same command as over wireless debugging>'`. Only after reading 2000 (su agreed and switched to shell)
         * is the nonce written to stdin; the handoff checks are exactly the same as when starting over wireless debugging (shell identity, APK, nonce, signing certificate pin).
         * stdin is closed once the handoff arrives (or times out) and sh exits after reading the end; the helper runs in a separate session created by setsid and is unaffected.
         */
        override suspend fun launchHelperAsRoot(): SuAnswer? {
            val su = RootShell.su() ?: return null.also { root.suGone() }
            val command = HelperHandoff.rootCommand(app.applicationInfo.sourceDir, app.packageName, Process.myUid(), ownCertPin(app))
            val p = RootShell.start(SuProbe.shellArgs(su, command))
            val before = hub.shell.value
            try {
                val uid = try {
                    runInterruptible { RootShell.readUid(p, ROOT_ASK_MS) }
                } catch (e: TimeoutException) {
                    return SuAnswer.NoAnswer
                } catch (e: ExecutionException) {
                    return SuAnswer.Denied
                }
                when (uid) {
                    null -> return SuAnswer.Denied
                    HelperHandoff.SHELL_UID -> Unit
                    else -> return SuAnswer.WrongUid
                }
                p.outputStream.apply {
                    write(HelperHandoff.stdinLine(hub.issueLaunchNonce()))
                    flush()
                }
                withTimeoutOrNull(HANDOFF_WAIT_MS) { hub.shell.first { s -> s != null && s !== before } }
                return SuAnswer.Granted
            } finally {
                runCatching { p.outputStream.close() }
                if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroy()
            }
        }

        override fun usbStarted() = prefs.getBoolean("usbStarted", false)

        override fun setUsbStarted(on: Boolean) {
            prefs.edit().putBoolean("usbStarted", on).apply()
        }

        // Reading this key yields null when it was never written: what cannot be seen clearly does not block anything
        override fun wirelessDebugging(): Boolean? =
            Settings.Global.getString(app.contentResolver, DeveloperSettings.ADB_WIFI_ENABLED)?.let { it == "1" }

        override fun wifiConnected(): Boolean = runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        }.getOrDefault(true)

        override suspend fun findPairingPort(timeoutMs: Long): Int? =
            finder.findPorts(AdbServiceTypes.PAIRING, timeoutMs).firstOrNull()
                .also { Log.i(PAIRING_TAG, if (it != null) "found the pairing window on port $it" else "no pairing window within $timeoutMs ms") }

        override fun pair(port: Int, code: String) {
            AdbPairing(AndroidAdb.tls(AndroidAdb.pairingIdentity(app))).pair(LOCALHOST, port, code)
        }

        override suspend fun launchHelper() {
            launchStage("preparing")
            val tls = AndroidAdb.tls(AndroidAdb.identity(app))
            // The helper verifies Claire's signature before every handoff
            val command = HelperHandoff.command(app.applicationInfo.sourceDir, app.packageName, Process.myUid(), ownCertPin(app))
            launchStage("adb_connecting")
            val connection = connect(tls)
            launchStage("adb_connected")
            // While the stream is open sh keeps waiting; it is closed when the handoff arrives (or on timeout), and the engine still judges the handoff result from HelperHub
            val before = hub.shell.value
            connection.use {
                val stream = it.exec(command)
                launchStage("exec_opened")
                stream.write(HelperHandoff.stdinLine(hub.issueLaunchNonce()))
                launchStage("launch_nonce_written")
                val handedOff = withTimeoutOrNull(HANDOFF_WAIT_MS) { hub.shell.first { s -> s != null && s !== before } }
                launchStage(if (handedOff != null) "handoff_received" else "handoff_timeout")
            }
        }

        suspend fun connect(tls: AdbTls = AndroidAdb.tls(AndroidAdb.identity(app))): AdbConnection =
            AdbConnectPort(
                ::tlsPort, finder, portCache, report = candidates, scan = scan, manual = { manualPort.getAndSet(null) }, scanning = scanning,
            ).connect { host, port -> AdbConnection.open(host, port, tls) }

        /** Port scanning covers only this device itself: 127.0.0.1 and the IPv4 addresses on Wi-Fi. */
        private fun localHosts(): List<String> = listOf(LOCALHOST) + runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                .flatMap { cm.getLinkProperties(it)?.linkAddresses.orEmpty() }
                .mapNotNull { (it.address as? Inet4Address)?.hostAddress }
        }.getOrDefault(emptyList())

        /** The range the system allocates ephemeral ports from; some systems do not let apps read it, and when it cannot be read a default range is scanned. */
        private fun portRangeText(): String? = runCatching { File(PORT_RANGE_FILE).readText() }.getOrNull()

        override fun stopHelper() {
            val remaining = hub.shell.value
            hub.detachShizuku(remaining)
            val unbind = shizuku.prepareStop()
            if (unbind || remaining != null) {
                scope.launch(Dispatchers.IO) {
                    if (unbind) shizuku.unbindService()
                    // destroy makes the helper process exit on the spot, so this call usually ends with DeadObjectException
                    if (remaining != null) runCatching { remaining.destroy() }
                }
            }
        }

        override fun forget() {
            root.turnOffStart()
            hub.forget()
            AndroidAdb.forget(app)
            prefs.edit().remove("port").apply()
        }

        @SuppressLint("PrivateApi")
        private fun tlsPort(): Int? = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, TLS_PORT_PROPERTY) as String?
        }.getOrNull()?.trim()?.toIntOrNull()
    }

    companion object {
        /** The built-in helper is enabled from Android 12 on; older systems use a compatibility path without the newer screen flags. */
        const val MIN_SDK = Build.VERSION_CODES.S
        private const val PREFS = "builtin_shell"
        private const val KEY_TRANSPORT = "transport"
        private const val SHIZUKU_COMMAND_WAIT_MS = 5_000L
        /** The same tag as the other pairing sites (PairingCoordinator, PairingCodeReader), so one command shows the whole pairing history. */
        private const val PAIRING_TAG = "MikaPairing"
        /** Stock Android adbd writes the wireless debugging connection port into this system property; when it cannot be read, the cache, mDNS, local port scanning and the user-entered port are used instead. */
        private const val TLS_PORT_PROPERTY = "service.adb.tls.port"
        private const val LOCALHOST = AdbConnectPort.LOCALHOST
        private const val PORT_RANGE_FILE = "/proc/sys/net/ipv4/ip_local_port_range"
        private const val HANDOFF_WAIT_MS = 15_000L
        /** How long the watchdog waits for su to answer when starting the helper through it (already-authorized su answers at once; when a permission dialog must appear, give the user a moment). */
        private const val ROOT_ASK_MS = 45_000L
    }
}
