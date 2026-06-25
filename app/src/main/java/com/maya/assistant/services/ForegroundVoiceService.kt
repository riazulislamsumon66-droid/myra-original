package com.maya.assistant.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.maya.assistant.R
import com.maya.assistant.ai.AIResponseManager
import com.maya.assistant.ai.ConversationMemory
import com.maya.assistant.ai.IntentAnalyzer
import com.maya.assistant.ai.DynamicDecisionEngine
import com.maya.assistant.ui.main.MainActivity
import com.maya.assistant.utils.Constants
import com.maya.assistant.utils.Logger
import com.maya.assistant.utils.prefs
import com.maya.assistant.voice.AudioFocusManager
import com.maya.assistant.voice.AudioPlayer
import com.maya.assistant.voice.AudioRecorder
import com.maya.assistant.voice.VoiceActivityDetector
import com.maya.assistant.voice.VoiceAuthManager
import com.maya.assistant.voice.VoiceStateManager
import com.maya.assistant.voice.WakeWordDetector
import com.maya.assistant.websocket.GeminiWebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ForegroundVoiceService : Service() {
    private val TAG = "VOICE_SVC"
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private lateinit var audioRecorder: AudioRecorder
    private lateinit var audioPlayer: AudioPlayer
    private lateinit var vad: VoiceActivityDetector
    private lateinit var audioFocus: AudioFocusManager
    private lateinit var voiceAuth: VoiceAuthManager
    private var wakeWordDetector: WakeWordDetector? = null
    private var geminiClient: GeminiWebSocketClient? = null

    // Mode: WAKE_WORD (listening for "Hey MAYA") or ACTIVE (recording user command)
    private var currentMode = Mode.WAKE_WORD

    enum class Mode { WAKE_WORD, ACTIVE }

    companion object {
        var instance: ForegroundVoiceService? = null
        var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        Logger.d(TAG, "=== SERVICE START ===")
        instance = this
        isRunning = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Constants.NOTIF_ID_VOICE,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(Constants.NOTIF_ID_VOICE, buildNotification())
        }
        initComponents()
    }

    private fun initComponents() {
        Logger.d(TAG, "Initializing components...")
        audioFocus = AudioFocusManager(this)
        audioPlayer = AudioPlayer()
        voiceAuth = VoiceAuthManager(this)

        audioPlayer.onPlaybackStarted = {
            Logger.d(TAG, "Playback STARTED — AI speaking")
            VoiceStateManager.setSpeaking()
        }
        audioPlayer.onPlaybackFinished = {
            Logger.d(TAG, "Playback FINISHED — returning to listen")
            VoiceStateManager.setListening()
        }

        vad = VoiceActivityDetector(
            onSpeechStart = {
                Logger.d(TAG, "VAD: Speech STARTED")
                VoiceStateManager.setListening()
            },
            onSpeechEnd = {
                Logger.d(TAG, "VAD: Speech ENDED — sending turnComplete")
                VoiceStateManager.setThinking()
                audioRecorder.stop()
                geminiClient?.sendTurnComplete()
            }
        )

        audioRecorder = AudioRecorder(this) { chunk ->
            if (!VoiceStateManager.isAiSpeaking()) {
                vad.processChunk(chunk)
                geminiClient?.sendAudioChunk(chunk)
            }
        }

        // Start wake word detection
        startWakeWordDetection()

        val apiKey = prefs().getString(Constants.KEY_API_KEY, "") ?: ""
        if (apiKey.isNotEmpty()) {
            Logger.d(TAG, "API key found, connecting to Gemini...")
            connectGemini(apiKey)
        } else {
            Logger.w(TAG, "API KEY EMPTY — voice service running but no AI. Set API key in Settings.")
        }
        Logger.d(TAG, "Components initialized. Mode: WAKE_WORD, waiting for 'Hey MAYA'...")
    }

    private fun startWakeWordDetection() {
        wakeWordDetector = WakeWordDetector(this) {
            // Wake word detected — switch to active mode and authenticate
            onWakeWordDetected()
        }
        wakeWordDetector?.start()
        currentMode = Mode.WAKE_WORD
        VoiceStateManager.setIdle()
    }

    private fun onWakeWordDetected() {
        Logger.d(TAG, "🎤 WAKE WORD DETECTED! Switching to ACTIVE mode...")
        wakeWordDetector?.stop()
        currentMode = Mode.ACTIVE

        // Authenticate voice
        Logger.d(TAG, "Starting voice authentication...")
        voiceAuth.authenticateVoice { isAuthorized, speakerName ->
            Logger.d(TAG, "Voice auth result: speaker=$speakerName, authorized=$isAuthorized")

            // Start recording for command
            vad.reset()
            audioRecorder.start()
            VoiceStateManager.setListening()
            Logger.d(TAG, "Recording started — waiting for command (10s timeout)")

            // Broadcast to UI
            val authStatus = if (isAuthorized) "✅ $speakerName recognized — commands enabled" else "⚠️ Unknown voice — conversation only"
            sendBroadcast(Intent("MAYA_RESPONSE").putExtra("text", authStatus))

            // Auto-stop after 10 seconds if no speech
            scope.launch {
                kotlinx.coroutines.delay(10000)
                if (currentMode == Mode.ACTIVE && audioRecorder.isActive()) {
                    Logger.w(TAG, "10s timeout — no speech detected, returning to wake word mode")
                    audioRecorder.stop()
                    geminiClient?.sendTurnComplete()
                    returnToWakeWordMode()
                }
            }
        }
    }

    private fun returnToWakeWordMode() {
        currentMode = Mode.WAKE_WORD
        VoiceStateManager.setIdle()
        startWakeWordDetection()
    }

    private fun connectGemini(apiKey: String) {
        geminiClient = GeminiWebSocketClient(
            apiKey = apiKey,
            systemPrompt = buildSystemPrompt(),
            onConnected = {
                VoiceStateManager.setIdle()
            },
            onAudioReceived = { data ->
                audioPlayer.playChunk(data)
            },
            onTextReceived = { text ->
                val clean = AIResponseManager.clean(text)
                if (clean.isNotBlank()) {
                    ConversationMemory.addAssistant(clean)

                    // Only execute commands if voice is authorized
                    val cmd = AIResponseManager.extractCommand(clean)
                    if (cmd != null) {
                        if (voiceAuth.isAuthorizedForCommands()) {
                            scope.launch {
                                val intent = IntentAnalyzer.analyze(cmd)
                                DynamicDecisionEngine.execute(this@ForegroundVoiceService, intent)
                            }
                        } else {
                            Logger.d(TAG, "Command blocked — voice not authorized: $cmd")
                            sendBroadcast(Intent("MAYA_RESPONSE").putExtra("text", "⚠️ Voice not recognized — device commands disabled. Only conversation mode."))
                        }
                    }

                    // Broadcast to UI
                    sendBroadcast(Intent("MAYA_RESPONSE").putExtra("text", clean))
                }
            },
            onTurnComplete = {
                if (!audioRecorder.isActive()) audioRecorder.start()
                // After response, return to wake word mode
                scope.launch {
                    kotlinx.coroutines.delay(2000)
                    if (currentMode == Mode.ACTIVE) {
                        returnToWakeWordMode()
                    }
                }
            },
            onError = { msg ->
                Logger.e(TAG, "Gemini error: $msg")
                VoiceStateManager.setError("Reconnecting...")
                scope.launch {
                    kotlinx.coroutines.delay(3000)
                    geminiClient?.connect()
                }
            }
        )
        geminiClient?.connect()
    }

    fun sendTextToGemini(text: String) {
        Logger.d(TAG, "sendTextToGemini: '$text'")
        ConversationMemory.addUser(text)
        VoiceStateManager.setThinking()
        geminiClient?.sendTextMessage(text)
    }

    fun reconnectGemini() {
        Logger.d(TAG, "reconnectGemini called")
        val apiKey = prefs().getString(Constants.KEY_API_KEY, "") ?: ""
        if (apiKey.isNotEmpty()) {
            geminiClient?.disconnect()
            connectGemini(apiKey)
        } else {
            Logger.w(TAG, "reconnectGemini: API key empty, cannot reconnect")
        }
    }

    /**
     * Mic button tap — conversation mode only (no device commands).
     */
    fun toggleRecording(): Boolean {
        Logger.d(TAG, "toggleRecording called — currentMode=$currentMode, isActive=${if (::audioRecorder.isInitialized) audioRecorder.isActive() else "N/A"}")
        return if (audioRecorder.isActive()) {
            Logger.d(TAG, "toggleRecording: STOP recording → send turnComplete → IDLE")
            audioRecorder.stop()
            geminiClient?.sendTurnComplete()
            VoiceStateManager.setIdle()
            false
        } else {
            // Ensure WebSocket is connected
            if (geminiClient == null || !geminiClient!!.isConnected()) {
                val apiKey = prefs().getString(Constants.KEY_API_KEY, "") ?: ""
                if (apiKey.isNotEmpty()) {
                    Logger.d(TAG, "toggleRecording: WebSocket not connected, connecting...")
                    connectGemini(apiKey)
                } else {
                    Logger.w(TAG, "toggleRecording: No API key, cannot connect")
                }
            }
            // Stop wake word detection during button recording
            wakeWordDetector?.stop()
            currentMode = Mode.ACTIVE
            vad.reset()
            audioRecorder.start()
            VoiceStateManager.setListening()
            Logger.d(TAG, "toggleRecording: START recording — conversation mode (no device commands)")
            true
        }
    }

    private fun buildSystemPrompt(): String {
        val userName = prefs().getString(Constants.KEY_USER_NAME, "Boss") ?: "Boss"
        val personality = prefs().getString(Constants.KEY_PERSONALITY, "friendly") ?: "friendly"
        return """
YOU ARE MAYA - My Yours Responsive Assistant.
User's name is $userName.
Personality: $personality.

STRICT RULES:
- Never explain or think aloud
- Never say: "Responding to", "I've registered", "Formulating", "Interpreting", "Processing"
- For device actions return ONLY the command:
  OPEN_APP <name> | CALL <name> | WHATSAPP_CALL <name>
  WHATSAPP_MSG <name> <message> | YOUTUBE_PLAY <query>
  SPOTIFY_PLAY <query> | FLASHLIGHT_ON | FLASHLIGHT_OFF
  VOLUME_UP | VOLUME_DOWN | SMS <name> <message>
- For conversation: Reply short and natural in Banglish
- Address user as $userName
- Be warm, witty, and human-like
        """.trimIndent()
    }

    private fun buildNotification(): Notification {
        createChannel()
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Constants.NOTIF_CHANNEL_VOICE)
            .setContentTitle("MAYA is listening ❤️")
            .setContentText("Say 'Hey MAYA' or tap mic button")
            .setSmallIcon(R.drawable.ic_myra_notif)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                Constants.NOTIF_CHANNEL_VOICE,
                "MAYA Voice Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.w(TAG, "⚠️ APP SWIPED FROM RECENTS — stopping recording")
        if (audioRecorder.isActive()) {
            audioRecorder.stop()
            geminiClient?.sendTurnComplete()
        }
        wakeWordDetector?.stop()
        VoiceStateManager.setIdle()
        geminiClient?.disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        Logger.d(TAG, "=== SERVICE DESTROY ===")
        isRunning = false
        instance = null
        wakeWordDetector?.stop()
        audioRecorder.stop()
        audioPlayer.release()
        geminiClient?.disconnect()
        job.cancel()
        super.onDestroy()
    }
}
