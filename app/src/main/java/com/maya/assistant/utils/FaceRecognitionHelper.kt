package com.maya.assistant.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * FaceRecognitionHelper — face detection and recognition manager.
 * 
 * Current implementation: enrollment UI + storage framework.
 * Full ML Kit integration requires CameraX + ML Kit Face Detect dependencies.
 * Add to build.gradle: implementation 'com.google.mlkit:face-detection:16.1.7'
 * Then uncomment the ML Kit imports and processing code.
 */
class FaceRecognitionHelper(private val context: Context) {

    companion object {
        private const val TAG = "FaceRecognition"
        private const val FACE_DIR = "maya_faces"
        private const val PREF_FACE_PREFIX = "face_signature_"
        private const val PREF_FACE_NAME = "face_name_"
        private const val PREF_FACE_ENROLLED = "face_enrolled_"
        private const val NUM_FACE_SLOTS = 10

        // Singleton instance
        @Volatile
        private var instance: FaceRecognitionHelper? = null

        fun getInstance(context: Context): FaceRecognitionHelper {
            return instance ?: synchronized(this) {
                instance ?: FaceRecognitionHelper(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("maya_face_prefs", Context.MODE_PRIVATE)

    /**
     * Enroll a face (placeholder — stores name and a generated signature).
     * For real face recognition, integrate with CameraX + ML Kit Face Detection.
     */
    fun enrollFace(name: String, onResult: (Boolean) -> Unit) {
        try {
            // Find next available slot
            val slotIndex = getNextAvailableSlot()
            if (slotIndex < 0) {
                Log.w(TAG, "No free face slots")
                onResult(false)
                return
            }

            // Generate face signature (placeholder — real impl uses ML embedding)
            val signature = generateSignature()
            
            // Save profile
            saveFaceProfile(slotIndex, name, signature)
            Log.i(TAG, "Face enrolled: $name (slot $slotIndex)")
            onResult(true)
        } catch (e: Exception) {
            Log.e(TAG, "Face enrollment failed: ${e.message}")
            onResult(false)
        }
    }

    /**
     * Get all enrolled face profiles.
     */
    fun getEnrolledProfiles(): List<Pair<String, Int>> {
        val profiles = mutableListOf<Pair<String, Int>>()
        for (i in 0 until NUM_FACE_SLOTS) {
            if (isFaceEnrolled(i)) {
                val name = getFaceName(i)
                profiles.add(Pair(name, i))
            }
        }
        return profiles
    }

    /**
     * Check if any face is enrolled.
     */
    fun hasRegisteredFaces(): Boolean {
        return getEnrolledProfiles().isNotEmpty()
    }

    /**
     * Get the name of an enrolled face.
     */
    fun getFaceName(slotIndex: Int): String {
        return prefs.getString(PREF_FACE_NAME + slotIndex, "Unknown") ?: "Unknown"
    }

    /**
     * Check if a face slot is enrolled.
     */
    fun isFaceEnrolled(slotIndex: Int): Boolean {
        return prefs.getBoolean(PREF_FACE_ENROLLED + slotIndex, false)
    }

    /**
     * Delete a registered face.
     */
    fun deleteFace(slotIndex: Int) {
        prefs.edit()
            .remove(PREF_FACE_PREFIX + slotIndex)
            .remove(PREF_FACE_NAME + slotIndex)
            .putBoolean(PREF_FACE_ENROLLED + slotIndex, false)
            .apply()
        Log.i(TAG, "Face deleted: slot $slotIndex")
    }

    /**
     * Get next available slot.
     */
    private fun getNextAvailableSlot(): Int {
        for (i in 0 until NUM_FACE_SLOTS) {
            if (!isFaceEnrolled(i)) return i
        }
        return -1
    }

    /**
     * Generate a mock face signature (128-dim embedding placeholder).
     * In production, use ML Kit Face Net or similar model.
     */
    private fun generateSignature(): String {
        val random = java.util.Random()
        return buildString {
            for (i in 0 until 128) {
                if (i > 0) append(",")
                append(String.format("%.6f", random.nextFloat()))
            }
        }.also {
            Log.d(TAG, "Generated 128-dim signature")
        }
    }

    /**
     * Save face profile to persistent storage.
     */
    private fun saveFaceProfile(slotIndex: Int, name: String, signature: String) {
        prefs.edit()
            .putString(PREF_FACE_PREFIX + slotIndex, signature)
            .putString(PREF_FACE_NAME + slotIndex, name)
            .putBoolean(PREF_FACE_ENROLLED + slotIndex, true)
            .apply()
    }

    /**
     * Compare face signatures (cosine similarity).
     * Returns similarity score (0.0 to 1.0).
     */
    fun compareFaces(sig1: String, sig2: String): Float {
        try {
            val arr1 = sig1.split(",").map { it.trim().toFloat() }
            val arr2 = sig2.split(",").map { it.trim().toFloat() }
            val len = minOf(arr1.size, arr2.size)
            if (len == 0) return 0f

            var dotProduct = 0f
            var norm1 = 0f
            var norm2 = 0f
            for (i in 0 until len) {
                dotProduct += arr1[i] * arr2[i]
                norm1 += arr1[i] * arr1[i]
                norm2 += arr2[i] * arr2[i]
            }
            val magnitude = kotlin.math.sqrt(norm1) * kotlin.math.sqrt(norm2)
            return if (magnitude > 0) (dotProduct / magnitude).coerceIn(0f, 1f) else 0f
        } catch (e: Exception) {
            return 0f
        }
    }

    /**
     * Get Bangla status message for face recognition.
     */
    fun getStatusMessage(): String {
        val enrolled = getEnrolledProfiles()
        return if (enrolled.isEmpty()) {
            "কোনো চেহরা সংরক্ষিত নেই। 'চেহরা সংরক্ষণ করো' বলুন।"
        } else {
            "সংরক্ষিত চেহরা: ${enrolled.joinToString(", ") { it.first }}"
        }
    }
}
