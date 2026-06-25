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
import com.maya.assistant.ai.GeminiTextClient
import com.maya.assistant.ai.IntentAnalyzer
import com.maya.assistant.ai.DynamicDecisionEngine
import com.maya.assistant.models.CommandType
import com.maya.assistant.ui.main.MainActivity
import com.maya.assistant.service.PowerButtonReceiver
import com.maya.assistant.utils.Constants
import com.maya.assistant.utils.Logger
import com.maya.assistant.security.SecurePrefs
import com.maya.assistant.voice.AudioFocusManager
import com.maya.assistant.voice.AudioPlayer
import com.maya.assistant.voice.AudioRecorder
import com.maya.assistant.voice.TextToSpeechEngine
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
    private lateinit var ttsEngine: TextToSpeechEngine
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

        // Initialize TTS engine for text-to-speech fallback
        ttsEngine = TextToSpeechEngine(this)

        // Sync language with settings
        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        val langCode = prefs.getString(Constants.KEY_LANGUAGE, "banglish") ?: "banglish"
        ttsEngine.setLanguageByCode(langCode)

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
                processCollectedAudio()
            }
        )

        audioRecorder = AudioRecorder(this) { chunk ->
            if (!VoiceStateManager.isAiSpeaking()) {
                vad.processChunk(chunk)
                // Stream audio to Gemini WebSocket
                geminiClient?.sendAudioChunk(chunk)
            }
        }

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
            geminiTextClient = GeminiTextClient(apiKey, buildSystemPrompt())
        } else {
            val plainKey = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
                .getString(Constants.KEY_API_KEY, "") ?: ""
            if (plainKey.isNotEmpty()) {
                connectGemini(plainKey)
                SecurePrefs.saveApiKey(this@ForegroundVoiceService, plainKey)
                geminiTextClient = GeminiTextClient(plainKey, buildSystemPrompt())
            } else {
                Logger.w(TAG, "No API key found — voice features disabled")
            }
        }
    }

    private fun processCollectedAudio() {
        // If WebSocket is not connected, use STT + text API as fallback
        if (geminiClient?.isConnected() != true) {
            Logger.d(TAG, "WebSocket not connected — using local STT + text API")
            val language = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
                .getString(Constants.KEY_LANGUAGE, "banglish") ?: "banglish"
            val sttLanguage = com.maya.assistant.voice.SpeechToTextEngine.getLanguageCodeForLocale(
                java.util.Locale(language)
            )

            val sttEngine = com.maya.assistant.voice.SpeechToTextEngine(this)
            sttEngine.startListening(sttLanguage, object : com.maya.assistant.voice.SpeechToTextEngine.STTCallback {
                override fun onSpeechResult(text: String) {
                    Logger.d(TAG, "STT result: $text")
                    if (text.isNotBlank()) {
                        sendTextToGemini(text)
                    } else {
                        ttsEngine.speak("আমি ঠিক শুনতে পাইনি, আবার বলুন")
                        VoiceStateManager.setListening()
                    }
                }
                override fun onSpeechError(errorCode: Int) {
                    Logger.e(TAG, "STT error: $errorCode")
                    ttsEngine.speak("Speech recognition error, try again")
                    VoiceStateManager.setListening()
                }
                override fun onSpeechStart() {
                    VoiceStateManager.setListening()
                }
                override fun onSpeechEnd() {
                    VoiceStateManager.setThinking()
                }
            })
        } else {
            Logger.d(TAG, "WebSocket connected — audio was streamed, waiting for response")
        }
    }

    private fun connectGemini(apiKey: String) {
        // Use GeminiWebSocketClient (audio streaming) — same as v108 working system
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
                handleGeminiText(text)
            },
            onTurnComplete = {
                if (!audioRecorder.isActive()) audioRecorder.start()
            },
            onError = { msg ->
                Logger.e(TAG, "Gemini error: $msg")
                // Try text API as fallback
                if (geminiTextClient != null) {
                    Logger.d(TAG, "Falling back to text API")
                }
            }
        )
        geminiClient?.connect()
    }

    private fun handleGeminiText(text: String) {
        val clean = AIResponseManager.clean(text)
        if (clean.isBlank() || AIResponseManager.hasThinkingText(clean)) {
            Logger.d(TAG, "Skipping empty/thinking response")
            return
        }

        // Try structured command first, then natural language
        val cmd = AIResponseManager.extractCommand(clean)
        val intent = if (cmd != null) {
            IntentAnalyzer.analyze(cmd)
        } else {
            if (clean.length < 80 && clean.lines().size <= 2) {
                IntentAnalyzer.analyze(clean)
            } else {
                com.maya.assistant.models.VoiceCommand(clean, CommandType.CONVERSATION)
            }
        }

        if (intent.type != CommandType.CONVERSATION && intent.type != CommandType.UNKNOWN) {
            if (com.maya.assistant.service.SmartAccessibilityEngine.service == null) {
                sendBroadcast(Intent("MAYA_RESPONSE").putExtra("text",
                    "⚠️ Accessibility Service বন্ধ আছে। Settings → Accessibility → MAYA → ON করো।"))
            }
            scope.launch {
                val result = DynamicDecisionEngine.execute(this@ForegroundVoiceService, intent)
                sendBroadcast(Intent("MAYA_RESPONSE").apply {
                    putExtra("text", result)
                    putExtra("is_result", true)
                })
            }
        }

        // Always broadcast the text response to UI
        sendBroadcast(Intent("MAYA_RESPONSE").putExtra("text", clean))

        // Speak the response via TTS (always speak, except device commands)
        val isCommand = intent.type != CommandType.CONVERSATION && intent.type != CommandType.UNKNOWN
        if (!isCommand || clean.length < 40) {
            ttsEngine.speak(clean)
        }
    }

    fun toggleRecording(): Boolean {
        return if (::audioRecorder.isInitialized && audioRecorder.isActive()) {
            audioRecorder.stop()
            false
        } else {
            audioRecorder.start()
            true
        }
    }

    fun isRecording() = ::audioRecorder.isInitialized && audioRecorder.isActive()

    fun sendTextToGemini(text: String) {
        VoiceStateManager.setThinking()

        // Use HTTP text API (reliable) — primary path
        val client = geminiTextClient
        if (client != null) {
            Logger.d(TAG, "Sending via GeminiTextClient (HTTP)...")
            client.sendMessage(text, object : GeminiTextClient.TextCallback {
                override fun onResponse(text: String) {
                    Logger.d(TAG, "Text API response received: ${text.take(80)}")
                    handleGeminiText(text)
                }
                override fun onError(error: String) {
                    Logger.e(TAG, "Text API error: $error")
                    ttsEngine.speak("Connection error, please try again")
                }
            })
        } else {
            Logger.e(TAG, "No API client available")
            ttsEngine.speak("API key not set. Please go to Settings and add your Gemini API key.")
        }
    }

    fun reconnectGemini() {
        val apiKey = SecurePrefs.getApiKey(this@ForegroundVoiceService)
        if (apiKey.isNotEmpty()) {
            connectGemini(apiKey)
        } else {
            val plainKey = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
                .getString(Constants.KEY_API_KEY, "") ?: ""
            if (plainKey.isNotEmpty()) {
                connectGemini(plainKey)
                SecurePrefs.saveApiKey(this@ForegroundVoiceService, plainKey)
            }
        }
    }

    private fun buildSystemPrompt(): String {
        val userName = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
            .getString(Constants.KEY_USER_NAME, "Boss") ?: "Boss"
        val personality = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
            .getString(Constants.KEY_PERSONALITY, "friendly") ?: "friendly"
        val language = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
            .getString(Constants.KEY_LANGUAGE, "banglish") ?: "banglish"

        // Jarvis session context
        val jarvisContext = try {
            com.maya.assistant.jarvis.JarvisSession.buildContext()
        } catch (_: Exception) { "" }
        val languageInstruction = when (language) {
            "bangla" -> "বাংলায় উত্তর দাও। বাংলা ও ইংরেজি মিশ্রণ করো না।"
            "banglish" -> "Reply in Banglish (Bangla written in English script). Do not use pure Hindi or formal Bangla."
            "hindi" -> "हिंदी में जवाब दो। अंग्रेजी मिक्स न करो।"
            "hinglish" -> "Reply in Hinglish (Hindi + English mix). Use natural conversational style."
            "english" -> "Reply in English only."
            "creole" -> "Reply in Seychellois Creole (Kreol Seselwa). Do not mix English unless necessary."
            else -> "Reply in Banglish."
        }

        return """
YOU ARE MAYA — $userName's personal AI assistant. (Jarvis-style)
Personality: $personality.
Language instruction: $languageInstruction
Session context: $jarvisContext

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
IMO_CALL <name> | MESSENGER_CALL <name> | TELEGRAM_CALL <name>
CALENDAR_TODAY | CALENDAR_UPCOMING | CALENDAR_CREATE <text>
REGISTER_FACE | RECOGNIZE_FACE | IDENTIFY_SPEAKER

=== COMMAND FORMAT — THIS IS CRITICAL ===
- For device commands: output ONLY the command, first line, nothing else
- WRONG: "I'll play that for you!\nYOUTUBE_PLAY song" → RIGHT: "YOUTUBE_PLAY song"
- WRONG: "Let me turn on the flashlight\nFLASHLIGHT_ON" → RIGHT: "FLASHLIGHT_ON"
- WRONG: "You want me to read the screen?\nREAD_SCREEN" → RIGHT: "READ_SCREEN"

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
        ttsEngine.shutdown()
        audioFocus.abandonFocus()
        geminiClient?.disconnect()
        powerButtonReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        powerButtonReceiver = null
        job.cancel()
        super.onDestroy()
    }
}
