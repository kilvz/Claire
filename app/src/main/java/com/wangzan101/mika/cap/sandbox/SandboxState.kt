package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.content.Context
import android.os.Build
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

enum class InstallStep { Check, Extract, Configure, Skills, Done }

/**
 * Sandbox state. Install progress is recorded in `files/sandbox/state.json` (deleted along with the whole directory on reset);
 * the network switch is a user setting, recorded in `files/sandbox-settings.json` and kept across resets.
 *
 * The sandbox only counts as ready when the record says "completed up to Done" and the root filesystem has /bin/sh; a half-finished install (including the process being killed mid-install) is always Broken,
 * and the next run resumes after the recorded step.
 */
class SandboxState internal constructor(
    private val dirs: SandboxDirs,
    private val settingsFile: File,
    private val abis: () -> List<String>,
) {
    constructor(context: Context) : this(
        SandboxDirs(context), File(context.filesDir, "sandbox-settings.json"), { Build.SUPPORTED_ABIS.toList() },
    )

    private val recordFile get() = File(dirs.root, "state.json")
    private var installing: SandboxStatus.Installing? = null
    private val _status = MutableStateFlow(read())
    val status: StateFlow<SandboxStatus> = _status.asStateFlow()

    /** "Allow sandbox networking", on by default (a local customization: networking works out of the box, with no need to turn it on manually in settings). */
    var networkAllowed: Boolean
        get() = settings()["networkAllowed"]?.jsonPrimitive?.booleanOrNull ?: true
        set(value) { putSetting("networkAllowed", JsonPrimitive(value)); refresh() }

    /** Reason the network cutoff filter could not be installed (determined by the launcher's self-check); it does not block installation, but commands are refused while the network is cut. */
    val isolationIssue: String? get() = record()["isolationIssue"]?.jsonPrimitive?.contentOrNull

    val installedVersion: String? get() = record()["version"]?.jsonPrimitive?.contentOrNull

    @Synchronized
    fun completedStep(): InstallStep? =
        record()["completedStep"]?.jsonPrimitive?.contentOrNull?.let { n -> InstallStep.entries.firstOrNull { it.name == n } }

    /** Record "completed up to [s]" and clear the previous failure reason; on Done, write the version and completion time. */
    @Synchronized
    fun markStep(s: InstallStep) = markStep(s, null)

    @Synchronized
    internal fun markStep(s: InstallStep, version: String?) {
        val r = record().toMutableMap()
        r["completedStep"] = JsonPrimitive(s.name)
        r.remove("broken")
        if (s == InstallStep.Done) {
            version?.let { r["version"] = JsonPrimitive(it) }
            r["doneAt"] = JsonPrimitive(System.currentTimeMillis())
            installing = null
        }
        writeJson(recordFile, JsonObject(r))
        refresh()
    }

    @Synchronized
    internal fun installing(step: String, percent: Int) {
        installing = SandboxStatus.Installing(percent, step)
        refresh()
    }

    @Synchronized
    internal fun markBroken(reason: String) {
        installing = null
        writeJson(recordFile, JsonObject(record() + ("broken" to JsonPrimitive(reason))))
        refresh()
    }

    @Synchronized
    internal fun recordIsolation(issue: String?) {
        val r = record().toMutableMap()
        if (issue == null) r.remove("isolationIssue") else r["isolationIssue"] = JsonPrimitive(issue)
        writeJson(recordFile, JsonObject(r))
    }

    /** Reset begins: delete the install record first and show "resetting", so that being killed halfway through the deletion can never leave it looking ready. */
    @Synchronized
    internal fun resetting() {
        Files.deleteIfExists(recordFile.toPath())
        installing(tr(R.string.sandbox_resetting), 0)
    }

    /** Install or reset finished (success or failure): drop the in-progress state and recompute from the record. */
    @Synchronized
    internal fun cleared() {
        installing = null
        refresh()
    }

    @Synchronized
    fun refresh() { _status.value = read() }

    private fun read(): SandboxStatus {
        if ("arm64-v8a" !in abis()) return SandboxStatus.Unsupported(tr(R.string.install_unsupported))
        installing?.let { return it }
        val r = record()
        val step = r["completedStep"]?.jsonPrimitive?.contentOrNull
        val broken = r["broken"]?.jsonPrimitive?.contentOrNull
        // Alpine's /bin/sh is an absolute symlink to /bin/busybox that cannot be resolved on the host: look at the link itself only
        val hasShell = Files.exists(File(dirs.rootfs, "bin/sh").toPath(), LinkOption.NOFOLLOW_LINKS)
        return when {
            step == InstallStep.Done.name && hasShell -> SandboxStatus.Ready(network = networkAllowed)
            step == InstallStep.Done.name -> SandboxStatus.Broken(tr(R.string.sandbox_incomplete))
            broken != null -> SandboxStatus.Broken(broken)
            step != null -> SandboxStatus.Broken(tr(R.string.sandbox_not_finished))
            else -> SandboxStatus.NotInstalled
        }
    }

    private fun record(): Map<String, JsonElement> = readJson(recordFile)
    private fun settings(): Map<String, JsonElement> = readJson(settingsFile)

    @Synchronized
    private fun putSetting(key: String, value: JsonPrimitive) = writeJson(settingsFile, JsonObject(settings() + (key to value)))

    companion object {
        /** The single in-process copy (supplied by the App): the state updated by the install service, which the settings screen and the Agent both see. */
        fun get(context: Context): SandboxState = SandboxOwner.of(context).sandboxState

        private fun readJson(f: File): Map<String, JsonElement> =
            runCatching { Json.parseToJsonElement(f.readText()).jsonObject }.getOrNull() ?: emptyMap()

        /** Write a temp file first, then rename it, so being killed mid-write never leaves a corrupt file. */
        private fun writeJson(f: File, o: JsonObject) {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            // Rename only after flushing to disk: on power loss or a kill, the record is either the old one or the new one
            java.io.FileOutputStream(tmp).use { out -> out.write(o.toString().toByteArray()); out.fd.sync() }
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
