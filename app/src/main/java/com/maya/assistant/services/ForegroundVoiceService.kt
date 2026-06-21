package com.maya.assistant.services

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.maya.assistant.R
import com.maya.assistant.ai.AIResponseManager
import com.maya.assistant.ai.ConversationMemory
import com.maya.assistant.ai.IntentAnalyzer
import com.maya.assistant.ai.DynamicDecisionEngine
import com.maya.assistant.models.CommandType
import com.maya.assistant.ui.main.MainActivity
import com.maya.assistant.service.PowerButtonReceiver
import com.maya.assistant.utils.Constants
import com.maya.assistant.utils.Logger
import com.maya.assistant.utils.prefs
import com.maya.assistant.voice.AudioFocusManager
import com.maya.assistant.voice.AudioPlayer
import com.maya.assistant.voice.AudioRecorder
import com.maya.assistant.voice.VoiceActivityDetector
import com.maya.assistant.voice.VoiceStateManager
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
    private var geminiClient: GeminiWebSocketClient? = null

    companion object {
        var instance: ForegroundVoiceService? = null
        var isRunning = false
    }

    private var powerButtonReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        startForeground(Constants.NOTIF_ID_VOICE, buildNotification())
        initComponents()
        registerPowerButtonReceiver()
    }

    private fun registerPowerButtonReceiver() {
        powerButtonReceiver = PowerButtonReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(powerButtonReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(powerButtonReceiver, filter)
        }
        Logger.d(TAG, "PowerButtonReceiver registered dynamically")
    }

    private fun initComponents() {
        audioFocus = AudioFocusManager(this)
        audioPlayer = AudioPlayer()

        audioPlayer.onPlaybackStarted = {
            VoiceStateManager.setSpeaking()
        }
        audioPlayer.onPlaybackFinished = {
            VoiceStateManager.setListening()
        }

        vad = VoiceActivityDetector(
            onSpeechStart = {
                VoiceStateManager.setListening()
            },
            onSpeechEnd = {
                VoiceStateManager.setThinking()
            }
        )

        audioRecorder = AudioRecorder(this) { chunk ->
            // Don't send audio while AI is speaking
            if (!VoiceStateManager.isAiSpeaking()) {
                vad.processChunk(chunk)
                geminiClient?.sendAudioChunk(chunk)
            }
        }

        val apiKey = prefs().getString(Constants.KEY_API_KEY, "") ?: ""
        if (apiKey.isNotEmpty()) {
            connectGemini(apiKey)
        }
    }

    private fun connectGemini(apiKey: String) {
        geminiClient = GeminiWebSocketClient(
            apiKey = apiKey,
            systemPrompt = buildSystemPrompt(),
            onConnected = {
                VoiceStateManager.setListening()
                audioRecorder.start()
            },
            onAudioReceived = { data ->
                audioPlayer.playChunk(data)
            },
            onTextReceived = { text ->
                val clean = AIResponseManager.clean(text)
                // Skip if response was only thinking text or is empty after cleaning
                if (clean.isNotBlank() && !AIResponseManager.hasThinkingText(text)) {
                    ConversationMemory.addAssistant(clean)
                    // Try structured command first, then natural language
                    val cmd = AIResponseManager.extractCommand(clean)
                    val intent = if (cmd != null) {
                        IntentAnalyzer.analyze(cmd)
                    } else {
                        // Natural language fallback - analyze directly
                        IntentAnalyzer.analyze(clean)
                    }
                    if (intent.type != CommandType.CONVERSATION && intent.type != CommandType.UNKNOWN) {
                        // Check accessibility before executing
                        if (com.maya.assistant.service.SmartAccessibilityEngine.service == null) {
                            sendBroadcast(Intent("MAYA_RESPONSE").putExtra("text", "⚠️ Accessibility Service বন্ধ আছে। Settings → Accessibility → MAYA → ON করো।"))
                        }
                        scope.launch {
                            val result = DynamicDecisionEngine.execute(this@ForegroundVoiceService, intent)
                            // Broadcast result to UI
                            sendBroadcast(Intent("MAYA_RESPONSE").apply {
                                putExtra("text", result)
                                putExtra("is_result", true)
                            })
                        }
                    }
                    // Broadcast to UI
                    sendBroadcast(Intent("MAYA_RESPONSE").putExtra("text", clean))
                } else if (AIResponseManager.hasThinkingText(text) && clean.isBlank()) {
                    // Gemini returned only thinking text — ask it to respond properly
                    Logger.d(TAG, "Gemini returned thinking text only, skipping")
                }
            },
            onTurnComplete = {
                // Resume listening after AI finishes
                if (!audioRecorder.isActive()) audioRecorder.start()
            },
            onError = { msg ->
                Logger.e(TAG, "Gemini error: $msg")
                VoiceStateManager.setError("Reconnecting...")
                // Auto-reconnect after delay
                scope.launch {
                    kotlinx.coroutines.delay(3000)
                    geminiClient?.connect()
                }
            }
        )
        geminiClient?.connect()
    }

    fun sendTextToGemini(text: String) {
        ConversationMemory.addUser(text)
        VoiceStateManager.setThinking()
        geminiClient?.sendTextMessage(text)
    }

    fun reconnectGemini() {
        val apiKey = prefs().getString(Constants.KEY_API_KEY, "") ?: ""
        if (apiKey.isNotEmpty()) {
            geminiClient?.disconnect()
            connectGemini(apiKey)
        }
    }

    private fun buildSystemPrompt(): String {
        val userName = prefs().getString(Constants.KEY_USER_NAME, "Boss") ?: "Boss"
        val personality = prefs().getString(Constants.KEY_PERSONALITY, "friendly") ?: "friendly"
        val language = prefs().getString(Constants.KEY_LANGUAGE, "bangla") ?: "bangla"
        return """
YOU ARE MAYA - My Yours Responsive Assistant.
User's name is $userName.
Personality: $personality.
Language: Reply in $language (Bangla/English/Hindi/Arabic/French based on user preference).

CRITICAL RULES:
- NEVER think aloud or explain your reasoning
- NEVER say: "Responding to", "I've registered", "Formulating", "Interpreting", "Processing", "I'm crafting", "I'm considering", "I'm thinking", "Let me think", "I need to", "I should", "I will", "The focus is on", "The only output needed", "different search modifiers", "optimize the search", "precise and will retrieve", "ensuring the command is precise"
- NEVER include internal reasoning in your response
- For device actions return ONLY the command (nothing else):
  OPEN_APP <name> | CLOSE_APP <name> | CALL <name> | WHATSAPP_CALL <name>
  WHATSAPP_MSG <name> <message> | YOUTUBE_PLAY <query>
  SPOTIFY_PLAY <query> | FLASHLIGHT_ON | FLASHLIGHT_OFF
  VOLUME_UP | VOLUME_DOWN | SCREENSHOT | SCROLL_UP | SCROLL_DOWN
  BACK | HOME | NOTIFICATION | SMS <name> <message>
  CLICK <text> | SEARCH <query> | TYPE_TEXT <text>
- For conversation: Reply short and natural in user's preferred language
- Address user as $userName
- Be warm, witty, and human-like
- Understand commands in Bangla, English, Hindi, Arabic, and French
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
            .setContentText("Always ready for you")
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

    override fun onDestroy() {
        isRunning = false
        instance = null
        audioRecorder.stop()
        audioPlayer.release()
        geminiClient?.disconnect()
        // Unregister power button receiver
        powerButtonReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        powerButtonReceiver = null
        job.cancel()
        super.onDestroy()
    }
}
