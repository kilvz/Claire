package com.wangzan101.mika.voicecall

import com.wangzan101.mika.voicecall.RouteDevice.Bluetooth
import com.wangzan101.mika.voicecall.RouteDevice.Earpiece
import com.wangzan101.mika.voicecall.RouteDevice.Speaker
import com.wangzan101.mika.voicecall.RouteDevice.Wired
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * chooseRoute full matrix: available device set × whether speakerphone is requested × SDK 30/31.
 * The decision policy is independent of the SDK (the SDK only decides how it is carried out: 31+ setCommunicationDevice, below that speakerphone),
 * so every set must give the same answer on both SDKs.
 */
class VoiceCallAudioRouteTest {

    private val sdks = listOf(30, 31)

    /** Asserts both SDKs at once when the answer is the same on each. */
    private fun assertRoute(
        expected: RouteDevice,
        available: List<RouteDevice>,
        speakerRequested: Boolean?,
    ) {
        for (sdk in sdks) {
            assertEquals(
                "available=$available speakerRequested=$speakerRequested sdk=$sdk",
                expected,
                chooseRoute(available, sdk, speakerRequested),
            )
        }
    }

    // ---- Speaker only ----

    @Test
    fun speakerOnly_withoutRequest_staysSpeaker() {
        assertRoute(Speaker, listOf(Speaker), speakerRequested = false)
    }

    @Test
    fun speakerOnly_withRequest_staysSpeaker() {
        assertRoute(Speaker, listOf(Speaker), speakerRequested = true)
    }

    // ---- Earpiece present ----

    @Test
    fun earpiece_withoutRequest_picksEarpiece() {
        assertRoute(Earpiece, listOf(Speaker, Earpiece), speakerRequested = false)
    }

    @Test
    fun earpiece_withRequest_picksSpeaker() {
        assertRoute(Speaker, listOf(Speaker, Earpiece), speakerRequested = true)
    }

    // ---- Wired present (preferred over the earpiece) ----

    @Test
    fun wired_withoutRequest_picksWired() {
        assertRoute(Wired, listOf(Speaker, Earpiece, Wired), speakerRequested = false)
    }

    @Test
    fun wired_withRequest_picksSpeaker() {
        assertRoute(Speaker, listOf(Speaker, Earpiece, Wired), speakerRequested = true)
    }

    // ---- Bluetooth present (preferred over the earpiece) ----

    @Test
    fun bluetooth_withoutRequest_picksBluetooth() {
        assertRoute(Bluetooth, listOf(Speaker, Earpiece, Bluetooth), speakerRequested = false)
    }

    @Test
    fun bluetooth_withRequest_picksSpeaker() {
        assertRoute(Speaker, listOf(Speaker, Earpiece, Bluetooth), speakerRequested = true)
    }

    // ---- All present: wired over Bluetooth, Bluetooth over the earpiece ----

    @Test
    fun allPresent_withoutRequest_picksWired() {
        assertRoute(Wired, listOf(Speaker, Earpiece, Wired, Bluetooth), speakerRequested = false)
    }

    @Test
    fun allPresent_withRequest_picksSpeaker() {
        assertRoute(Speaker, listOf(Speaker, Earpiece, Wired, Bluetooth), speakerRequested = true)
    }

    // ---- No private device, stay on the speaker (tablet form factor: no earpiece, speaker only) ----

    @Test
    fun noPrivateDevice_withoutRequest_staysSpeaker() {
        assertRoute(Speaker, listOf(Speaker), speakerRequested = false)
    }

    @Test
    fun noEarpiece_wiredAndBluetooth_picksWired() {
        assertRoute(Wired, listOf(Speaker, Wired, Bluetooth), speakerRequested = false)
    }

    // ---- Edge cases ----

    @Test
    fun emptyAvailable_fallsBackToSpeaker() {
        assertRoute(Speaker, emptyList(), speakerRequested = false)
        assertRoute(Speaker, emptyList(), speakerRequested = true)
    }

    @Test
    fun speakerRequested_butSpeakerUnavailable_fallsBackToPrivate() {
        assertRoute(Earpiece, listOf(Earpiece), speakerRequested = true)
        assertRoute(Wired, listOf(Earpiece, Wired), speakerRequested = true)
    }

    @Test
    fun orderOfAvailableListDoesNotMatter() {
        assertRoute(Wired, listOf(Bluetooth, Wired, Earpiece, Speaker), speakerRequested = false)
        assertRoute(Bluetooth, listOf(Earpiece, Bluetooth, Speaker), speakerRequested = false)
    }

    // ---- Auto (the user has not chosen yet): the headset takes over, nobody holds the phone to their ear for the assistant, so skip the earpiece ----

    @Test
    fun auto_withWired_picksWiredOverEverything() {
        assertRoute(Wired, listOf(Speaker, Earpiece, Wired, Bluetooth), speakerRequested = null)
    }

    @Test
    fun auto_withBluetooth_picksBluetooth() {
        assertRoute(Bluetooth, listOf(Speaker, Earpiece, Bluetooth), speakerRequested = null)
    }

    @Test
    fun auto_withEarpieceOnly_staysSpeaker() {
        assertRoute(Speaker, listOf(Speaker, Earpiece), speakerRequested = null)
    }

    @Test
    fun auto_withoutSpeaker_fallsBackToEarpiece() {
        assertRoute(Earpiece, listOf(Earpiece), speakerRequested = null)
    }

    @Test
    fun auto_speakerOnly_staysSpeaker() {
        assertRoute(Speaker, listOf(Speaker), speakerRequested = null)
        assertRoute(Speaker, emptyList(), speakerRequested = null)
    }
}
