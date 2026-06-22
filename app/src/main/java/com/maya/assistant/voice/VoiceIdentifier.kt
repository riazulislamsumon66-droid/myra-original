package com.maya.assistant.voice

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * VoiceIdentifier — Identifies who is speaking using voice fingerprinting.
 *
 * Works by:
 * 1. Recording a short voice sample during enrollment
 * 2. Extracting voice features (pitch, formant frequencies, spectral envelope)
 * 3. Storing voice profile as a "voice print"
 * 4. During recognition, comparing incoming voice against stored profiles
 *
 * Supports 3 voice slots:
 * - Owner (Sumon) — full access
 * - Known Person (Tuha Moni) — recognized by name
 * - Unknown — unrecognized voice
 */
class VoiceIdentifier(private val context: Context) {

    companion object {
        private const val TAG = "VoiceIdentifier"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val ENROLLMENT_DURATION_MS = 5000 // 5 seconds enrollment
        private const val RECOGNITION_DURATION_MS = 2000 // 2 seconds recognition
        private const val NUM_VOICE_SLOTS = 3

        // Voice profile keys
        private const val PREF_VOICE_PREFIX = "voice_profile_"
        private const val PREF_VOICE_NAME = "voice_name_"
        private const val PREF_VOICE_ENROLLED = "voice_enrolled_"
    }

    data class VoiceProfile(
        val name: String,
        val features: FloatArray,
        val enrolled: Boolean = false
    )

    data class RecognitionResult(
        val speakerName: String,
        val confidence: Float,
        val slotIndex: Int
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("maya_voice_prefs", Context.MODE_PRIVATE)

    private var isRecording = false
    private var recordingJob: Job? = null

    /**
     * Enroll a voice for a specific slot.
     * @param slotIndex 0=Owner, 1=Known Person, 2=Unknown
     * @param name Display name for this voice
     * @param onProgress Callback for enrollment progress (0-100)
     * @param onComplete Callback when enrollment is done
     */
    fun enrollVoice(
        slotIndex: Int,
        name: String,
        onProgress: (Int) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        if (slotIndex < 0 || slotIndex >= NUM_VOICE_SLOTS) {
            onComplete(false)
            return
        }

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                onProgress(10)

                // Record audio
                val audioData = recordAudio(ENROLLMENT_DURATION_MS) { progress ->
                    onProgress(10 + (progress * 0.4f).toInt())
                }

                if (audioData == null || audioData.isEmpty()) {
                    withContext(Dispatchers.Main) { onComplete(false) }
                    return@launch
                }

                onProgress(50)

                // Extract features
                val features = extractVoiceFeatures(audioData)
                onProgress(70)

                // Save profile
                saveVoiceProfile(slotIndex, name, features)
                onProgress(90)

                Log.i(TAG, "Voice enrolled: $name (slot $slotIndex, ${features.size} features)")
                onProgress(100)

                withContext(Dispatchers.Main) { onComplete(true) }

            } catch (e: Exception) {
                Log.e(TAG, "Enrollment failed: ${e.message}")
                withContext(Dispatchers.Main) { onComplete(false) }
            }
        }
    }

    /**
     * Recognize who is speaking.
     * @param onResult Callback with recognition result
     */
    fun recognizeVoice(onResult: (RecognitionResult) -> Unit) {
        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // Record audio
                val audioData = recordAudio(RECOGNITION_DURATION_MS) { }

                if (audioData == null || audioData.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        onResult(RecognitionResult("Unknown", 0f, -1))
                    }
                    return@launch
                }

                // Extract features
                val features = extractVoiceFeatures(audioData)

                // Compare against enrolled profiles
                var bestMatch = "Unknown"
                var bestConfidence = 0f
                var bestSlot = -1

                for (i in 0 until NUM_VOICE_SLOTS) {
                    if (isVoiceEnrolled(i)) {
                        val profile = loadVoiceProfile(i)
                        if (profile != null) {
                            val similarity = compareVoiceFeatures(features, profile.features)
                            Log.d(TAG, "Comparison with ${profile.name}: similarity=$similarity")
                            if (similarity > bestConfidence && similarity > 0.6f) {
                                bestConfidence = similarity
                                bestMatch = profile.name
                                bestSlot = i
                            }
                        }
                    }
                }

                Log.i(TAG, "Recognition result: $bestMatch (confidence: $bestConfidence)")

                withContext(Dispatchers.Main) {
                    onResult(RecognitionResult(bestMatch, bestConfidence, bestSlot))
                }

            } catch (e: Exception) {
                Log.e(TAG, "Recognition failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    onResult(RecognitionResult("Unknown", 0f, -1))
                }
            }
        }
    }

    /**
     * Check if a voice slot is enrolled.
     */
    fun isVoiceEnrolled(slotIndex: Int): Boolean {
        return prefs.getBoolean(PREF_VOICE_ENROLLED + slotIndex, false)
    }

    /**
     * Get the name of an enrolled voice.
     */
    fun getVoiceName(slotIndex: Int): String {
        return prefs.getString(PREF_VOICE_NAME + slotIndex, "Unknown") ?: "Unknown"
    }

    /**
     * Get all enrolled voice profiles.
     */
    fun getEnrolledProfiles(): List<VoiceProfile> {
        val profiles = mutableListOf<VoiceProfile>()
        for (i in 0 until NUM_VOICE_SLOTS) {
            if (isVoiceEnrolled(i)) {
                val profile = loadVoiceProfile(i)
                if (profile != null) profiles.add(profile)
            }
        }
        return profiles
    }

    /**
     * Delete a voice profile.
     */
    fun deleteVoiceProfile(slotIndex: Int) {
        prefs.edit()
            .remove(PREF_VOICE_PREFIX + slotIndex)
            .remove(PREF_VOICE_NAME + slotIndex)
            .putBoolean(PREF_VOICE_ENROLLED + slotIndex, false)
            .apply()
        Log.i(TAG, "Voice profile deleted: slot $slotIndex")
    }

    /**
     * Stop any ongoing recording.
     */
    fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
    }

    // ── Audio Recording ──────────────────────────────────────────

    private fun recordAudio(durationMs: Int, onProgress: (Float) -> Unit): ShortArray? {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize <= 0) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            return null
        }

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize * 2
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            audioRecord.release()
            return null
        }

        val totalSamples = (SAMPLE_RATE * durationMs / 1000)
        val audioData = ShortArray(totalSamples)
        val buffer = ShortArray(bufferSize)
        var samplesRead = 0

        isRecording = true
        audioRecord.startRecording()

        while (isRecording && samplesRead < totalSamples) {
            val toRead = minOf(buffer.size, totalSamples - samplesRead)
            val read = audioRecord.read(buffer, 0, toRead)
            if (read > 0) {
                System.arraycopy(buffer, 0, audioData, samplesRead, read)
                samplesRead += read
                onProgress(samplesRead.toFloat() / totalSamples)
            }
        }

        isRecording = false
        audioRecord.stop()
        audioRecord.release()

        return if (samplesRead > 0) audioData.copyOfRange(0, samplesRead) else null
    }

    // ── Voice Feature Extraction ─────────────────────────────────

    /**
     * Extract voice features from audio data.
     * Uses a simplified approach: pitch detection + spectral analysis.
     */
    private fun extractVoiceFeatures(audioData: ShortArray): FloatArray {
        val features = mutableListOf<Float>()

        // 1. Normalize audio
        val floatData = audioData.map { it.toFloat() / Short.MAX_VALUE }.toFloatArray()

        // 2. RMS energy
        val rms = sqrt(floatData.map { it * it }.average().toFloat())
        features.add(rms)

        // 3. Zero crossing rate
        var zeroCrossings = 0
        for (i in 1 until floatData.size) {
            if ((floatData[i] >= 0 && floatData[i - 1] < 0) ||
                (floatData[i] < 0 && floatData[i - 1] >= 0)) {
                zeroCrossings++
            }
        }
        features.add(zeroCrossings.toFloat() / floatData.size)

        // 4. Pitch estimation using autocorrelation
        val pitch = estimatePitch(floatData, SAMPLE_RATE)
        features.add(pitch)

        // 5. Spectral centroid
        val spectralCentroid = computeSpectralCentroid(floatData, SAMPLE_RATE)
        features.add(spectralCentroid)

        // 6. Spectral energy in bands
        val bandEnergies = computeBandEnergies(floatData, SAMPLE_RATE)
        features.addAll(bandEnergies.toList())

        // 7. MFCC-like coefficients (simplified)
        val mfcc = computeSimplifiedMFCC(floatData, SAMPLE_RATE)
        features.addAll(mfcc.toList())

        return features.toFloatArray()
    }

    private fun estimatePitch(audio: FloatArray, sampleRate: Int): Float {
        // Autocorrelation-based pitch detection
        val minFreq = 80f  // Hz (lowest human voice)
        val maxFreq = 400f // Hz (highest human voice)
        val minLag = (sampleRate / maxFreq).toInt()
        val maxLag = (sampleRate / minFreq).toInt()

        if (maxLag >= audio.size) return 0f

        var bestCorrelation = 0f
        var bestLag = minLag

        for (lag in minLag..minOf(maxLag, audio.size / 2)) {
            var correlation = 0f
            for (i in 0 until audio.size - lag) {
                correlation += audio[i] * audio[i + lag]
            }
            correlation /= (audio.size - lag)

            if (correlation > bestCorrelation) {
                bestCorrelation = correlation
                bestLag = lag
            }
        }

        return if (bestLag > 0) sampleRate.toFloat() / bestLag else 0f
    }

    private fun computeSpectralCentroid(audio: FloatArray, sampleRate: Int): Float {
        // Simple DFT-based spectral centroid
        val n = minOf(audio.size, 1024)
        val halfN = n / 2

        var weightedSum = 0f
        var magnitudeSum = 0f

        for (k in 0 until halfN) {
            var real = 0f
            var imag = 0f
            for (t in 0 until n) {
                val angle = -2f * PI.toFloat() * k * t / n
                real += audio[t] * cos(angle)
                imag += audio[t] * sin(angle)
            }
            val magnitude = sqrt(real * real + imag * imag)
            weightedSum += k * magnitude
            magnitudeSum += magnitude
        }

        return if (magnitudeSum > 0) (weightedSum / magnitudeSum) * (sampleRate.toFloat() / n) else 0f
    }

    private fun computeBandEnergies(audio: FloatArray, sampleRate: Int): FloatArray {
        // Divide spectrum into bands and compute energy in each
        val bands = listOf(
            0f, 200f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
        )
        val n = minOf(audio.size, 1024)
        val bandEnergies = FloatArray(bands.size - 1)

        for (b in 0 until bands.size - 1) {
            val lowBin = ((bands[b] * n) / sampleRate).toInt().coerceIn(0, n / 2 - 1)
            val highBin = ((bands[b + 1] * n) / sampleRate).toInt().coerceIn(0, n / 2 - 1)

            var energy = 0f
            for (k in lowBin until highBin) {
                var real = 0f
                var imag = 0f
                for (t in 0 until n) {
                    val angle = -2f * PI.toFloat() * k * t / n
                    real += audio[t] * cos(angle)
                    imag += audio[t] * sin(angle)
                }
                energy += real * real + imag * imag
            }
            bandEnergies[b] = energy / maxOf(1, highBin - lowBin)
        }

        // Normalize
        val maxEnergy = bandEnergies.maxOrNull() ?: 1f
        if (maxEnergy > 0) {
            for (i in bandEnergies.indices) {
                bandEnergies[i] /= maxEnergy
            }
        }

        return bandEnergies
    }

    private fun computeSimplifiedMFCC(audio: FloatArray, sampleRate: Int): FloatArray {
        // Simplified MFCC computation (13 coefficients)
        val numCoefficients = 13
        val n = minOf(audio.size, 1024)
        val mfcc = FloatArray(numCoefficients)

        // Apply Hamming window
        val windowed = FloatArray(n)
        for (i in 0 until n) {
            windowed[i] = audio[i] * (0.54f - 0.46f * cos(2f * PI.toFloat() * i / (n - 1)))
        }

        // Compute power spectrum
        val powerSpectrum = FloatArray(n / 2)
        for (k in 0 until n / 2) {
            var real = 0f
            var imag = 0f
            for (t in 0 until n) {
                val angle = -2f * PI.toFloat() * k * t / n
                real += windowed[t] * cos(angle)
                imag += windowed[t] * sin(angle)
            }
            powerSpectrum[k] = (real * real + imag * imag) / n
        }

        // Apply mel filterbank and compute log energies
        val numFilters = 26
        val melEnergies = FloatArray(numFilters)

        for (i in 0 until numFilters) {
            val centerFreq = 2595f * log10(1f + (i * sampleRate / 2f) / numFilters / 700f)
            val bin = ((centerFreq * n) / sampleRate).toInt().coerceIn(0, n / 2 - 1)
            melEnergies[i] = log10(maxOf(powerSpectrum[bin], 1e-10f))
        }

        // Apply DCT to get MFCC
        for (i in 0 until numCoefficients) {
            var sum = 0f
            for (j in 0 until numFilters) {
                sum += melEnergies[j] * cos(PI.toFloat() * i * (2 * j + 1) / (2 * numFilters))
            }
            mfcc[i] = sum
        }

        return mfcc
    }

    // ── Voice Comparison ────────────────────────────────────────

    /**
     * Compare two voice feature vectors.
     * Returns similarity score (0.0 to 1.0).
     */
    private fun compareVoiceFeatures(features1: FloatArray, features2: FloatArray): Float {
        if (features1.isEmpty() || features2.isEmpty()) return 0f

        val len = minOf(features1.size, features2.size)
        var dotProduct = 0f
        var norm1 = 0f
        var norm2 = 0f

        for (i in 0 until len) {
            dotProduct += features1[i] * features2[i]
            norm1 += features1[i] * features1[i]
            norm2 += features2[i] * features2[i]
        }

        val magnitude = sqrt(norm1) * sqrt(norm2)
        return if (magnitude > 0) (dotProduct / magnitude).coerceIn(0f, 1f) else 0f
    }

    // ── Persistence ─────────────────────────────────────────────

    private fun saveVoiceProfile(slotIndex: Int, name: String, features: FloatArray) {
        val sb = StringBuilder()
        for (i in features.indices) {
            if (i > 0) sb.append(",")
            sb.append(features[i])
        }

        prefs.edit()
            .putString(PREF_VOICE_PREFIX + slotIndex, sb.toString())
            .putString(PREF_VOICE_NAME + slotIndex, name)
            .putBoolean(PREF_VOICE_ENROLLED + slotIndex, true)
            .apply()
    }

    private fun loadVoiceProfile(slotIndex: Int): VoiceProfile? {
        val featuresStr = prefs.getString(PREF_VOICE_PREFIX + slotIndex, null) ?: return null
        val name = prefs.getString(PREF_VOICE_NAME + slotIndex, "Unknown") ?: "Unknown"

        return try {
            val features = featuresStr.split(",").map { it.trim().toFloat() }.toFloatArray()
            VoiceProfile(name, features, true)
        } catch (e: Exception) {
            null
        }
    }
}
