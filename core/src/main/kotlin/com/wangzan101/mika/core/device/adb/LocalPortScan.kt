package com.wangzan101.mika.core.device.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap

/** A candidate connection port: which address on this device, and which port. */
data class AdbEndpoint(val host: String, val port: Int)

/** Listening ports that Claire itself has open on this device (such as the login callback): skipped while scanning so it does not connect to itself. */
object OwnListeningPorts {
    private val ports = ConcurrentHashMap.newKeySet<Int>()
    fun add(port: Int) { ports += port }
    fun remove(port: Int) { ports -= port }
    fun snapshot(): Set<Int> = ports.toSet()
}

/** The low-level action that scans local ports; replaced with a fake in tests. */
interface LocalPortScanner {
    /** Which of [ports] on [host] accept a TCP connection (ascending), skipping [skip]; stops once [budgetMs] is used up and still returns what was already found. */
    suspend fun openPorts(host: String, ports: IntRange, skip: Set<Int>, budgetMs: Long): List<Int>

    /** Only a peer that replies STLS to our CNXN counts as the wireless debugging adbd (other services, and connecting to ourselves, never reply STLS). */
    fun speaksAdbTls(host: String, port: Int, timeoutMs: Int): Boolean
}

/** The result of one local scan: how many ports were open and how many looked like adbd (only the counts are recorded, for diagnostics). */
data class ScanResult(val endpoints: List<AdbEndpoint>, val open: Int, val adb: Int)

/**
 * The last resort for finding the connection port: when the earlier sources produced no connectable port, scan the machine's own addresses.
 * Wireless debugging's adbd listens on a port the system assigns at random, within the ephemeral port range ([range], or [FALLBACK_RANGE] when unreadable).
 * Scans one address at a time in [hosts] order (127.0.0.1 first, then the machine's Wi-Fi address; adbd may bind only one of them),
 * sends CNXN to every open port to see whether it replies STLS; once an address has something that looks like adbd, the next address is not scanned. Only this machine is scanned, never other devices.
 * Each pass scans [PORT_BATCH_SIZE] ports and immediately checks the open ones in that batch; if something looks like adbd, stop and do not scan later batches, otherwise scan the next batch.
 * Scanning and checking together take no longer than [budgetMs].
 */
class LocalPortScan(
    private val scanner: LocalPortScanner,
    private val hosts: () -> List<String>,
    private val range: () -> IntRange = { FALLBACK_RANGE },
    private val ownPorts: () -> Set<Int> = OwnListeningPorts::snapshot,
    private val budgetMs: Long = 10_000,
    private val probeTimeoutMs: Int = 1_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Runs one scan; [skip] holds the ports the earlier sources already tried. */
    suspend fun scan(skip: Set<Int>): ScanResult {
        val deadline = now() + budgetMs
        val ports = range()
        val excluded = skip + ownPorts()
        val found = ArrayList<AdbEndpoint>()
        var open = 0
        hosts@ for (host in hosts().distinct()) {
            // Scan in batches and check each batch immediately: scanning the whole range before checking spends the budget on scanning when it is slow, leaving no time to check an adbd that has been open all along
            var first = ports.first
            while (first <= ports.last) {
                val left = deadline - now()
                if (left <= 0) break@hosts
                val last = (first.toLong() + PORT_BATCH_SIZE - 1).coerceAtMost(ports.last.toLong()).toInt()
                val openPorts = scanner.openPorts(host, first..last, excluded, left)
                open += openPorts.size
                for (port in openPorts) {
                    if (now() >= deadline) break@hosts
                    if (scanner.speaksAdbTls(host, port, probeTimeoutMs)) found += AdbEndpoint(host, port)
                }
                // Once adbd is found in this batch, do not scan later batches or the next address
                if (found.isNotEmpty()) break@hosts
                if (last == ports.last) break
                first = last + 1
            }
        }
        return ScanResult(found, open, found.size)
    }

    companion object {
        /** How many ports to scan per batch before checking. */
        const val PORT_BATCH_SIZE = 1_024

        /** Batch scanned when the system's ephemeral port range cannot be read (covers the settings common across versions). */
        val FALLBACK_RANGE = 30_000..65_535

        /**
         * Parses `/proc/sys/net/ipv4/ip_local_port_range` (two numbers, whitespace-separated). Uses [FALLBACK_RANGE] when it cannot be read, is malformed, or the range is unreasonable.
         */
        fun parseRange(text: String?): IntRange {
            val nums = text?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toIntOrNull() } ?: return FALLBACK_RANGE
            if (nums.size != 2) return FALLBACK_RANGE
            val (lo, hi) = nums
            return if (lo in 1024..65_535 && hi in lo..65_535) lo..hi else FALLBACK_RANGE
        }
    }
}

/**
 * The real scan: java.nio non-blocking connects, at most [concurrency] in flight at once, each waiting at most [connectTimeoutMs].
 * Ports that cannot be connected on a local address are rejected immediately, so scanning the whole range takes a few seconds. Runs on IO threads and stops when the coroutine is cancelled.
 */
class NioPortScanner(
    private val concurrency: Int = 200,
    private val connectTimeoutMs: Long = 150,
) : LocalPortScanner {
    override suspend fun openPorts(host: String, ports: IntRange, skip: Set<Int>, budgetMs: Long): List<Int> = withContext(Dispatchers.IO) {
        val address = AdbHosts.ownAddress(host) ?: return@withContext emptyList()
        val ctx = currentCoroutineContext()
        sweep(address, ports, skip, System.currentTimeMillis() + budgetMs) { ctx.isActive }
    }

    private fun sweep(addr: InetAddress, ports: IntRange, skip: Set<Int>, deadline: Long, active: () -> Boolean): List<Int> {
        val open = TreeSet<Int>()
        val next = ports.iterator()
        Selector.open().use { selector ->
            val startedAt = HashMap<SelectionKey, Long>()
            try {
                while ((next.hasNext() || startedAt.isNotEmpty()) && System.currentTimeMillis() < deadline && active()) {
                    while (next.hasNext() && startedAt.size < concurrency) {
                        val port = next.nextInt()
                        if (port in skip) continue
                        val ch = SocketChannel.open()
                        try {
                            ch.configureBlocking(false)
                            if (ch.connect(InetSocketAddress(addr, port))) {
                                if (!selfConnected(ch, port)) open += port
                                ch.close()
                            } else {
                                startedAt[ch.register(selector, SelectionKey.OP_CONNECT, port)] = System.currentTimeMillis()
                            }
                        } catch (e: IOException) {
                            ch.close()
                        }
                    }
                    selector.select(SELECT_MS)
                    for (key in selector.selectedKeys()) {
                        val ch = key.channel() as SocketChannel
                        val ok = try { ch.finishConnect() } catch (e: IOException) { false }
                        if (ok && !selfConnected(ch, key.attachment() as Int)) open += key.attachment() as Int
                        startedAt.remove(key)
                        key.cancel()
                        ch.close()
                    }
                    selector.selectedKeys().clear()
                    val t = System.currentTimeMillis()
                    val late = startedAt.filterValues { t - it > connectTimeoutMs }.keys
                    for (key in late) {
                        startedAt.remove(key)
                        key.cancel()
                        key.channel().close()
                    }
                }
            } finally {
                for (key in startedAt.keys) runCatching { key.channel().close() }
            }
        }
        return open.toList()
    }

    /** While scanning the ephemeral port range, the system may happen to assign this very connection the same local port, so we end up connected to ourselves. */
    private fun selfConnected(ch: SocketChannel, port: Int): Boolean = runCatching {
        val s = ch.socket()
        s.localPort == port && s.localAddress == s.inetAddress
    }.getOrDefault(false)

    override fun speaksAdbTls(host: String, port: Int, timeoutMs: Int): Boolean = try {
        val target = AdbHosts.ownSocketAddress(host, port) ?: return false
        Socket().use { s ->
            s.connect(target, timeoutMs)
            if (s.localPort == port && s.localAddress == s.inetAddress) return false
            s.soTimeout = timeoutMs
            s.getOutputStream().apply {
                write(AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, HOST_BANNER).encode())
                flush()
            }
            AdbMessage.read(DataInputStream(s.getInputStream()), maxData = 4096)?.command == AdbMessage.STLS
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val SELECT_MS = 20L
        val HOST_BANNER = "host::\u0000".toByteArray(Charsets.US_ASCII)
    }
}
