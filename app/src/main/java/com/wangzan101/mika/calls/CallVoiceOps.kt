package com.wangzan101.mika.calls

import com.wangzan101.mika.R
import com.wangzan101.mika.voice.cascade.CallUse
import com.wangzan101.mika.voice.model.CallEngine
import com.wangzan101.mika.voice.model.RealtimeSelectionController
import com.wangzan101.mika.voice.model.VoiceErrorCategory
import com.wangzan101.mika.voice.model.VoiceModelError
import com.wangzan101.mika.voice.model.VoiceModelRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wangzan101.mika.calls.CallRuntime.Companion.STANDARD_PROFILE_ID

// Call runtime: voice profiles, call voice selection and audio test
fun CallRuntime.activeVoiceProfile(): CallVoiceProfile {
    val config = settings.state.value
    if (standardCalls) {
        val ready = cascade.blocker(CallUse.Phone) == null
        return CallVoiceProfile(id = STANDARD_PROFILE_ID, name = STANDARD_PROFILE_ID, credentialsConfigured = ready, connectionTested = ready)
    }
    if (realtimeController.migrated) {
        val ref = realtimeController.callCurrent()
        if (ref != null) {
            return runCatching { realtimeController.callProfile() }.getOrElse {
                CallVoiceProfile(id = ref.modelId, name = ref.modelId, credentialsConfigured = false, connectionTested = false)
            }
        }
        return CallVoiceProfile(id = config.activeVoiceProfileId, name = app.getString(R.string.calls_voice_default_name), credentialsConfigured = false, connectionTested = false)
    }
    return voiceProfiles.get(config.activeVoiceProfileId) ?: CallVoiceProfile(
        id = config.activeVoiceProfileId,
        name = app.getString(R.string.calls_voice_default_name),
        speaker = config.speaker,
        credentialsConfigured = config.credentialsConfigured,
        connectionTested = config.credentialsConfigured,
    )
}

internal suspend fun CallRuntime.loadVoiceProfiles() {
    if (realtimeController.migrated) {
        syncRealtimeState()
        return
    }
    val migrated = withContext(Dispatchers.IO) {
        try {
            voiceProfiles.migrateLegacy(settings.state.value, settings::readLegacyCredentials)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
    if (!migrated) return
    val config = settings.state.value
    val profile = voiceProfiles.get(config.activeVoiceProfileId)
    val next = config.copy(
        credentialsConfigured = profile?.credentialsConfigured == true,
        audioVerified = profile != null && voiceProfiles.isAudioVerified(profile.id, config.audioMode),
    )
    if (next != config) runCatching { settings.save(next) }
}

/** Mirrors the catalog verification state into [CallSettings] so every gate reads one source. */
internal fun CallRuntime.syncRealtimeState(mode: CallAudioMode = settings.state.value.audioMode) {
    val ref = callVoiceRef()
    runCatching {
        synchronized(settings) {
            settings.mirrorRealtimeState(
                credentialsConfigured = if (standardCalls) cascade.blocker(CallUse.Phone) == null
                else ref?.let { realtimeController.credentialsConfigured(it) } ?: false,
                audioVerified = ref?.let { realtimeController.isAudioVerified(it, mode) } ?: false,
            )
        }
    }
}

/** Legacy connection path uses the plain provider; after migration the catalog creates it. */
internal fun CallRuntime.realtimeProvider(): CallRealtimeProvider =
    if (realtimeController.migrated) realtimeController.callProvider() else ProfileRealtimeProvider()

suspend fun CallRuntime.selectVoiceProfile(id: String): CallActionResult = onMain {
    if (work != null || lockedDial != null) return@onMain fail("End the current call or cancel the locked-screen dial first")
    if (realtimeController.migrated) {
        val profile = voiceProfiles.get(id) ?: return@onMain fail("The realtime voice configuration does not exist")
        val ref = RealtimeSelectionController.legacyProfileRef(id, profile.model)
        realtimeController.select(ref)
        val config = settings.state.value
        settings.save(config.copy(
            credentialsConfigured = realtimeController.credentialsConfigured(ref),
            audioVerified = realtimeController.isAudioVerified(ref, config.audioMode),
            autoAnswer = false,
        ))
        publish()
        return@onMain ok("Selected ${profile.name}; auto-answer must be turned on again")
    }
    val profile = voiceProfiles.get(id) ?: return@onMain fail("The realtime voice configuration does not exist")
    settings.save(settings.state.value.copy(
        activeVoiceProfileId = profile.id,
        speaker = profile.speaker,
        credentialsConfigured = profile.credentialsConfigured,
        audioVerified = voiceProfiles.isAudioVerified(profile.id, settings.state.value.audioMode),
        autoAnswer = false,
    ))
    publish()
    ok("Selected ${profile.name}; auto-answer must be turned on again")
}

/**
 * Selects the realtime voice used for phone calls after migration: when [ref] is null it follows "voice calls", otherwise phone calls use [ref] on their own (optionally with a phone-only voice [speaker]).
 * Auto-answer is turned off whenever the model actually in use changes, so the user has to confirm again on the new model; credentials and verification mirroring are synced from the catalog.
 */
suspend fun CallRuntime.selectCallVoice(ref: VoiceModelRef?, speaker: String = ""): CallActionResult = onMain {
    if (work != null || lockedDial != null) return@onMain fail("End the current call or cancel the locked-screen dial first")
    if (!realtimeController.migrated) return@onMain fail("The realtime voice configuration has not been migrated yet")
    val before = realtimeController.callCurrent()
    try {
        realtimeController.selectForCalls(ref, speaker)
    } catch (_: VoiceModelError) {
        return@onMain fail("The realtime voice model does not exist")
    }
    val now = realtimeController.callCurrent()
    val config = settings.state.value
    settings.save(config.copy(
        credentialsConfigured = now?.let { realtimeController.credentialsConfigured(it) } ?: false,
        audioVerified = now?.let { realtimeController.isAudioVerified(it, config.audioMode) } ?: false,
        autoAnswer = config.autoAnswer && now == before,
    ))
    publish()
    ok(if (now == before) "Call voice saved" else "Call voice changed; auto-answer must be turned on again")
}

/**
 * Phone calls' own engine and listen/speak models ([com.wangzan101.mika.voice.model.VoiceCatalogStore.setPhoneVoice]);
 * a null [engine] with no models makes phone calls follow the voice settings again, realtime choice included.
 */
suspend fun CallRuntime.selectPhoneVoice(engine: CallEngine?, stt: VoiceModelRef?, tts: VoiceModelRef?, ttsVoice: String = ""): CallActionResult = onMain {
    if (work != null || lockedDial != null) return@onMain fail("End the current call or cancel the locked-screen dial first")
    if (!realtimeController.migrated) return@onMain fail("The realtime voice configuration has not been migrated yet")
    val before = standardCalls to callVoiceRef()
    realtimeController.catalog.setPhoneVoice(engine, stt, tts, ttsVoice)
    if (engine == null && stt == null && tts == null) realtimeController.selectForCalls(null)
    syncRealtimeState()
    val changed = (standardCalls to callVoiceRef()) != before
    if (changed) settings.save(settings.state.value.copy(autoAnswer = false))
    publish()
    ok(if (changed) "Call voice changed; auto-answer must be turned on again" else "Call voice saved")
}

suspend fun CallRuntime.saveVoiceProfile(profile: CallVoiceProfile, credentials: VolcRealtimeCredentials? = null): CallActionResult = onMain {
    if (work != null || lockedDial != null) return@onMain fail("End the current call or cancel the locked-screen dial first")
    if (realtimeController.migrated) {
        val ref = realtimeController.callCurrent() ?: return@onMain fail("Select a realtime voice model first")
        val model = runCatching { realtimeController.model(ref) }.getOrNull()
            ?: return@onMain fail("The realtime voice model does not exist")
        val binding = model.realtime ?: return@onMain fail("The realtime voice model does not support calls")
        if (binding.speaker != profile.speaker) {
            realtimeController.catalog.upsertModel(model.copy(realtime = binding.copy(speaker = profile.speaker)))
        }
        publish()
        return@onMain ok("Realtime voice configuration saved")
    }
    val previous = voiceProfiles.get(profile.id)
    val saved = withContext(Dispatchers.IO) { voiceProfiles.save(profile, credentials) }
    if (settings.state.value.activeVoiceProfileId == saved.id) {
        val serviceChanged = credentials != null || previous != null &&
            (previous.protocol != saved.protocol || previous.endpoint != saved.endpoint || previous.model != saved.model)
        settings.save(settings.state.value.copy(
            speaker = saved.speaker,
            credentialsConfigured = saved.credentialsConfigured,
            audioVerified = voiceProfiles.isAudioVerified(saved.id, settings.state.value.audioMode),
            autoAnswer = if (serviceChanged) false else settings.state.value.autoAnswer,
        ))
    }
    publish()
    ok("Realtime voice configuration saved")
}

suspend fun CallRuntime.deleteVoiceProfile(id: String): CallActionResult = onMain {
    if (work != null || lockedDial != null) return@onMain fail("End the current call or cancel the locked-screen dial first")
    if (realtimeController.migrated) return@onMain fail("Delete the realtime model from the voice model catalog")
    withContext(Dispatchers.IO) { voiceProfiles.delete(id, settings.state.value.activeVoiceProfileId) }
    publish()
    ok("Realtime voice configuration deleted")
}

suspend fun CallRuntime.testVoiceProfile(id: String): CallActionResult = onMain {
    if (work != null || lockedDial != null) return@onMain fail("End the current call or cancel the locked-screen dial first")
    if (realtimeController.migrated) {
        val legacy = voiceProfiles.get(id) ?: return@onMain fail("The realtime voice configuration does not exist")
        val ref = RealtimeSelectionController.legacyProfileRef(id, legacy.model)
        val provider = try {
            realtimeController.provider(ref)
        } catch (e: VoiceModelError) {
            return@onMain fail("The realtime voice configuration is unavailable; check the credentials")
        }
        realtimeController.markConnectionTested(ref, false)
        publish()
        val result = try {
            val session = provider.connect(CallVoiceProfile(), VolcRealtimeCredentials(), "Connection test. Do not generate speech.")
            session.close()
            realtimeController.markConnectionTested(ref, true)
            if (realtimeController.callCurrent() == ref) publish()
            ok("Connected to the realtime voice service; two-way phone audio still requires a test call to verify")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val reason = when ((error as? VoiceModelError)?.category) {
                VoiceErrorCategory.AUTH_FAILED -> "Credentials or service permissions are invalid"
                VoiceErrorCategory.RATE_LIMITED -> "The realtime voice service is receiving too many requests"
                VoiceErrorCategory.TIMEOUT -> "The realtime voice service connection timed out"
                else -> "Could not connect to the realtime voice service"
            }
            fail(reason)
        }
        return@onMain result
    }
    val profile = voiceProfiles.get(id) ?: return@onMain fail("The realtime voice configuration does not exist")
    val credentials = voiceProfiles.credentials(id) ?: return@onMain fail("Configure realtime voice credentials first")
    voiceProfiles.markConnectionTested(id, false)
    publish()
    try {
        val session = realtimeProvider().connect(profile, credentials, "Connection test. Do not generate speech.")
        session.close()
        voiceProfiles.markConnectionTested(id, true)
        if (settings.state.value.activeVoiceProfileId == id) publish()
        ok("Connected to the realtime voice service; two-way phone audio still requires a test call to verify")
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        val reason = when ((error as? VolcRealtimeException)?.kind) {
            VolcRealtimeException.Kind.AUTH -> "Credentials or service permissions are invalid"
            VolcRealtimeException.Kind.RATE_LIMITED -> "The realtime voice service is receiving too many requests"
            VolcRealtimeException.Kind.TIMEOUT -> "The realtime voice service connection timed out"
            else -> "Could not connect to the realtime voice service"
        }
        fail(reason)
    }
}

suspend fun CallRuntime.setAudioVerified(verified: Boolean): CallActionResult = onMain {
    if (work != null || lockedDial != null) return@onMain fail("End the current call or cancel the locked-screen dial first")
    val config = settings.state.value
    if (realtimeController.migrated) {
        val ref = callVoiceRef() ?: return@onMain fail(if (standardCalls) "Select a transcription model for calls first" else "Select a realtime voice model first")
        realtimeController.markAudioVerified(ref, config.audioMode, verified)
        settings.save(config.copy(audioVerified = verified, autoAnswer = verified && config.autoAnswer))
        publish()
        return@onMain ok(if (verified) "Two-way audio verified on this device" else "Two-way audio verification cleared; auto-answer is off")
    }
    val id = config.activeVoiceProfileId
    withContext(Dispatchers.IO) { voiceProfiles.markAudioVerified(id, config.audioMode, verified) }
    settings.save(config.copy(audioVerified = verified, autoAnswer = verified && config.autoAnswer))
    publish()
    ok(if (verified) "Two-way audio verified on this device" else "Two-way audio verification cleared; auto-answer is off")
}
