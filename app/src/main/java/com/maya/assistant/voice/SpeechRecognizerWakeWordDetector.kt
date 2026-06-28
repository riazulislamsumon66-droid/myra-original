package com.maya.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * SpeechRecognizerWakeWordDetector — continuous, free, on-device wake-word
 * detection using Android's built-in SpeechRecognizer.
 *
 * WHY THIS INSTEAD OF A THIRD-PARTY SDK:
 * Picovoice Porcupine (the previous approach) requires a per-developer
 * AccessKey, and as of mid-2026 Picovoice discontinued its free tier
 * entirely (moving to a paid-only model after a 7-day trial). openWakeWord
 * is free and open-source, but has no official Android SDK and has known
 * crash issues with its TFLite melspectrogram model on Android (see
 * https://github.com/dscripka/openWakeWord/issues/223). Android's own
 * SpeechRecognizer is built into every Android device, requires no API key,
 * no account, no usage limits, and no extra library — it's the most
 * dependency-free option that still works reliably.
 *
 * HOW IT WORKS:
 * SpeechRecognizer only listens for a single utterance and then stops
 * (it doesn't support truly continuous listening out of the box), so this
 * class restarts it automatically whenever it ends, creating continuous
 * "wake word" behavior. Each transcript is checked for the wake phrase
 * ("hey maya" or "maya" — see WAKE_PHRASES) using partial results, so the
 * detection latency is similar to a dedicated wake-word engine.
 *
 * TRADE-OFFS vs. a dedicated wake-word engine (Porcupine/openWakeWord):
 * - Slightly higher battery usage, since it's running real speech
 *   recognition rather than a tiny keyword-spotting model.
 * - On devices/Android versions without on-device recognition support,
 *   it may fall back to a network-based recognizer (handled gracefully —
 *   see EXTRA_PREFER_OFFLINE below, which requests on-device recognition
 *   where available).
 * - Restart cycling (stop -> immediately listen again) can have a brief
 *   gap of a few hundred ms between utterances; RESTART_DELAY_MS tunes this.
 *
 * Public interface intentionally matches the old WakeWordDetector
 * (same constructor shape, start()/stop()) so ForegroundVoiceService
 * does not need to change how it's used.
 */
class SpeechRecognizerWakeWordDetector(
    private val context: Context,
    private val onWakeWord: () -> Unit
) {
    private val TAG = "SR_WAKE_WORD"

    // Phrases that count as "the wake word was heard". Includes common
    // misrecognitions of "MAYA" since general speech recognition wasn't
    // trained specifically on this word.
    private val WAKE_PHRASES = listOf(
        "hey maya", "hi maya", "hey mayah", "hey maaya", "ok maya", "okay maya", "maya"
    )

    private val RESTART_DELAY_MS = 800L  // Longer delay = less mic connect/disconnect cycling

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var restartRunnable: Runnable? = null

    fun start() {
        if (isListening) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "SpeechRecognizer not available on this device — wake word disabled")
            return
        }
        isListening = true
        mainHandler.post { startListeningInternal() }
    }

    fun stop() {
        isListening = false
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        restartRunnable = null
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping SpeechRecognizer: ${e.message}")
            }
            speechRecognizer = null
        }
        Log.d(TAG, "Wake word detection STOPPED")
    }

    // Must run on the main thread — SpeechRecognizer requires it.
    private fun startListeningInternal() {
        if (!isListening) return
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createListener())
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                // Request on-device recognition where the OS supports it
                // (Android 9+, supported devices) — keeps wake-word
                // detection private and working without an internet
                // connection. Falls back to network recognition
                // automatically if unsupported.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SpeechRecognizer: ${e.message}")
            scheduleRestart()
        }
    }

    private fun scheduleRestart() {
        if (!isListening) return
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { startListeningInternal() }
        restartRunnable = runnable
        mainHandler.postDelayed(runnable, RESTART_DELAY_MS)
    }

    private fun containsWakePhrase(text: String): Boolean {
        val lower = text.lowercase()
        return WAKE_PHRASES.any { lower.contains(it) }
    }

    private fun createListener(): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            matches?.firstOrNull()?.let { text ->
                if (containsWakePhrase(text)) {
                    Log.d(TAG, "Wake word detected (partial): '$text'")
                    fireWakeWord()
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val hit = matches?.any { containsWakePhrase(it) } == true
            if (hit) {
                Log.d(TAG, "Wake word detected (final): ${matches?.firstOrNull()}")
                fireWakeWord()
            } else {
                // No wake word this round — just keep listening.
                scheduleRestart()
            }
        }

        override fun onError(error: Int) {
            // ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT are expected during
            // normal silence between utterances — not real errors, just
            // restart and keep listening. Other errors are logged but
            // still recovered from the same way, since a background
            // wake-word listener should never just give up.
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // Expected during silence — restart with longer delay to reduce
                    // mic connect/disconnect cycling when no one is speaking.
                    restartRunnable?.let { mainHandler.removeCallbacks(it) }
                    val runnable = Runnable { startListeningInternal() }
                    restartRunnable = runnable
                    mainHandler.postDelayed(runnable, 1200L)
                    return
                }
                else -> {
                    Log.w(TAG, "SpeechRecognizer error: $error")
                }
            }
            scheduleRestart()
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun fireWakeWord() {
        if (!isListening) return
        // Stop listening before firing the callback — the callback
        // typically starts a real command-recording session, which
        // shouldn't race with this wake-word recognizer still running.
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (e: Exception) { /* ignore */ }
        onWakeWord()
    }
}
