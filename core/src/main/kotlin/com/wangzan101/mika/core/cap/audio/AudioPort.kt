package com.wangzan101.mika.core.cap.audio

import com.wangzan101.mika.core.model.SavedAudio
import com.wangzan101.mika.core.model.StoredAudio
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse

/**
 * The app-side implementation of the audio capability: call TTS, write the synthesized result into the workspace, and save workspace audio to the music library.
 * Path normalization, symlink checks, and file-handle verification are all done by the app side; the core only recognizes `/workspace/...`.
 */
interface AudioPort {
    val modelName: String
    val capabilities: TtsCapabilities

    /** Synthesizes a passage; throws [com.wangzan101.mika.core.model.ModelError] or [com.wangzan101.mika.core.model.TtsError] on failure. */
    suspend fun synthesize(request: TtsRequest): TtsResponse

    /** Writes the synthesized result into the workspace at `/workspace/out/audio/` and returns the final path; writes go through the workspace gate. */
    suspend fun store(response: TtsResponse, nameHint: String?, text: String): StoredAudio

    /** Saves audio from the workspace into `Music/Claire` (MediaStore.Audio). */
    suspend fun saveToMusic(path: String, name: String?): SavedAudio

    /** Synthesizes and plays a passage on this device (does not write to the workspace, does not send); throws the same errors on failure. */
    suspend fun speak(request: TtsRequest)
}
