package com.wangzan101.mika.voice.ondevice

import com.wangzan101.mika.voice.model.SttBinding
import com.wangzan101.mika.voice.model.SttProtocol
import com.wangzan101.mika.voice.model.VoiceAccount
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceModel
import com.wangzan101.mika.voice.model.VoiceModelOrigin
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.voice.model.VoiceService

/**
 * How an installed voice pack appears in the voice catalog: one model per pack under the fixed [SERVICE] (the model id is the pack id).
 * Like other models it is stored in the catalog snapshot (unlike android-system, which is generated on the fly), so fallback chains and cleanup rules need no special cases.
 * When files are gone the model and the selection stay; using it reports [com.wangzan101.mika.voice.model.VoiceModelError.PackMissing].
 */
object OnDeviceCatalog {
    const val SERVICE_ID = "on-device"
    val SERVICE = VoiceService(SERVICE_ID, "On-device", VoiceAccount.None)

    fun model(entry: VoicePackEntry): VoiceModel = VoiceModel(
        serviceId = SERVICE_ID,
        id = entry.id,
        displayName = entry.displayName,
        stt = SttBinding(SttProtocol.OnDevice, endpoint = entry.id, live = entry.streaming),
        origin = VoiceModelOrigin.Builtin,
    )

    fun ref(packId: String): VoiceModelRef = VoiceModelRef(SERVICE_ID, packId, VoiceCapability.STT)

    fun isOnDevice(ref: VoiceModelRef): Boolean = ref.serviceId == SERVICE_ID
}
