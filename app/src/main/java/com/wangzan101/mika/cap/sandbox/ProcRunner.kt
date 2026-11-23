package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.net.LocalHosts
import com.wangzan101.mika.core.workspace.SandboxPaths
import java.io.File
import java.io.InputStream
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs

/** Raw result of one process run; [timedOut] only means the Kotlin-side fallback time limit was reached. */
internal class ProcOutcome(val exitCode: Int, val output: ByteArray, val droppedBytes: Long, val timedOut: Boolean, val durationMs: Long)

/** Builds the launcher + PRoot command line and environment variables. */
object ProcRunner {
    const val OUTPUT_LIMIT = 1 shl 20
    const val READ_CHUNK = 64 * 1024
    const val PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    /** When going through a proxy, loopback and LAN addresses still connect directly (the same set as Claire's own connections, see [LocalHosts]). */
    const val NO_PROXY = LocalHosts.NO_PROXY
    /** Format of explicitly appended environment variable names (the same as the preset's env targets). */
    private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    /** Keys in the base allowlist that Claire fills itself; the resolved environment must not override them. */
    private val RESERVED_ENV = setOf(
        "PROOT_LOADER", "PROOT_TMP_DIR", "HOME", "PATH", "LANG", "TMPDIR", "TZ",
        "MIKA_BRIDGE", "MIKA_TOKEN", "MIKA_CONVERSATION", "TERM", "HISTFILE",
        "http_proxy", "https_proxy", "all_proxy", "no_proxy",
        "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
    )

    /**
     * [cwd]: a relative path under `/workspace`; an empty string means `/workspace`; [standIns]: names of the stand-in files in `files/sandbox/proc/`,
     * each bound to /proc/<name>. /dev only binds the devices listed in [ProotDevices] (not the whole /dev; see the note there).
     * [timeoutSec] of 0 means no time limit; [pty] true gives the command a pseudo-terminal (used by the user terminal);
     * [ptySizePath] is a small file that can change the window size while running (contents "cols rows"; mikarun reads it every 200ms).
     */
    fun argv(
        dirs: SandboxDirs, workspace: File, cwd: String, timeoutSec: Int, network: Boolean, standIns: List<String>, command: List<String>,
        devices: List<String> = ProotDevices.bindArgs(),
        pty: Boolean = false, ptyCols: Int = 80, ptyRows: Int = 24, ptySizePath: File? = null,
    ): List<String> = buildList {
        add(dirs.lib("libmikarun.so").path)
        if (timeoutSec > 0) addAll(listOf("--timeout", timeoutSec.toString()))
        addAll(listOf("--net", if (network) "on" else "off"))
        if (pty) addAll(listOf("--pty", "--pty-cols", ptyCols.toString(), "--pty-rows", ptyRows.toString()))
        if (pty && ptySizePath != null) addAll(listOf("--pty-size", ptySizePath.path))
        add("--")
        add(dirs.lib("libproot.so").path)
        addAll(listOf("--kill-on-exit", "--link2symlink", "-0", "-r", dirs.rootfs.path))
        addAll(devices)
        addAll(listOf("-b", "/proc", "-b", "/sys"))
        for (s in standIns) addAll(listOf("-b", "${File(dirs.procStandIns, s).path}:/proc/$s"))
        addAll(
            listOf(
                "-b", "${workspace.path}:${SandboxPaths.WORKSPACE}",
                "-b", "${dirs.skills.path}:${SandboxPaths.SKILLS}",
                "-b", "${dirs.mcpCache.path}:${SandboxPaths.MCP_CACHE}",
                "-w", if (cwd.isEmpty()) SandboxPaths.WORKSPACE else "${SandboxPaths.WORKSPACE}/$cwd",
            ),
        )
        addAll(command)
    }

    /** The environment is cleared first and only these are provided; when the bridge is off, MIKA_BRIDGE / MIKA_TOKEN are not provided. With [pty], TERM for the terminal and an empty history file are provided. */
    fun env(
        dirs: SandboxDirs, bridgeName: String?, token: String?, conversationId: String,
        /** The container's network proxy; an empty string means none (see NetProxyStore). Callers pass an empty string when networking is not allowed. */
        proxy: String = "",
        pty: Boolean = false,
        /** Resolved explicit environment (stdio secrets reach the process this way); only appended, never overriding base entries; keys/values are validated. */
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> = buildMap {
        put("PROOT_LOADER", dirs.lib("libproot_loader.so").path)
        put("PROOT_TMP_DIR", dirs.tmp.path)
        put("HOME", "/root")
        put("PATH", PATH)
        put("LANG", "C.UTF-8")
        put("TMPDIR", "/tmp")
        // Read the phone's current time zone on every start; Alpine's libc does not read Android's system time zone.
        put("TZ", timezone())
        bridgeName?.let { put("MIKA_BRIDGE", it) }
        token?.let { put("MIKA_TOKEN", it) }
        put("MIKA_CONVERSATION", conversationId)
        if (pty) {
            put("TERM", "xterm-256color")
            // Shell history is managed in memory by the app; do not write entered commands (which may contain secrets) into the rootfs.
            put("HISTFILE", "/dev/null")
        }
        // The proxy is only for commands inside the container: common tools recognize these variables (some only the uppercase ones); loopback addresses bypass the proxy
        if (proxy.isNotBlank()) {
            for (name in listOf("http_proxy", "https_proxy", "all_proxy")) {
                put(name, proxy)
                put(name.uppercase(), proxy)
            }
            put("no_proxy", NO_PROXY)
            put("NO_PROXY", NO_PROXY)
        }
        // The resolved explicit environment is only appended on top of the allowlist and overrides no base entry; secret values never go into argv or logs.
        extra.forEach { (name, value) ->
            require(ENV_NAME.matches(name) && name !in RESERVED_ENV) { "Invalid environment variable name" }
            require(!value.contains('\u0000')) { "Environment values cannot contain NUL" }
            put(name, value)
        }
    }

    /**
     * Writes the phone's time zone as TZ inside the sandbox. Region names (such as Asia/Shanghai) are passed as-is and resolved by the tzdata shipped with the runtime, preserving historical offsets and daylight saving time;
     * fixed offsets (such as GMT+08:00) cannot be passed as-is: the sign is inverted in POSIX TZ. Whole hours are converted to tzdata names such as Etc/GMT-8,
     * the rest are written in POSIX form `<+0545>-05:45`; UTC is used when the time zone cannot be read.
     */
    internal fun timezone(zone: () -> ZoneId = ZoneId::systemDefault): String {
        val z = runCatching(zone).getOrNull() ?: return "UTC"
        val fixed = z as? ZoneOffset ?: z.id.takeIf { it.startsWith("GMT") || it.startsWith("UT") }?.let { z.normalized() as? ZoneOffset }
        val s = fixed?.totalSeconds ?: return z.id
        if (s == 0) return "UTC"
        if (s % 3600 == 0 && s / 3600 in -12..14) return "Etc/GMT" + (if (s > 0) "-" else "+") + abs(s / 3600)
        val a = abs(s)
        val hm = String.format(Locale.ROOT, "%02d%02d", a / 3600, a / 60 % 60)
        val posix = String.format(Locale.ROOT, "%02d:%02d", a / 3600, a / 60 % 60) +
            (if (a % 60 != 0) String.format(Locale.ROOT, ":%02d", a % 60) else "")
        return if (s > 0) "<+$hm>-$posix" else "<-$hm>$posix"
    }

    /** Reads to end of stream; the result is in [OutputCollector]. */
    internal fun collect(input: InputStream, limit: Int = OUTPUT_LIMIT): Pair<ByteArray, Long> =
        OutputCollector(limit).apply { drain(input) }.snapshot()
}

/** Reads to end of stream; keeps at most [limit] bytes, reading and discarding the excess while counting it (the command would block writing output otherwise). A snapshot can be taken while reading. */
internal class OutputCollector(private val limit: Int = ProcRunner.OUTPUT_LIMIT) {
    private val kept = java.io.ByteArrayOutputStream(minOf(limit, ProcRunner.READ_CHUNK))
    private var dropped = 0L

    fun drain(input: InputStream) {
        val buf = ByteArray(ProcRunner.READ_CHUNK)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            synchronized(this) {
                val room = limit - kept.size()
                if (room > 0) kept.write(buf, 0, minOf(n, room))
                if (n > room) dropped += n - maxOf(room, 0)
            }
        }
    }

    @Synchronized fun snapshot(): Pair<ByteArray, Long> = kept.toByteArray() to dropped
}
