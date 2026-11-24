package com.wangzan101.mika.voicecall

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Output device for a voice call. Speaker is loudspeaker; Earpiece/Wired/Bluetooth are all "private listening devices" (sound is not played out loud). */
enum class RouteDevice { Speaker, Earpiece, Wired, Bluetooth }

/**
 * Speakerphone routing decision, a pure function, testable across all combinations.
 *
 * Policy, three states of [speakerRequested]:
 * - `true` (user wants speakerphone): use the speaker if present, otherwise fall back to the best private device.
 * - `false` (user turned speakerphone off): best private device — wired > Bluetooth > earpiece; keep the speaker only if there is no private device (tablet form factor).
 * - `null` (no opinion yet, automatic): wired > Bluetooth > speaker — plugged-in headphones are almost always just connected by the user, so they take over directly;
 *   nobody holds the phone to their ear to listen to the assistant, so the automatic state skips the earpiece.
 * The decision itself is SDK-agnostic: [sdkInt] is only used to branch in the applying layer (API 31+ setCommunicationDevice, below that the speakerphone switch),
 * and the parameter is kept in the signature so call sites do not need to branch themselves.
 */
fun chooseRoute(available: List<RouteDevice>, sdkInt: Int, speakerRequested: Boolean?): RouteDevice {
    val set = available.toSet()
    if (speakerRequested == true && RouteDevice.Speaker in set) return RouteDevice.Speaker
    when {
        RouteDevice.Wired in set -> return RouteDevice.Wired
        RouteDevice.Bluetooth in set -> return RouteDevice.Bluetooth
    }
    return when {
        speakerRequested == false && RouteDevice.Earpiece in set -> RouteDevice.Earpiece
        RouteDevice.Speaker in set -> RouteDevice.Speaker
        RouteDevice.Earpiece in set -> RouteDevice.Earpiece
        else -> RouteDevice.Speaker
    }
}

/**
 * Call speakerphone routing: applies the decision of [chooseRoute] to the system audio.
 *
 * - API 31+: setCommunicationDevice selects precisely; on hang-up clearCommunicationDevice restores the default.
 * - API 29–30: the speakerphone switch (selection of private devices is done automatically by the system based on connection state); on hang-up the pre-call switch state is restored.
 * - The selected state always reads the system's actual communication device ([route] only reflects the system reading, it does not track its own intent).
 * - AudioDeviceCallback hot-plug: re-decide and apply when devices are added or removed.
 *
 * Assembly order: first have the call audio implementation enter MODE_IN_COMMUNICATION ([VoiceCallAudio.requestCallAudio]), then [begin];
 * on hang-up call [end] first to restore the route, then abandonCallAudio to leave the mode. This class does not touch audio mode or focus (that is VoiceCallAudio's domain).
 */
// isSpeakerphoneOn/isWiredHeadsetOn/isBluetoothScoOn are deprecated, but they are the only path on API 29–30, and 31+ does not go through here
@Suppress("DEPRECATION")
class VoiceCallAudioRoute(context: Context) {
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)

    private val _route = MutableStateFlow(RouteDevice.Speaker)
    /** The currently effective output device; reads the system's actual state, not "the state we want". */
    val route: StateFlow<RouteDevice> = _route

    private val lock = Any()
    private var active = false
    private var speakerRequested: Boolean? = null // no opinion: automatic — plugged-in headphones take over, otherwise loudspeaker (see chooseRoute)
    private var legacySpeakerBefore = false

    private val hotPlug = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    /** Call start: take over routing and register the hot-plug listener. Safe to call repeatedly (idempotent); [speaker] is only given by the speakerphone switch in the UI. */
    fun begin(speaker: Boolean? = null) {
        synchronized(lock) {
            if (!active) {
                active = true
                legacySpeakerBefore = manager.isSpeakerphoneOn
                manager.registerAudioDeviceCallback(hotPlug, null)
            }
            speakerRequested = speaker
        }
        refresh()
    }

    /** Speakerphone switch: request loudspeaker or return to a private device. */
    fun setSpeakerRequested(requested: Boolean) {
        synchronized(lock) { speakerRequested = requested }
        refresh()
    }

    /** Hang-up: restore the pre-call route and remove the hot-plug listener. Safe to call repeatedly (idempotent). */
    fun end() {
        synchronized(lock) {
            if (!active) return
            active = false
            manager.unregisterAudioDeviceCallback(hotPlug)
            runCatching {
                if (Build.VERSION.SDK_INT >= 31) {
                    manager.clearCommunicationDevice()
                } else {
                    manager.isSpeakerphoneOn = legacySpeakerBefore
                }
            }
        }
    }

    /** After a hot-plug or an external route change: re-decide, apply, and push the system's actual selected state into [route]. */
    private fun refresh() {
        val decision = synchronized(lock) {
            if (!active) return
            val available = availableDevices()
            available to chooseRoute(available, Build.VERSION.SDK_INT, speakerRequested)
        }
        applyLocked(desired = decision.second, available = decision.first)
    }

    private fun applyLocked(desired: RouteDevice, available: List<RouteDevice>) {
        if (Build.VERSION.SDK_INT >= 31) {
            val current = manager.communicationDevice
            if (routeOf(current) != desired) {
                val target = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { routeOf(it) == desired }
                if (target == null || !manager.setCommunicationDevice(target)) {
                    // The target device was just unplugged / the system refused the selection: stay on the system default and read the selected state truthfully
                }
            }
        } else {
            val wantSpeaker = desired == RouteDevice.Speaker
            if (manager.isSpeakerphoneOn != wantSpeaker) {
                runCatching { manager.isSpeakerphoneOn = wantSpeaker }
            }
        }
        _route.value = readSystemRoute(available)
    }

    /** The system's current actual communication device; when it cannot be read, infer the default from the available set (earpiece if present, otherwise speaker). */
    private fun readSystemRoute(available: List<RouteDevice>): RouteDevice {
        if (Build.VERSION.SDK_INT >= 31) {
            routeOf(manager.communicationDevice)?.let { return it }
            return if (RouteDevice.Earpiece in available) RouteDevice.Earpiece else RouteDevice.Speaker
        }
        return when {
            manager.isSpeakerphoneOn -> RouteDevice.Speaker
            manager.isWiredHeadsetOn -> RouteDevice.Wired
            manager.isBluetoothScoOn -> RouteDevice.Bluetooth
            RouteDevice.Earpiece in available -> RouteDevice.Earpiece
            else -> RouteDevice.Speaker
        }
    }

    private fun availableDevices(): List<RouteDevice> =
        manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapNotNull { routeOf(it) }.distinct()

    private companion object {
        fun routeOf(device: AudioDeviceInfo?): RouteDevice? = when (device?.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> RouteDevice.Speaker
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> RouteDevice.Earpiece
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            -> RouteDevice.Wired
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
            -> RouteDevice.Bluetooth
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            -> RouteDevice.Wired
            else -> null
        }
    }
}
