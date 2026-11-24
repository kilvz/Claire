package com.wangzan101.mika.core.cap.transcribe

/** Transcribes an audio file in the workspace into text. */
interface TranscribePort {
    /** The path is `/workspace/...`; reading the file, choosing the provider and sending the request are all done on the App side. */
    suspend fun transcribe(path: String, language: String?): Transcript
}

/** Transcription result; [language] is the language given by the provider/settings, null when unknown. */
class Transcript(val text: String, val language: String? = null)

/** Errors of transcription itself; provider HTTP errors still use [com.wangzan101.mika.core.model.ModelError]. */
sealed class TranscribeError(message: String) : Exception(message) {
    class Unsupported(message: String) : TranscribeError(message)
    class TooLarge(val bytes: Long) : TranscribeError("audio is too large")
    class BadAudio(message: String) : TranscribeError(message)
}
