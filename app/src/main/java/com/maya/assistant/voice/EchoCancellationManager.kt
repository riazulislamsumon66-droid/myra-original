package com.maya.assistant.voice

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.maya.assistant.utils.Logger

/**
 * Attaches Android's built-in audio effects to the AudioRecord session:
 *  - AEC  (Acoustic Echo Canceler)  — removes MAYA's own TTS voice from mic input
 *  - NS   (Noise Suppressor)        — filters background noise
 *  - AGC  (Automatic Gain Control)  — keeps mic volume consistent
 *
 * Call attach() right after AudioRecord.startRecording().
 * Call release() inside AudioRecorder.stop().
 */
object EchoCancellationManager {
    private val TAG = "ECHO"

    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    fun attach(audioSessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
                Logger.d(TAG, "AEC enabled ✅")
            } else {
                Logger.w(TAG, "AEC not available on this device")
            }

            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
                Logger.d(TAG, "Noise Suppressor enabled ✅")
            } else {
                Logger.w(TAG, "NS not available on this device")
            }

            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
                Logger.d(TAG, "AGC enabled ✅")
            } else {
                Logger.w(TAG, "AGC not available on this device")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to attach audio effects: ${e.message}")
        }
    }

    fun release() {
        try {
            aec?.release(); aec = null
            ns?.release();  ns  = null
            agc?.release(); agc = null
            Logger.d(TAG, "Audio effects released")
        } catch (e: Exception) {
            Logger.e(TAG, "Release error: ${e.message}")
        }
    }
}
