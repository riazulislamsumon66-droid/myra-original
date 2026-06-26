package com.maya.assistant.websocket

import android.util.Base64
import android.util.Log
import com.maya.assistant.utils.Constants
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiWebSocketClient(
    private val apiKey: String,
    private val systemPrompt: String,
    private val onConnected: () -> Unit,
    private val onAudioReceived: (ByteArray) -> Unit,
    private val onTextReceived: (String) -> Unit,
    private val onTurnComplete: () -> Unit,
    private val onError: (String) -> Unit
) {
    private val TAG = "GEMINI_WS"
    private var webSocket: WebSocket? = null
    private var isSetupComplete = false
    private var currentModelIndex = 0
    private val modelQueue = listOf(Constants.GEMINI_MODEL) + Constants.GEMINI_FALLBACK_MODELS

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private val url = "${Constants.GEMINI_WS_BASE}?key=$apiKey"

    fun connect() {
        currentModelIndex = 0
        connectInternal()
    }

    private fun connectInternal() {
        if (currentModelIndex >= modelQueue.size) {
            Log.e(TAG, "All ${modelQueue.size} models exhausted — giving up")
            onError("সব Gemini মডেল connect করতে ব্যর্থ হলো")
            return
        }
        isSetupComplete = false
        val model = modelQueue[currentModelIndex]
        Log.d(TAG, "Connecting (model ${currentModelIndex + 1}/${modelQueue.size}: $model) to: ${url.take(80)}...")
        val request = Request.Builder()
            .url(url)
            .addHeader("Origin", "https://generativelanguage.googleapis.com")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket OPEN ✅ — sending setup message")
                sendSetup(ws, model)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleResponse(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                try { handleResponse(bytes.utf8()) } catch (e: Exception) { Log.e(TAG, "Binary parse error") }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isSetupComplete = false
                Log.e(TAG, "WS Failure (model #$currentModelIndex: $model): ${t.message}")
                currentModelIndex++
                if (currentModelIndex < modelQueue.size) {
                    Log.w(TAG, "Retrying with fallback model: ${modelQueue[currentModelIndex]}")
                    connectInternal()
                } else {
                    onError("Connection failed: ${t.message}")
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isSetupComplete = false
                Log.d(TAG, "WS Closed: $reason")
            }
        })
    }

    private fun sendSetup(ws: WebSocket, model: String) {
        val setup = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", model)
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", Constants.GEMINI_VOICE)
                            })
                        })
                    })
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                })
            })
        }
        ws.send(setup.toString())
        Log.d(TAG, "Setup sent ✅")
    }

    fun sendAudioChunk(pcm: ByteArray) {
        if (!isSetupComplete) {
            Log.w(TAG, "sendAudioChunk: DROPPED ${pcm.size} bytes — setup not complete yet")
            return
        }
        try {
            val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
            val msg = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("mediaChunks", JSONArray().put(JSONObject().apply {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", b64)
                    }))
                })
            }
            webSocket?.send(msg.toString())
            Log.v(TAG, "sendAudioChunk: sent ${pcm.size} bytes (base64 ${b64.length} chars)")
        } catch (e: Exception) {
            Log.e(TAG, "Audio send error: ${e.message}")
        }
    }

    fun sendTurnComplete() {
        if (!isSetupComplete) {
            Log.w(TAG, "sendTurnComplete: DROPPED — setup not complete")
            return
        }
        try {
            val msg = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray())
                    put("turnComplete", true)
                })
            }
            webSocket?.send(msg.toString())
            Log.d(TAG, "Turn complete sent ✅")
        } catch (e: Exception) {
            Log.e(TAG, "Turn complete error: ${e.message}")
        }
    }

    fun sendTextMessage(text: String) {
        if (!isSetupComplete) {
            Log.w(TAG, "sendTextMessage: DROPPED — setup not complete")
            return
        }
        try {
            val msg = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", text)))
                    }))
                    put("turnComplete", true)
                })
            }
            webSocket?.send(msg.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Text send error: ${e.message}")
        }
    }

    private fun handleResponse(json: String) {
        try {
            val obj = JSONObject(json)

            // Setup complete
            if (obj.has("setupComplete") || obj.optJSONObject("setupComplete") != null) {
                isSetupComplete = true
                Log.d(TAG, "Setup COMPLETE ✅ — Gemini ready for audio")
                onConnected()
                return
            }

            val serverContent = obj.optJSONObject("serverContent")
            if (serverContent == null) {
                Log.w(TAG, "Response without serverContent: ${json.take(100)}")
                return
            }

            val modelTurn = serverContent.optJSONObject("modelTurn")
            if (modelTurn != null) {
                val parts = modelTurn.optJSONArray("parts") ?: return
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    // Audio
                    if (part.has("inlineData")) {
                        val data = part.getJSONObject("inlineData").getString("data")
                        val bytes = Base64.decode(data, Base64.DEFAULT)
                        onAudioReceived(bytes)
                    }
                    // Text
                    if (part.has("text")) {
                        onTextReceived(part.getString("text"))
                    }
                }
            }

            if (serverContent.optBoolean("turnComplete", false)) {
                onTurnComplete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message} | ${json.take(200)}")
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "Bye")
        webSocket = null
        isSetupComplete = false
    }

    fun isConnected() = isSetupComplete
}
