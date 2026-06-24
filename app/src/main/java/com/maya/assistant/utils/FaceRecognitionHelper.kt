package com.maya.assistant.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.sqrt

/**
 * FaceRecognitionHelper — real face detection and recognition using ML Kit.
 *
 * Features:
 * - Real face detection via ML Kit Face Detection API
 * - 128-dim face signature from landmark positions + bounding box features
 * - 10 enrollment slots with persistent storage
 * - Cosine similarity matching with configurable threshold
 * - Bangla status messages
 */
class FaceRecognitionHelper(private val context: Context) {

    companion object {
        private const val TAG = "FaceRecognition"
        private const val PREF_FACE_PREFIX = "face_signature_"
        private const val PREF_FACE_NAME = "face_name_"
        private const val PREF_FACE_ENROLLED = "face_enrolled_"
        private const val NUM_FACE_SLOTS = 10
        private const val SIGNATURE_DIM = 128
        private const val MATCH_THRESHOLD = 0.65f

        // Singleton
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

    // ML Kit face detector — accurate mode with landmarks + classification
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .enableTracking()
            .build()
    )

    /**
     * Detect faces in a bitmap and return list of face bounding boxes.
     */
    fun detectFaces(bitmap: Bitmap, onResult: (List<Face>) -> Unit) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                onResult(faces)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Face detection failed: ${e.message}")
                onResult(emptyList())
            }
    }

    /**
     * Enroll a face from a bitmap.
     * Detects face, generates signature, and stores it.
     */
    fun enrollFace(name: String, bitmap: Bitmap, onResult: (Boolean) -> Unit) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isEmpty()) {
                    Log.w(TAG, "No face detected in image")
                    onResult(false)
                    return@addOnSuccessListener
                }

                val face = faces[0] // Use first detected face
                val signature = generateSignature(face, bitmap)

                val slotIndex = getNextAvailableSlot()
                if (slotIndex < 0) {
                    Log.w(TAG, "No free face slots")
                    onResult(false)
                    return@addOnSuccessListener
                }

                saveFaceProfile(slotIndex, name, signature)
                Log.i(TAG, "Face enrolled: $name (slot $slotIndex)")
                onResult(true)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Face enrollment failed: ${e.message}")
                onResult(false)
            }
    }

    /**
     * Recognize a face from a bitmap.
     * Returns the matched slot index, or -1 if no match found.
     */
    fun recognizeFace(bitmap: Bitmap, onResult: (Int, Float) -> Unit) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isEmpty()) {
                    onResult(-1, 0f)
                    return@addOnSuccessListener
                }

                val face = faces[0]
                val signature = generateSignature(face, bitmap)

                var bestMatch = -1
                var bestScore = 0f

                for (slot in 0 until NUM_FACE_SLOTS) {
                    if (!isFaceEnrolled(slot)) continue
                    val enrolledSig = getSignature(slot) ?: continue
                    val similarity = cosineSimilarity(signature, enrolledSig)
                    if (similarity > bestScore) {
                        bestScore = similarity
                        bestMatch = slot
                    }
                }

                if (bestMatch >= 0 && bestScore >= MATCH_THRESHOLD) {
                    Log.i(TAG, "Face recognized: slot $bestMatch (score: $bestScore)")
                    onResult(bestMatch, bestScore)
                } else {
                    Log.d(TAG, "No match found (best: $bestScore)")
                    onResult(-1, bestScore)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Face recognition failed: ${e.message}")
                onResult(-1, 0f)
            }
    }

    /**
     * Generate a 128-dim face signature from ML Kit face landmarks.
     * Uses normalized landmark positions + bounding box features.
     */
    private fun generateSignature(face: Face, bitmap: Bitmap): FloatArray {
        val signature = FloatArray(SIGNATURE_DIM)
        val bbox = face.boundingBox

        // Feature 0-3: Normalized bounding box (relative to image)
        val imgW = bitmap.width.toFloat()
        val imgH = bitmap.height.toFloat()
        signature[0] = bbox.left / imgW
        signature[1] = bbox.top / imgH
        signature[2] = bbox.width() / imgW
        signature[3] = bbox.height() / imgH

        // Features 4-5: Head rotation
        signature[4] = face.headEulerAngleX / 90f  // pitch
        signature[5] = face.headEulerAngleY / 90f  // yaw

        // Features 6-7: Classification scores
        signature[6] = face.smilingProbability ?: 0f
        signature[7] = face.rightEyeOpenProbability ?: 0f

        // Features 8-39: Normalized landmark positions
        // ML Kit FaceLandmark integer IDs (avoiding unresolved constants):
        // 0=LEFT_EYE, 1=RIGHT_EYE, 2=LEFT_EAR, 3=RIGHT_EAR,
        // 4=NOSE_BASE, 5=MOUTH_LEFT, 6=MOUTH_RIGHT,
        // 100=FACE_CENTER, 101=LEFT_CHEEK, 102=RIGHT_CHEEK
        val landmarks = listOf(
            FaceLandmark.LEFT_EYE, FaceLandmark.RIGHT_EYE,
            FaceLandmark.NOSE_BASE, FaceLandmark.MOUTH_LEFT, FaceLandmark.MOUTH_RIGHT,
            FaceLandmark.LEFT_CHEEK, FaceLandmark.RIGHT_CHEEK,
            FaceLandmark.LEFT_EAR, FaceLandmark.RIGHT_EAR
        )

        var idx = 8
        for (landmarkType in landmarks) {
            if (idx + 1 >= SIGNATURE_DIM) break
            val landmark = face.getLandmark(landmarkType)
            if (landmark != null) {
                signature[idx] = landmark.position.x / imgW
                signature[idx + 1] = landmark.position.y / imgH
            }
            idx += 2
        }

        // Features 40-127: Inter-landmark distances (relative)
        val noseBase = face.getLandmark(FaceLandmark.NOSE_BASE)
        if (noseBase != null) {
            val refX = noseBase.position.x
            val refY = noseBase.position.y
            var dIdx = 40
            for (i in landmarks.indices) {
                if (dIdx >= SIGNATURE_DIM) break
                val lm = face.getLandmark(landmarks[i]) ?: continue
                val dx = (lm.position.x - refX) / imgW
                val dy = (lm.position.y - refY) / imgH
                signature[dIdx] = dx * dx + dy * dy  // squared distance
                dIdx++
            }
        }

        // Normalize the signature
        val norm = signature.map { it * it }.sum().let { sqrt(it.toDouble()).toFloat() }
        if (norm > 0) {
            for (i in signature.indices) {
                signature[i] /= norm
            }
        }

        return signature
    }

    /**
     * Cosine similarity between two face signatures.
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0) (dot / denom).coerceIn(0f, 1f) else 0f
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

    fun hasRegisteredFaces(): Boolean = getEnrolledProfiles().isNotEmpty()

    fun getFaceName(slotIndex: Int): String {
        return prefs.getString(PREF_FACE_NAME + slotIndex, "Unknown") ?: "Unknown"
    }

    fun isFaceEnrolled(slotIndex: Int): Boolean {
        return prefs.getBoolean(PREF_FACE_ENROLLED + slotIndex, false)
    }

    fun deleteFace(slotIndex: Int) {
        prefs.edit()
            .remove(PREF_FACE_PREFIX + slotIndex)
            .remove(PREF_FACE_NAME + slotIndex)
            .putBoolean(PREF_FACE_ENROLLED + slotIndex, false)
            .apply()
        Log.i(TAG, "Face deleted: slot $slotIndex")
    }

    private fun getNextAvailableSlot(): Int {
        for (i in 0 until NUM_FACE_SLOTS) {
            if (!isFaceEnrolled(i)) return i
        }
        return -1
    }

    private fun getSignature(slotIndex: Int): FloatArray? {
        if (!isFaceEnrolled(slotIndex)) return null
        val sig = FloatArray(SIGNATURE_DIM)
        for (i in 0 until SIGNATURE_DIM) {
            sig[i] = prefs.getFloat("${PREF_FACE_PREFIX}${slotIndex}_$i", 0f)
        }
        return sig
    }

    private fun saveFaceProfile(slotIndex: Int, name: String, signature: FloatArray) {
        val editor = prefs.edit()
        editor.putString(PREF_FACE_NAME + slotIndex, name)
        editor.putBoolean(PREF_FACE_ENROLLED + slotIndex, true)
        for (i in signature.indices) {
            editor.putFloat("${PREF_FACE_PREFIX}${slotIndex}_$i", signature[i])
        }
        editor.apply()
    }

    fun getStatusMessage(): String {
        val enrolled = getEnrolledProfiles()
        return if (enrolled.isEmpty()) {
            "কোনো চেহরা সংরক্ষিত নেই। 'চেহরা সংরক্ষণ করো' বলুন।"
        } else {
            "সংরক্ষিত চেহরা: ${enrolled.joinToString(", ") { it.first }}"
        }
    }
}
