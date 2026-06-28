package com.maya.assistant.websocket

import com.maya.assistant.utils.Logger

/**
 * Wraps GeminiWebSocketClient with automatic reconnect logic.
 *
 * If the WebSocket drops (network error, server timeout, etc.),
 * ConnectionManager retries up to MAX_RECONNECT times with a 3s
 * delay between attempts — so MAYA recovers silently without the
 * user needing to restart the app.
 */
class ConnectionManager(
    private val apiKey: String,
    private val systemPrompt: String,
    private val onConnected: () -> Unit,
    private val onAudioReceived: (ByteArray) -> Unit,
    private val onTextReceived: (String) -> Unit,
    private val onTurnComplete: () -> Unit,
    private val onError: (String) -> Unit
) {
    private val TAG = "CONN_MGR"
    private var client: GeminiWebSocketClient? = null
    private var reconnectAttempts = 0
    private val MAX_RECONNECT = 5

    fun connect() {
        reconnectAttempts = 0
        createClient()
    }

    private fun createClient() {
        client = GeminiWebSocketClient(
            apiKey = apiKey,
            systemPrompt = systemPrompt,
            onConnected = {
                reconnectAttempts = 0
                Logger.d(TAG, "Connected ✅ — reconnect counter reset")
                onConnected()
            },
            onAudioReceived = onAudioReceived,
            onTextReceived = onTextReceived,
            onTurnComplete = onTurnComplete,
            onError = { msg ->
                Logger.e(TAG, "Connection error: $msg | attempt=$reconnectAttempts/$MAX_RECONNECT")
                onError(msg)
                if (reconnectAttempts < MAX_RECONNECT) {
                    reconnectAttempts++
                    Logger.d(TAG, "Reconnecting in 3s... (attempt $reconnectAttempts)")
                    Thread.sleep(3000)
                    createClient()
                } else {
                    Logger.e(TAG, "Max reconnect attempts reached — giving up")
                }
            }
        )
        client?.connect()
    }

    fun sendAudioChunk(pcm: ByteArray) = client?.sendAudioChunk(pcm)

    fun sendTextMessage(text: String) = client?.sendTextMessage(text)

    fun sendTurnComplete() = client?.sendTurnComplete()

    fun disconnect() {
        client?.disconnect()
        client = null
        reconnectAttempts = 0
    }

    fun isConnected() = client?.isConnected() == true

    fun getClient(): GeminiWebSocketClient? = client
}
