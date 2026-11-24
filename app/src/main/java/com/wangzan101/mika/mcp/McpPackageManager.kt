package com.wangzan101.mika.mcp

import com.wangzan101.mika.core.cap.mcp.McpPackageSpec
import com.wangzan101.mika.core.cap.sandbox.SandboxPort
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.workspace.SandboxPaths

/**
 * Installation and caching of stdio packages: installs into the workspace's controlled cache and returns the argv prefix of the directory holding the installed executables,
 * without running the MCP server itself. The caller combines the prefix with the argv declared by the preset into the final command.
 *
 * Safety: nothing is downloaded when unconfirmed or uncached; installation only happens when [confirmed]; on success a success marker is written atomically, while an interruption/failure leaves no marker and
 * can be retried; secrets enter the process only through Task 5's environment variables and never enter the argv generated here.
 */
interface McpPackageProvisioner {
    /**
     * Ensures [spec] is installed into the [workspace] view of the shared package cache and returns the argv prefix (a single element: the executables directory, e.g.
     * `/opt/mika/mcp-cache/npm-<name>-<version>/node_modules/.bin`). When it is not cached and [confirmed] is false it throws
     * `mcp_package_consent`; a download/install failure throws the retryable `mcp_package_install_failed`.
     */
    suspend fun prepare(workspace: String, spec: McpPackageSpec, confirmed: Boolean): List<String>
}

class McpPackageManager(
    private val port: SandboxPort,
    private val networkAllowed: () -> Boolean = { port.networkAllowed() },
) : McpPackageProvisioner {

    override suspend fun prepare(workspace: String, spec: McpPackageSpec, confirmed: Boolean): List<String> {
        val cache = cacheDir(spec)
        val marker = "$cache/.ok"
        if (probe(workspace, listOf("test", "-f", marker))) return prefix(spec, cache)
        if (!confirmed) throw consentError(spec)
        requireSupported(spec)
        if (!networkAllowed()) {
            throw SandboxError("mcp_package_network", "The sandbox has no network access, so this package cannot be installed. Turn on network access and try again.", recoverable = true)
        }
        val result = port.run(workspace, installArgv(spec, cache), null, INSTALL_TIMEOUT_MS) { }
        if (result.exitCode != 0) {
            runCatching { port.run(workspace, listOf("rm", "-rf", cache), null, CLEANUP_TIMEOUT_MS) {} }
            throw SandboxError(
                "mcp_package_install_failed",
                "Installing ${spec.name} ${spec.version} failed: " + result.output.trim().takeLast(400),
                recoverable = true,
            )
        }
        port.run(workspace, listOf("mkdir", "-p", cache), null, CLEANUP_TIMEOUT_MS) {}
        port.run(workspace, listOf("touch", marker), null, CLEANUP_TIMEOUT_MS) {}
        return prefix(spec, cache)
    }

    private suspend fun probe(workspace: String, argv: List<String>): Boolean =
        port.run(workspace, argv, null, PROBE_TIMEOUT_MS) {}.exitCode == 0

    private fun prefix(spec: McpPackageSpec, cache: String): List<String> = when (spec.ecosystem) {
        "npm" -> listOf("$cache/node_modules/.bin")
        "pypi" -> listOf("$cache/bin")
        else -> emptyList()
    }

    private fun installArgv(spec: McpPackageSpec, cache: String): List<String> = when (spec.ecosystem) {
        "npm" -> listOf("npm", "install", "${spec.name}@${spec.version}", "--ignore-scripts", "--no-audit", "--no-fund", "--no-save", "--prefix", cache)
        "pypi" -> listOf("python3", "-m", "pip", "install", "${spec.name}==${spec.version}", "--only-binary=:all:", "--prefix", cache)
        else -> emptyList()
    }

    private fun requireSupported(spec: McpPackageSpec) {
        if (spec.ecosystem !in setOf("npm", "pypi")) {
            throw SandboxError("mcp_package_unsupported", "Packages from ${spec.ecosystem} are not supported for stdio servers.", recoverable = false)
        }
    }

    private fun consentError(spec: McpPackageSpec): SandboxError = SandboxError(
        "mcp_package_consent",
        "This stdio server needs to install ${spec.name} ${spec.version} from ${spec.source}. Confirm to download and install it.",
        recoverable = true,
    )

    private fun cacheDir(spec: McpPackageSpec): String {
        val safe = spec.name.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }.joinToString("")
        return "${SandboxPaths.MCP_CACHE}/${spec.ecosystem}-$safe-${spec.version}"
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 15_000L
        const val CLEANUP_TIMEOUT_MS = 30_000L
        const val INSTALL_TIMEOUT_MS = 5 * 60_000L
    }
}
