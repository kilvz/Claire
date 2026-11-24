package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.device.DeviceAccess
import java.net.InetAddress
import java.net.InetSocketAddress

/** A little bit of activity shown on the settings page: how many clients connected recently, and when the last request was. */
data class RemoteActivity(val recentClients: Int = 0, val lastRequestAtMs: Long? = null)

/**
 * The combined entry point for computer connections: shared HTTP listening and authentication, with the ordinary task API and MCP both wired into
 * the same [RemoteTaskService]. It only listens on [port]; when [allowAllInterfaces] is false it binds only the local loopback 127.0.0.1 (computer
 * connections over USB, tests), and when true it binds all interfaces but only accepts connections where both ends are private addresses (LAN,
 * private mesh networks); connections from the public internet are disconnected on the spot.
 * [token] is accepted from any connection; [loopbackToken] is only accepted from the local loopback (a computer coming in through `adb forward`
 * arrives on loopback).
 */
class RemoteHost(
    tasks: RemoteTasks,
    private val token: () -> String?,
    private val port: Int,
    private val allowAllInterfaces: Boolean,
    serverVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onError: (Throwable) -> Unit = {},
    loopbackToken: () -> String? = { null },
    device: DeviceAccess? = null,
    history: RemoteHistory? = null,
    records: RunExportSource? = null,
    private val endpoints: RemoteEndpoints = RemoteEndpoints(tasks, serverVersion, clock, device, history, records),
) {
    private val access = RemoteAccess(token, clock, loopbackToken = loopbackToken)
    val activity get() = endpoints.activity
    private var server: MiniHttpServer? = null

    val boundPort: Int get() = server?.port ?: -1

    fun handle(req: HttpRequest): HttpResponse = access.handle(req, endpoints::handle)

    /** Start listening; binding failure such as the port being taken throws. */
    fun start() {
        val s = MiniHttpServer(bindAddress(allowAllInterfaces, port), ::handle, accept = { local, remote ->
            RemoteAccess.privateAddress(local) && RemoteAccess.privateAddress(remote)
        }, onError = onError)
        s.start()
        server = s
    }

    fun stop() {
        server?.stop()
        server = null
    }

    companion object {
        const val RECENT_MS = 10 * 60_000L

        /**
         * The address to listen on: all interfaces, or only IPv4 loopback 127.0.0.1. Loopback is hardcoded to IPv4: on Android
         * `InetAddress.getLoopbackAddress()` may return ::1, whereas `adb forward` connects to 127.0.0.1.
         */
        fun bindAddress(allowAllInterfaces: Boolean, port: Int): InetSocketAddress =
            if (allowAllInterfaces) InetSocketAddress(port) else InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port)
    }
}
