package com.maya.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.maya.assistant.utils.Logger
import java.util.*

/**
 * TextToSpeechEngine — converts text to voice output.
 * Uses Android's built-in TTS (no API key needed).
 * Supports Bangla, Hindi, English, and other languages.
 */
class TextToSpeechEngine(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TTS"
    }

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var pendingText: String? = null
    private var onDoneCallback: (() -> Unit)? = null

    var onSpeakingStart: (() -> Unit)? = null
    var onSpeakingDone: (() -> Unit)? = null
    var onSpeakingError: ((String) -> Unit)? = null

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isReady = true
            // Set default language
            setLanguage(Locale("bn"))
            Log.d(TAG, "TTS initialized ✅")

            // Set utterance callbacks
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS started speaking")
                    onSpeakingStart?.invoke()
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS finished speaking")
                    onSpeakingDone?.invoke()
                    onDoneCallback?.invoke()
                    onDoneCallback = null
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS error")
                    onSpeakingError?.invoke("Speech error")
                    onDoneCallback?.invoke()
                    onDoneCallback = null
                }
            })

            // Speak any pending text
            pendingText?.let {
                speak(it)
                pendingText = null
            }
        } else {
            Log.e(TAG, "TTS init failed: $status")
        }
    }

    fun setLanguage(locale: Locale): Boolean {
        return try {
            val result = tts?.setLanguage(locale)
            if (result == TextToSpeech.LANG_AVAILABLE || result == TextToSpeech.LANG_COUNTRY_AVAILABLE) {
                Log.d(TAG, "Language set to: ${locale.displayName}")
                true
            } else {
                Log.w(TAG, "Language not available: ${locale.displayName}, falling back to English")
                tts?.setLanguage(Locale.ENGLISH)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Language set error: ${e.message}")
            false
        }
    }

    fun setLanguageByCode(code: String): Boolean {
        val locale = when (code) {
            "bangla" -> Locale("bn")
            "hindi" -> Locale("hi")
            "english" -> Locale("en")
            "creole" -> Locale("fr") // Fallback for Seychellois Creole
            else -> Locale("bn") // Default Bangla
        }
        return setLanguage(locale)
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) {
            Log.w(TAG, "Empty text, skipping TTS")
            onDone?.invoke()
            return
        }

        onDoneCallback = onDone

        if (!isReady) {
            pendingText = text
            Log.d(TAG, "TTS not ready, queuing text")
            return
        }

        // Clean text for TTS (remove emojis, markdown)
        val cleanText = cleanForTTS(text)

        try {
            // Use API 21+ speak method
            val result = tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "maya_${System.currentTimeMillis()}")
            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "speak() returned ERROR")
                onSpeakingError?.invoke("Speech failed")
                onDoneCallback = null
                onDone?.invoke()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Speak error: ${e.message}")
            onSpeakingError?.invoke(e.message ?: "Speech error")
            onDoneCallback = null
            onDone?.invoke()
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Stop error: ${e.message}")
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isReady = false
        } catch (e: Exception) {
            Log.e(TAG, "Shutdown error: ${e.message}")
        }
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking ?: false

    private fun cleanForTTS(text: String): String {
        return text
            .replace(Regex("[\\u{1F600}-\\u{1F64F}\\u{1F300}-\\u{1F5FF}\\u{1F680}-\\u{1F6FF}\\u{1F1E0}-\\u{1F1FF}\\u{2600}-\\u{26FF}\\u{2700}-\\u{27BF}\\u{FE00}-\\u{FE0F}\\u{1F900}-\\u{1F9FF}\\u{1FA00}-\\u{1FA6F}\\u{1FA70}-\\u{1FAFF}\\u{200D}\\u{20E3}\\u{FE0F}\\u{E0020}-\\u{E007F}]"), "")
            .replace(Regex("[*_`#>-]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
