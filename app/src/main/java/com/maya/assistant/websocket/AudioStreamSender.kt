package com.maya.assistant.websocket

/**
 * WebSocket-level audio stream sender
 */
class AudioStreamSender(private val client: GeminiWebSocketClient) {
    fun send(pcm: ByteArray) = client.sendAudioChunk(pcm)
}
