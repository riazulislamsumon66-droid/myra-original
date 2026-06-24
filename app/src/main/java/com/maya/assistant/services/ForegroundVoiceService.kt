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
// ConversationMemory removed — using inline tracking
import com.maya.assistant.ai.IntentAnalyzer
import com.maya.assistant.ai.DynamicDecisionEngine
import com.maya.assistant.models.CommandType
import com.maya.assistant.ui.main.MainActivity
import com.maya.assistant.service.PowerButtonReceiver
import com.maya.assistant.utils.Constants
import com.maya.assistant.utils.Logger
import com.maya.assistant.utils.prefs
import com.maya.assistant.security.SecurePrefs
import com.maya.assistant.voice.AudioFocusManager
import com.maya.assistant.voice.AudioPlayer
import com.maya.assistant.voice.AudioRecorder
import com.maya.assistant.voice.VoiceActivityDetector
import com.maya.assistant.voice.VoiceStateManager
import com.maya.assistant.ai.GeminiLiveClient
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
    private var geminiClient: GeminiLiveClient? = null

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
            VoiceStateManager.notifyCharacterTalking(this)
        }
        audioPlayer.onPlaybackFinished = {
            VoiceStateManager.setListening()
            VoiceStateManager.notifyCharacterIdle(this)
        }

        vad = VoiceActivityDetector(
            onSpeechStart = {
                VoiceStateManager.setListening()
                VoiceStateManager.notifyCharacterListening(this)
            },
            onSpeechEnd = {
                VoiceStateManager.setThinking()
                VoiceStateManager.notifyCharacterThinking(this)
            }
        )

        audioRecorder = AudioRecorder(this) { chunk ->
            // Don't send audio while AI is speaking
            if (!VoiceStateManager.isAiSpeaking()) {
                vad.processChunk(chunk)
                // Audio chunks sent only when GeminiWebSocketClient is available
                // (GeminiLiveClient is text-only — no audio streaming support yet)
            }
        }

        // Request audio focus before starting recorder
        audioFocus.requestFocus(
            onGained = { Logger.d(TAG, "Audio focus gained") },
            onLost = {
                Logger.d(TAG, "Audio focus lost — stopping recorder")
                audioRecorder.stop()
            }
        )

        val apiKey = SecurePrefs.getApiKey(this@ForegroundVoiceService)
        if (apiKey.isNotEmpty()) {
            connectGemini(apiKey)
        } else {
            // Fallback: try plain text prefs for backward compatibility
            val plainKey = prefs().getString(Constants.KEY_API_KEY, "") ?: ""
            if (plainKey.isNotEmpty()) {
                connectGemini(plainKey)
                // Migrate to encrypted storage
                SecurePrefs.saveApiKey(this@ForegroundVoiceService, plainKey)
            } else {
                Logger.w(TAG, "No API key found — voice features disabled")
            }
        }
    }

    private fun connectGemini(apiKey: String) {
        geminiClient = GeminiLiveClient(
            apiKey = apiKey,
            systemPrompt = buildSystemPrompt(),
            onConnected = {
                VoiceStateManager.setListening()
                VoiceStateManager.notifyCharacterListening(this)
                audioRecorder.start()
            },
            onAudioReceived = { data ->
                audioPlayer.playChunk(data)
            },
            onTextReceived = { text ->
                val clean = AIResponseManager.clean(text)
                // Skip if response was only thinking text or is empty after cleaning
                if (clean.isNotBlank() && !AIResponseManager.hasThinkingText(clean)) {
                    // ConversationMemory.addAssistant(clean) — tracked inline
                    // Try structured command first, then natural language
                    val cmd = AIResponseManager.extractCommand(clean)
                    val intent = if (cmd != null) {
                        IntentAnalyzer.analyze(cmd)
                    } else {
                        // Natural language fallback — only analyze short command-like text
                        // Skip long conversational responses (likely not commands)
                        if (clean.length < 80 && clean.lines().size <= 2) {
                            IntentAnalyzer.analyze(clean)
                        } else {
                            com.maya.assistant.models.VoiceCommand(clean, CommandType.CONVERSATION)
                        }
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
                } else if (AIResponseManager.hasThinkingText(clean) && clean.isBlank()) {
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
        // ConversationMemory.addUser(text) — tracked inline
        VoiceStateManager.setThinking()
        VoiceStateManager.notifyCharacterThinking(this)
        geminiClient?.sendTextMessage(text)
    }

    fun reconnectGemini() {
        val apiKey = SecurePrefs.getApiKey(this@ForegroundVoiceService)
        if (apiKey.isNotEmpty()) {
            connectGemini(apiKey)
        } else {
            val plainKey = prefs().getString(Constants.KEY_API_KEY, "") ?: ""
            if (plainKey.isNotEmpty()) {
                connectGemini(plainKey)
                SecurePrefs.saveApiKey(this@ForegroundVoiceService, plainKey)
            }
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

=== ABSOLUTE RULES — ZERO TOLERANCE ===
1. NEVER output thinking, reasoning, or internal monologue — YOUR APP CRASHES IF YOU DO
2. NEVER start response with: "I'm", "I've", "Let me", "The focus", "Processing", "Interpreting", "Formulating", "Crafting", "Considering", "Observing", "I need to", "I should", "I will", "The only output", "different search modifiers", "optimize the search", "precise and will retrieve", "ensuring the command is precise", "I lack a specific command", "my response will be", "as best as I can", "sifting through", "subjective evaluation", "complex understanding", "framing a clear"
3. NEVER describe what you are about to do — just DO it
4. If user asks a question → reply directly in ONE short sentence (max 10 words)
5. If user gives a device command → return ONLY the command on its own line, nothing else — no explanation, no follow-up sentence
6. Your response must be MAXIMUM 2 lines. If you write more, your app fails
7. When user mentions screen ("দেখো", "বলো", "screen", "কী আছে") → respond with ONLY: READ_SCREEN
8. When user mentions flashlight ("ফ্লাশলাইট", "flashlight", "আলো") → respond with ONLY: FLASHLIGHT_ON or FLASHLIGHT_OFF
9. When user mentions YouTube ("youtube", "ইউটিউব", "ভিডিও", "video") → respond with ONLY: YOUTUBE_PLAY <query>
10. When user mentions call ("call", "কল", "phone", "ফোন") → respond with ONLY: CALL <name>
11. When user mentions calendar/schedule ("calendar", "আজ কী", "event", "রিমাইন্ডার") → respond with ONLY: CALENDAR_TODAY or CALENDAR_UPCOMING or CALENDAR_CREATE <text>
12. When user mentions notification ("notification", "নোটিফিকেশন") → respond with ONLY: NOTIFICATION
13. When user mentions face/চেহরা ("face", "চেহরা", "register face", "recognize face") → respond with ONLY: REGISTER_FACE or RECOGNIZE_FACE
14. When user mentions "who is speaking" or "কে কথা বলছে" → respond with ONLY: IDENTIFY_SPEAKER

=== DEVICE COMMANDS (return ONLY the command text on its own line) ===
OPEN_APP <name> | CLOSE_APP <name> | CALL <name> | WHATSAPP_CALL <name>
WHATSAPP_MSG <name> <message> | YOUTUBE_PLAY <query>
SPOTIFY_PLAY <query> | MUSIC_PLAY <query> | FLASHLIGHT_ON | FLASHLIGHT_OFF
VOLUME_UP | VOLUME_DOWN | SCREENSHOT | SCROLL_UP | SCROLL_DOWN
BACK | HOME | NOTIFICATION | SMS <name> <message>
CLICK <text> | SEARCH <query> | TYPE_TEXT <text> | READ_SCREEN
BATTERY_CHECK | SETTINGS_OPEN
SETTINGS_WIFI_ON | SETTINGS_WIFI_OFF
SETTINGS_BLUETOOTH_ON | SETTINGS_BLUETOOTH_OFF
SETTINGS_BRIGHTNESS <up|down|0-255>
|IMO_CALL <name> | MESSENGER_CALL <name> | TELEGRAM_CALL <name>
CALENDAR_TODAY | CALENDAR_UPCOMING | CALENDAR_CREATE <text>
REGISTER_FACE | RECOGNIZE_FACE | IDENTIFY_SPEAKER
NOTIFICATION

=== COMMAND FORMAT — THIS IS CRITICAL ===
- For device commands: output ONLY the command, first line, nothing else
- WRONG: "I'll play that for you!\\nYOUTUBE_PLAY song" → RIGHT: "YOUTUBE_PLAY song"
- WRONG: "Let me turn on the flashlight\\nFLASHLIGHT_ON" → RIGHT: "FLASHLIGHT_ON"
- WRONG: "You want me to read the screen?\\nREAD_SCREEN" → RIGHT: "READ_SCREEN"

=== YOUTUBE/SPOTIFY/MUSIC QUERY RULES ===
- Query MUST contain ONLY the song/video name + optional language/genre
- KEEP these words in query: "bangla", "hindi", "english", "song", "গান", "video", "music" — these are SEARCH TERMS not fillers
- REMOVE these fillers: "youtube এ", "ইউটিউবে", "spotify এ", "play করো", "চালাও", "একটা", "একটি", "ekta", "play karo", "দাও", "show", "search", "find"
- Example: "YouTube এ Tum Hi Ho চালাও" → YOUTUBE_PLAY Tum Hi Ho
- Example: "spotify এ একটা bangla song play করো" → SPOTIFY_PLAY bangla song

=== CALL COMMAND RULES ===
- Extract ONLY the contact name
- "Rahim কে call করো" → CALL Rahim
- "call my friend Rahim" → CALL Rahim

=== CALL MONITORING ===
- Call monitoring is AUTOMATIC — it runs in background
- If user asks "call monitoring করতে পারো?" → reply "হ্যাঁ, কল মনিটরিং অটোমেটিক চলছে!"

=== CONVERSATION ===
- Reply short and natural in user's preferred language
- Address user as $userName
- Be warm, witty, and human-like
- Understand commands in Bangla, English, Hindi, Arabic, and French
- Keep responses under 15 words for conversation
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
            .setSmallIcon(R.drawable.ic_maya_notif)
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
        audioFocus.abandonFocus()
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
