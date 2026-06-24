package com.maya.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Speech-to-Text engine using Android's built-in SpeechRecognizer.
 * Supports offline recognition for: English, Bangla, Hindi.
 */
class SpeechToTextEngine(private val context: Context) {

    companion object {
        private const val TAG = "STT"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    var isListening = false
        private set

    fun destroy() {
        isListening = false
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    interface STTCallback {
        fun onSpeechResult(text: String)
        fun onSpeechError(errorCode: Int)
        fun onSpeechStart()
        fun onSpeechEnd()
    }

    fun setCallback(callback: STTCallback?) {
        // Callback is set via startListening
    }

    fun startListening(languageCode: String = "en-US", callback: STTCallback? = null) {
        if (isListening) return

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available")
            callback?.onSpeechError(SpeechRecognizer.ERROR_CLIENT)
            return
        }

        speechRecognizer = SpeechRecognizer.create(context(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    Log.d(TAG, "Ready for speech")
                    callback?.onSpeechStart()
                }
                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "Speech beginning")
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                    Log.d(TAG, "Speech ended")
                    callback?.onSpeechEnd()
                }
                override fun onError(error: Int) {
                    isListening = false
                    Log.e(TAG, "STT Error: $error")
                    callback?.onSpeechError(error)
                }
                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    Log.d(TAG, "STT Result: $text")
                    if (text.isNotBlank()) {
                        callback?.onSpeechResult(text)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    Log.d(TAG, "Partial: ${matches?.firstOrNull() ?: ""}")
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

            startListening(intent)
        }
    }

    fun stopListening() {
        isListening = false
        speechRecognizer?.stopListening()
    }

    companion object {
        fun getLanguageCodeForLocale(locale: Locale): String {
            return when (locale.language) {
                "bn" -> "bn-BD"
                "hi" -> "hi-IN"
                "en" -> "en-US"
                else -> "en-US"
            }
        }
    }
}
