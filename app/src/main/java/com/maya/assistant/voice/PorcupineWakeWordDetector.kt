package com.maya.assistant.voice

import ai.picovoice.porcupine.BuiltInKeyword
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import ai.picovoice.porcupine.exception.PorcupineActivationException
import ai.picovoice.porcupine.exception.PorcupineActivationLimitException
import ai.picovoice.porcupine.exception.PorcupineActivationThrottledException
import ai.picovoice.porcupine.exception.PorcupineInvalidArgumentException
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.maya.assistant.BuildConfig

/**
 * PorcupineWakeWordDetector — accurate, low-power, on-device wake-word
 * detection using Picovoice Porcupine.
 *
 * Replaces the previous WakeWordDetector, which used a simple audio-energy
 * threshold and could false-trigger on any loud sound (claps, doors, etc.)
 * while also missing genuine wake-word utterances. Porcupine uses a real
 * trained keyword-spotting model and runs entirely on-device — no audio
 * ever leaves the phone.
 *
 * SETUP REQUIRED before this works:
 * 1. Get a free AccessKey from https://console.picovoice.ai/ (sign up,
 *    no credit card needed for personal/low-volume use).
 * 2. Put it in gradle.properties as PICOVOICE_ACCESS_KEY=your-key-here
 *    (already wired up via BuildConfig — no source-code edits needed).
 * 3. (Optional) To use a custom wake word like "Hey MAYA" instead of the
 *    built-in "Jarvis": train one for free on the Picovoice Console
 *    (takes seconds), download the resulting .ppn file, drop it into
 *    app/src/main/assets/, and switch from setBuiltInKeyword() to
 *    setKeywordPath() below.
 *
 * Public interface intentionally matches the old WakeWordDetector
 * (same constructor shape, start()/stop()) so ForegroundVoiceService
 * does not need to change how it's used.
 */
class PorcupineWakeWordDetector(
    private val context: Context,
    private val onWakeWord: () -> Unit
) {
    private val TAG = "PORCUPINE_WAKE"

    private val ACCESS_KEY = BuildConfig.PICOVOICE_ACCESS_KEY

    private var porcupineManager: PorcupineManager? = null
    private var isListening = false

    // Fallback to the old detector if Porcupine can't initialize (e.g. the
    // AccessKey hasn't been set yet, or RECORD_AUDIO isn't granted yet) so
    // the app still has *some* wake-word behavior rather than none.
    private var fallback: WakeWordDetector? = null

    fun start() {
        if (isListening) return

        if (ACCESS_KEY.isBlank()) {
            Log.w(TAG, "No Picovoice AccessKey configured — falling back to energy-based detector. " +
                "Get a free key at https://console.picovoice.ai/ and set PICOVOICE_ACCESS_KEY in gradle.properties")
            startFallback()
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            // PorcupineManager.build() opens the microphone internally and
            // throws if this permission is missing — checking here avoids
            // a crash and gives a clear log reason instead.
            Log.w(TAG, "RECORD_AUDIO not granted — cannot start Porcupine, falling back")
            startFallback()
            return
        }

        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(ACCESS_KEY)
                // "Jarvis" is a free built-in keyword — no custom training
                // needed to get started. Swap for a custom "Hey MAYA" .ppn
                // file later if desired (see class doc above).
                .setBuiltInKeyword(BuiltInKeyword.JARVIS)
                .setSensitivity(0.6f) // 0..1 — higher = fewer misses, more false alarms
                .build(context, object : PorcupineManagerCallback {
                    override fun invoke(keywordIndex: Int) {
                        Log.d(TAG, "Wake word detected (index=$keywordIndex)")
                        onWakeWord()
                    }
                })
            porcupineManager?.start()
            isListening = true
            Log.d(TAG, "Porcupine wake word detection STARTED")
        } catch (e: PorcupineActivationThrottledException) {
            // Free-tier AccessKeys are limited to a small number of active
            // devices at once. If you see this in production, it means
            // either too many test devices are using the same key, or it's
            // time to upgrade the Picovoice plan.
            Log.e(TAG, "Porcupine AccessKey throttled (too many active devices on free tier) — falling back")
            startFallback()
        } catch (e: PorcupineActivationLimitException) {
            Log.e(TAG, "Porcupine AccessKey activation limit reached — falling back")
            startFallback()
        } catch (e: PorcupineActivationException) {
            Log.e(TAG, "Porcupine AccessKey invalid or activation failed: ${e.message} — falling back")
            startFallback()
        } catch (e: PorcupineInvalidArgumentException) {
            Log.e(TAG, "Porcupine invalid argument (check AccessKey format): ${e.message} — falling back")
            startFallback()
        } catch (e: Exception) {
            Log.e(TAG, "Porcupine init failed (${e.message}) — falling back to energy-based detector")
            startFallback()
        }
    }

    private fun startFallback() {
        fallback = WakeWordDetector(context, onWakeWord)
        fallback?.start()
        isListening = true
    }

    fun stop() {
        if (!isListening) return
        try {
            porcupineManager?.stop()
            porcupineManager?.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Porcupine: ${e.message}")
        }
        porcupineManager = null
        fallback?.stop()
        fallback = null
        isListening = false
        Log.d(TAG, "Wake word detection STOPPED")
    }

    fun isActive() = isListening
}
