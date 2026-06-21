package com.maya.assistant.voice

import com.maya.assistant.utils.AudioUtils
import com.maya.assistant.websocket.GeminiWebSocketClient

/**
 * Bridges AudioRecorder chunks → GeminiWebSocketClient
 */
class AudioStreamSender(private val client: GeminiWebSocketClient) {

    fun send(pcm: ByteArray) {
        if (VoiceStateManager.isAiSpeaking()) return
        client.sendAudioChunk(pcm)
    }
}
