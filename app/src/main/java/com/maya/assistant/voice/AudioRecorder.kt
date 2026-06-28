package com.maya.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.maya.assistant.utils.AudioUtils
import com.maya.assistant.utils.Logger

class AudioRecorder(
    private val context: Context,
    private val onChunk: (ByteArray) -> Unit
) {
    private val TAG = "RECORDER"
    private val SAMPLE_RATE = 16000
    private val CHUNK_SIZE = 1024  // ~64ms at 16kHz

    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    @Volatile private var isRecording = false

    @Synchronized
    fun start() {
        if (isRecording) {
            Logger.d(TAG, "start() called but already recording — ignored (prevents mic flicker)")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Logger.e(TAG, "RECORD_AUDIO permission not granted")
            return
        }

        // Release any stale AudioRecord before creating a new one.
        // This prevents "mic already in use" errors after an unclean stop.
        audioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
            audioRecord = null
        }

        val bufferSize = AudioUtils.getMinBufferSize(SAMPLE_RATE)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord!!.state != AudioRecord.STATE_INITIALIZED) {
                Logger.e(TAG, "AudioRecord failed to initialize — mic may be in use by another app")
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord!!.startRecording()
            isRecording = true

            recordThread = Thread {
                val buffer = ByteArray(CHUNK_SIZE)
                while (isRecording && !Thread.interrupted()) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0 && !VoiceStateManager.isAiSpeaking()) {
                        onChunk(buffer.copyOf(read))
                    }
                }
            }.also {
                it.name = "MAYA_RecordThread"
                it.isDaemon = true
                it.start()
            }

            Logger.d(TAG, "Recording started")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to start recording: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        if (!isRecording) {
            Logger.d(TAG, "stop() called but not recording — ignored")
            return
        }
        isRecording = false
        recordThread?.interrupt()
        recordThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Logger.e(TAG, "Stop error: \${e.message}")
        }
        Logger.d(TAG, "Recording stopped cleanly")
    }

    fun isActive() = isRecording
}
