package com.maya.assistant.voice

/**
 * Receives raw PCM audio chunks from Gemini and routes them to AudioPlayer.
 * Separates audio routing logic from WebSocket parsing logic.
 */
class AudioStreamReceiver(private val player: AudioPlayer) {

    fun receive(data: ByteArray) {
        if (data.isNotEmpty()) player.playChunk(data)
    }

    fun clear() = player.clearAndStop()
}
