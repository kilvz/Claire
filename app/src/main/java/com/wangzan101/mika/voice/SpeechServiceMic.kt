package com.wangzan101.mika.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.provider.Settings

/** Whether the app hosting a recognition service has the recording permission. It and Claire are two separate apps, each with its own permissions. */
enum class ServiceMic {
    /** Requested and granted. */
    Granted,
    /** Requested but not granted: it cannot record audio. */
    Missing,
    /** The recording permission was never requested (audio may be obtained another way): no conclusion is drawn. */
    NotRequested,
    /** Cannot be determined (the app is not visible, or the query failed). */
    Unknown,
}

/** The recognition service lacking the recording permission: [packageName] is used for "open settings", [label] is its name in the system, for people to read. */
data class SpeechServiceTarget(val packageName: String, val label: String)

/**
 * When a recognition service says "no permission", tell whose permission is missing. Claire's recording permission may be granted while the recognition service (another app) lacks its own, and it still cannot record —
 * pointing the user to Claire's permission page in that case points at the wrong place.
 *
 * The recognition service is only said to lack the permission when all three are certain: Claire itself holds the recording permission; the service used this time can be identified; and that app requested the recording permission but did not get it.
 * If any one is uncertain, no such conclusion is drawn and the earlier wording stands. This only queries; it never requests or grants permissions for other apps.
 */
object SpeechServiceMic {
    /** The key for the "default recognition service" in system settings: there is no public constant, but the system lets apps read it. */
    private const val DEFAULT_SERVICE = "voice_recognition_service"
    private const val LABEL_MAX = 40

    /** Reads the recording permission state from an app's requested permissions and their flags. */
    fun of(requested: Array<String>?, flags: IntArray?): ServiceMic {
        val i = requested?.indexOf(Manifest.permission.RECORD_AUDIO) ?: -1
        if (i < 0) return ServiceMic.NotRequested
        val flag = flags?.getOrNull(i) ?: return ServiceMic.Unknown
        return if (flag and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0) ServiceMic.Granted else ServiceMic.Missing
    }

    /**
     * Which app's service this recognition uses. When one is named, it is that one ([namedPackage]); when nothing is named, it is the system's default recognition service ([defaultPackage],
     * null when unset or unreadable); on-device recognition ([onDevice]) uses a service the system designates internally, which cannot be told from outside. Null when it cannot be identified; the first registered one is never guessed.
     */
    fun used(namedPackage: String?, onDevice: Boolean, defaultPackage: () -> String?): String? =
        if (onDevice) null else namedPackage ?: defaultPackage()

    /** Returns the recognition service when it is determined to lack the recording permission; null when anything is uncertain (see the class comment). */
    fun missing(selfGranted: Boolean, servicePackage: String?, state: (String) -> ServiceMic, label: (String) -> String): SpeechServiceTarget? {
        if (!selfGranted || servicePackage.isNullOrBlank()) return null
        if (state(servicePackage) != ServiceMic.Missing) return null
        return SpeechServiceTarget(servicePackage, label(servicePackage))
    }

    /** [missing] wired to the system: [namedPackage] and [onDevice] are the entry actually used for this recognition. */
    fun missing(context: Context, namedPackage: String?, onDevice: Boolean): SpeechServiceTarget? = missing(
        selfGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        servicePackage = used(namedPackage, onDevice) { defaultService(context)?.packageName },
        state = { state(context, it) },
        label = { label(context, it) },
    )

    /** The system's default recognition service; null when unset or unreadable. */
    fun defaultService(context: Context): ComponentName? = try {
        Settings.Secure.getString(context.contentResolver, DEFAULT_SERVICE)?.takeIf { it.isNotBlank() }?.let(ComponentName::unflattenFromString)
    } catch (_: Exception) {
        null
    }

    fun state(context: Context, packageName: String): ServiceMic = try {
        val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        of(info.requestedPermissions, info.requestedPermissionsFlags)
    } catch (_: Exception) {
        ServiceMic.Unknown
    }

    /** This app's name in the system (chosen by another app, so it is shown as plain text and truncated); the package name is used when it cannot be obtained. */
    fun label(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        clean(pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()).ifEmpty { packageName }
    } catch (_: Exception) {
        packageName
    }

    internal fun clean(label: String): String = label.replace(Regex("\\s+"), " ").trim().take(LABEL_MAX)
}
