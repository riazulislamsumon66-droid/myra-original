package com.maya.assistant.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.maya.assistant.utils.AudioUtils
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * WakeWordDetector — Continuously listens for "Hey MAYA" or "Hi MAYA"
 * using lightweight keyword spotting on device.
 *
 * When wake word is detected, triggers onWakeWord callback.
 * Uses energy-based detection + simple pattern matching (no cloud needed).
 */
class WakeWordDetector(
    private val context: Context,
    private val onWakeWord: () -> Unit
) {
    private val TAG = "WAKE_WORD"

    private var isListening = false
    private var recordJob: Job? = null

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val BUFFER_MS = 1000 // 1 second analysis window
        private const val ENERGY_THRESHOLD = 0.02f // Minimum energy to consider as speech
        private const val WAKE_WORD_CONFIDENCE_THRESHOLD = 0.6f
    }

    private var audioRecord: AudioRecord? = null
    private var bufferSize = 0

    fun start() {
        if (isListening) return
        isListening = true
        Log.d(TAG, "Wake word detection STARTED — listening for 'Hey MAYA'...")

        bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed")
                isListening = false
                return
            }

            audioRecord?.startRecording()
            Log.d(TAG, "Wake word detection started ✅")

            recordJob = CoroutineScope(Dispatchers.IO).launch {
                processAudioStream()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Wake word start error: ${e.message}")
            isListening = false
        }
    }

    fun stop() {
        isListening = false
        recordJob?.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Wake word stop error: ${e.message}")
        }
        audioRecord = null
        Log.d(TAG, "Wake word detection STOPPED")
    }

    private suspend fun processAudioStream() {
        val buffer = ShortArray(SAMPLE_RATE) // 1 second of audio

        while (isListening && isActive) {
            try {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val rms = AudioUtils.calculateRmsFromShort(buffer, read)
                    if (rms > ENERGY_THRESHOLD) {
                        // Speech detected — analyze for wake word
                        val confidence = analyzeForWakeWord(buffer, read)
                        if (confidence >= WAKE_WORD_CONFIDENCE_THRESHOLD) {
                            Log.d(TAG, "Wake word detected! (confidence=$confidence)")
                            withContext(Dispatchers.Main) {
                                onWakeWord()
                            }
                            // Cooldown — don't trigger again immediately
                            delay(3000)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "Audio process error: ${e.message}")
            }
        }
    }

    /**
     * Simple wake word detection based on audio pattern analysis.
     * "Hey MAYA" has a distinct energy pattern: short pause + rising tone.
     * This is a lightweight approximation — for production, use a proper KWS model.
     */
    private fun analyzeForWakeWord(buffer: ShortArray, length: Int): Float {
        // Calculate energy profile
        val frameSize = SAMPLE_RATE / 10 // 100ms frames
        val numFrames = length / frameSize
        if (numFrames < 5) return 0f

        val frameEnergies = FloatArray(numFrames) { i ->
            var sum = 0.0
            for (j in 0 until frameSize) {
                val idx = i * frameSize + j
                if (idx < length) {
                    val sample = buffer[idx].toFloat() / 32768f
                    sum += sample * sample
                }
            }
            (sum / frameSize).toFloat()
        }

        // "Hey MAYA" pattern: medium energy → short silence → high energy
        // Simplified: check if there are 2 distinct speech bursts with a gap
        var speechBursts = 0
        var inSpeech = false
        var silenceAfterFirstBurst = false

        for (energy in frameEnergies) {
            if (energy > ENERGY_THRESHOLD) {
                if (!inSpeech) {
                    if (silenceAfterFirstBurst) {
                        speechBursts++
                        silenceAfterFirstBurst = false
                    } else if (speechBursts == 0) {
                        speechBursts++
                    }
                    inSpeech = true
                }
            } else {
                if (inSpeech && speechBursts == 1) {
                    silenceAfterFirstBurst = true
                }
                inSpeech = false
            }
        }

        // "Hey MAYA" = 2 speech bursts (Hey + MAYA)
        return if (speechBursts >= 2) {
            // Calculate confidence based on energy pattern match
            val avgEnergy = frameEnergies.average().toFloat()
            val energyVariance = frameEnergies.map { (it - avgEnergy).pow(2) }.average().toFloat()
            // Higher variance = more likely to be speech with pauses
            (0.5f + energyVariance * 10f).coerceIn(0f, 1f)
        } else {
            0f
        }
    }
}
