package com.wangzan101.mika.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** A speech synthesis engine registered on the phone. */
data class SystemTtsEngineInfo(
    val packageName: String,
    val label: String,
    val isDefault: Boolean = false,
)

/** A voice provided by an engine; [name] is [android.speech.tts.Voice.getName], and that is what is stored in settings. */
data class SystemTtsVoiceInfo(
    val name: String,
    val locale: Locale? = null,
    val displayName: String = name,
)

/**
 * Lists speech synthesis engines on the phone and the voices of one engine, using only public APIs: engines are queried by [TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE]
 * (the action is declared in the manifest's `<queries>`), voices use [TextToSpeech.getVoices]. No specific vendor package name is recognised.
 */
object SystemTtsDiscovery {
    /** Upper bound for waiting for engine initialisation; a cold engine has to bring its process up first. */
    const val VOICES_TIMEOUT_MS = 5_000L

    /** Installed engines, default engine first, the rest sorted by name. Empty when none found. */
    suspend fun engines(context: Context): List<SystemTtsEngineInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val services = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, 0)
            }
        } catch (e: RuntimeException) {
            emptyList()
        }
        val defaultPackage = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
        } catch (e: RuntimeException) {
            null
        }
        val engines = services.mapNotNull { info ->
            val pkg = info.serviceInfo?.packageName ?: return@mapNotNull null
            val label = runCatching { info.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
            SystemTtsEngineInfo(pkg, label, isDefault = pkg == defaultPackage)
        }.distinctBy { it.packageName }
        sortEngines(engines)
    }

    internal fun sortEngines(engines: List<SystemTtsEngineInfo>): List<SystemTtsEngineInfo> =
        engines.sortedWith(compareByDescending<SystemTtsEngineInfo> { it.isDefault }.thenBy { it.label.lowercase() })

    /**
     * Voices of [enginePackage] (empty = default engine); only lists those with data installed, with those in the same language as [preferred] first.
     * Returns an empty list when the engine is not installed, initialisation fails or times out; always shuts down after querying.
     */
    suspend fun voices(context: Context, enginePackage: String, preferred: Locale?): List<SystemTtsVoiceInfo> =
        withContext(Dispatchers.Main) {
            val ready = CompletableDeferred<Int>()
            val listener = TextToSpeech.OnInitListener { ready.complete(it) }
            val tts = try {
                if (enginePackage.isBlank()) TextToSpeech(context.applicationContext, listener)
                else TextToSpeech(context.applicationContext, listener, enginePackage)
            } catch (e: RuntimeException) {
                return@withContext emptyList()
            }
            try {
                val status = withTimeoutOrNull(VOICES_TIMEOUT_MS) { ready.await() }
                if (status != TextToSpeech.SUCCESS) return@withContext emptyList()
                val voices = try {
                    tts.voices.orEmpty().filterNot { it.notInstalled() }.map { v ->
                        val language = v.locale?.displayName?.takeIf { it.isNotBlank() }
                        SystemTtsVoiceInfo(v.name, v.locale, if (language != null) "$language · ${v.name}" else v.name)
                    }
                } catch (e: RuntimeException) {
                    emptyList()
                }
                sortVoices(voices, preferred)
            } finally {
                runCatching { tts.shutdown() }
            }
        }

    internal fun sortVoices(voices: List<SystemTtsVoiceInfo>, preferred: Locale?): List<SystemTtsVoiceInfo> =
        voices.sortedWith(
            compareByDescending<SystemTtsVoiceInfo> { preferred != null && it.locale?.language == preferred.language }
                .thenBy { it.displayName.lowercase() },
        )
}
