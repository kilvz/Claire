package com.wangzan101.mika.cap.phone.setup

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import com.wangzan101.mika.cap.phone.shell.HelperHub
import com.wangzan101.mika.core.device.shell.HelperHandoff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * The "start from computer" script: the computer runs `adb shell sh <external files dir>/usb-start.sh` over USB
 * debugging and starts the same helper as wireless debugging; the handoff also goes through the same
 * [HelperHub.offer] (likewise verifying shell identity, passphrase and signature).
 * When the helper is absent, write a script that carries a one-time passphrase; when the helper is connected,
 * replace it with the instructions that carry no passphrase, so no usable passphrase is left on disk.
 * The app can write the external files directory without permissions and the shell can read it; other apps
 * cannot.
 */
class UsbStartScript(context: Context, private val hub: HelperHub) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    /** The script's location on the phone; null when external storage is temporarily unavailable. */
    private fun file(): File? = app.getExternalFilesDir(null)?.let { File(it, HelperHandoff.USB_SCRIPT) }

    /** The command to run on the computer (this is exactly what the onboarding screen displays and copies). */
    fun command(): String =
        HelperHandoff.usbCommand(file()?.absolutePath ?: "/storage/emulated/0/Android/data/${app.packageName}/files/${HelperHandoff.USB_SCRIPT}")

    /** Called when the app starts: rewrites the script according to whether the helper is present and whether the passphrase has been invalidated (unpaired). */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch { HelperHandoff.usbScriptWrites(hub.shell, hub.nonceResets).collect { write(connected = it) } }
    }

    /** Called when the onboarding screen is opened because the helper is needed but missing: writes it again (the passphrase is reused while still fresh, see [HelperHub.usbNonce]). */
    fun refresh() {
        scope.launch { write(connected = hub.shell.value != null) }
    }

    @Synchronized
    private fun write(connected: Boolean) {
        val f = file() ?: return
        val text = runCatching {
            HelperHandoff.usbFile(
                connected, app.applicationInfo.sourceDir, app.packageName, Process.myUid(), { ownCertPin(app) }, hub::usbNonce, f.absolutePath,
            )
        }.getOrElse {
            Log.w(TAG, "cannot build ${f.name}", it)
            return
        }
        // Write a temporary file first and rename it afterwards: the computer never reads a half-written file
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                f.delete()
                check(tmp.renameTo(f)) { "rename failed" }
            }
        }.onFailure { Log.w(TAG, "cannot write ${f.name}", it) }
    }

    private companion object {
        const val TAG = "MikaUsbStart"
    }
}

/** Pin of Claire's signing certificate: the helper checks Claire against it before every handoff (shared by both start methods); read from this app's own install information. */
internal fun ownCertPin(context: Context): String {
    val signers = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        .signingInfo?.apkContentsSigners?.map { it.toByteArray() }
    return HelperHandoff.certPin(signers) ?: error("cannot read own signing certificate")
}
