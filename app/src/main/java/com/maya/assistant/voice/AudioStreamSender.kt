package com.maya.assistant.voice

import com.maya.assistant.websocket.GeminiWebSocketClient

/**
 * Bridges AudioRecorder PCM chunks → GeminiWebSocketClient.
 * Skips sending while MAYA is speaking (prevents echo feedback loop).
 */
class AudioStreamSender(private val client: GeminiWebSocketClient) {

    fun send(pcm: ByteArray) {
        if (VoiceStateManager.isAiSpeaking()) return
        client.sendAudioChunk(pcm)
    }
}
