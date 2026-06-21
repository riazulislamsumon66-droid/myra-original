package com.maya.assistant.screenvision

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

object OCRProcessor {
    private val TAG = "OCR"
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun extractText(bitmap: Bitmap, onResult: (String) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val text = result.textBlocks.joinToString("\n") { it.text }
                Log.d(TAG, "OCR result: $text")
                onResult(text)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed: ${e.message}")
                onResult("")
            }
    }

    /** Suspend-friendly version for use in coroutines (blocking under the hood via CompletableFuture) */
    suspend fun extractTextSuspend(bitmap: Bitmap): String {
        val future = java.util.concurrent.CompletableFuture<String>()
        extractText(bitmap) { text -> future.complete(text) }
        return future.get()
    }
}
