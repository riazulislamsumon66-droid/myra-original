package com.maya.assistant.ai

import android.util.Log
import com.maya.assistant.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * GeminiTextClient — HTTP-based text API (reliable fallback).
 * Uses Gemini REST API for text responses when WebSocket audio fails.
 */
class GeminiTextClient(
    private val apiKey: String,
    private val systemPrompt: String
) {
    companion object {
        private const val TAG = "GEMINI_TEXT"
        private const val API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    interface TextCallback {
        fun onResponse(text: String)
        fun onError(error: String)
    }

    fun sendMessage(userText: String, callback: TextCallback) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = buildRequestJson(userText)
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$API_URL?key=$apiKey")
                    .post(body)
                    .build()

                Log.d(TAG, "Sending text to Gemini...")
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    val text = parseResponse(responseBody)
                    if (text != null) {
                        Log.d(TAG, "Response: ${text.take(100)}")
                        callback.onResponse(text)
                    } else {
                        callback.onError("Empty response from Gemini")
                    }
                } else {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Log.e(TAG, "API error ${response.code}: $errorBody")
                    callback.onError("API error: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Request failed: ${e.message}")
                callback.onError("Network error: ${e.message}")
            }
        }
    }

    private fun buildRequestJson(userText: String): JSONObject {
        return JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", userText
                }))
            }))
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", systemPrompt
                }))
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.7)
                put("maxOutputTokens", 200)
                put("responseMimeType", "text/plain")
            })
        }
    }

    private fun parseResponse(jsonString: String): String? {
        return try {
            val json = JSONObject(jsonString)
            val candidates = json.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val content = candidates.getJSONObject(0).optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("text")) {
                    sb.append(part.getString("text"))
                }
            }
            val result = sb.toString().trim()
            if (result.isEmpty()) null else result
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
            null
        }
    }
}
