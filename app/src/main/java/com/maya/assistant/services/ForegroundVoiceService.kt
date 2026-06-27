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
import com.maya.assistant.security.SecurePrefs
import com.maya.assistant.voice.VoiceActivityDetector
import com.maya.assistant.voice.VoiceAuthManager
import com.maya.assistant.voice.VoiceStateManager
import com.maya.assistant.voice.SpeechRecognizerWakeWordDetector
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
    private var wakeWordDetector: SpeechRecognizerWakeWordDetector? = null
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
                resetInactivityTimer()
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

        // NOTE: previously this also eagerly called connectGemini() here,
        // meaning a live WebSocket to Gemini was held open for the entire
        // lifetime of the service — even while just idling in wake-word
        // mode, never having heard "Hey MAYA". That's wasted battery/data
        // for a connection that's only needed once a conversation actually
        // starts. Now only the lightweight wake-word detector starts;
        // Gemini connects on-demand in onWakeWordDetected()/toggleRecording().
        startWakeWordDetection()
        Logger.d(TAG, "Components initialized. Mode: WAKE_WORD (Gemini not connected — connects on demand), waiting for 'Hey MAYA'...")
    }

    private fun startWakeWordDetection() {
        wakeWordDetector = SpeechRecognizerWakeWordDetector(this) {
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

        // Connect to Gemini now that a real conversation is starting —
        // not held open the whole time the service was idling in
        // wake-word mode.
        ensureGeminiConnected()

        // SpeechRecognizer releases the microphone asynchronously (its
        // internal stop()/destroy() are posted to the main handler), so a
        // short delay here avoids a race where AudioRecorder tries to open
        // the mic before the previous session has actually let go of it.
        scope.launch {
            kotlinx.coroutines.delay(200)
            startActiveConversation()
        }
    }

    private fun startActiveConversation() {
        // Authenticate voice
        Logger.d(TAG, "Starting voice authentication...")
        voiceAuth.authenticateVoice { isAuthorized, speakerName ->
            Logger.d(TAG, "Voice auth result: speaker=$speakerName, authorized=$isAuthorized")

            // Start recording for command
            vad.reset()
            audioFocus.requestFocus(
                onGained = { if (!audioRecorder.isActive()) audioRecorder.start() },
                onLost = { audioRecorder.stop() }
            )
            VoiceStateManager.setListening()
            resetInactivityTimer()
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

    /**
     * Connects Gemini if it isn't already connected. Safe to call multiple
     * times — connectGemini() itself disconnects any stale client first.
     */
    private fun ensureGeminiConnected() {
        if (geminiClient != null && geminiClient!!.isConnected()) return
        val apiKey = SecurePrefs.getApiKey(this).ifEmpty {
            prefs().getString(Constants.KEY_API_KEY, "") ?: ""
        }
        if (apiKey.isNotEmpty()) {
            connectGemini(apiKey)
        } else {
            Logger.w(TAG, "ensureGeminiConnected: no API key set")
        }
    }

    private var inactivityJob: kotlinx.coroutines.Job? = null
    private val INACTIVITY_TIMEOUT_MS = 30_000L // 30s of no speech/command while ACTIVE -> back to wake-word-only

    private fun returnToWakeWordMode() {
        currentMode = Mode.WAKE_WORD
        inactivityJob?.cancel()
        inactivityJob = null
        // Disconnect Gemini — no need to hold a live WebSocket open while
        // just idling for the next "Hey MAYA". Reconnects on demand the
        // next time onWakeWordDetected()/toggleRecording() fires.
        geminiClient?.disconnect()
        VoiceStateManager.setIdle()
        // Brief delay before claiming the mic with SpeechRecognizer — same
        // handoff-race reasoning as in onWakeWordDetected(), just in the
        // opposite direction (AudioRecorder releasing -> SpeechRecognizer
        // claiming, instead of the other way around).
        scope.launch {
            kotlinx.coroutines.delay(200)
            startWakeWordDetection()
        }
    }

    /**
     * Restarts the inactivity countdown. Call this whenever there's a sign
     * of life in the conversation (speech detected, response received,
     * etc.) so an active conversation doesn't get cut off just because the
     * timer happened to be close to firing.
     */
    private fun resetInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = scope.launch {
            kotlinx.coroutines.delay(INACTIVITY_TIMEOUT_MS)
            if (currentMode == Mode.ACTIVE) {
                Logger.d(TAG, "Inactivity timeout (${INACTIVITY_TIMEOUT_MS}ms) — returning to wake-word-only mode")
                if (audioRecorder.isActive()) audioRecorder.stop()
                returnToWakeWordMode()
            }
        }
    }

    private fun connectGemini(apiKey: String) {
        geminiClient?.disconnect()
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
                // Defense in depth: clean() strips known thinking-trace patterns,
                // but if Gemini phrases its internal reasoning in a way clean()
                // didn't anticipate, hasThinkingText() catches it here so it's
                // never shown in the UI or treated as a command.
                if (clean.isNotBlank() && !AIResponseManager.hasThinkingText(clean)) {
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
                // Give the user a real window to keep the conversation
                // going (follow-up question, etc.) instead of unconditionally
                // snapping back to wake-word-only mode 2s after every single
                // response — resetInactivityTimer() means it only times out
                // after genuine silence.
                if (currentMode == Mode.ACTIVE) {
                    resetInactivityTimer()
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
        val apiKey = SecurePrefs.getApiKey(this).ifEmpty {
            prefs().getString(Constants.KEY_API_KEY, "") ?: ""
        }
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
            Logger.d(TAG, "toggleRecording: STOP recording → send turnComplete → THINKING")
            audioRecorder.stop()
            geminiClient?.sendTurnComplete()
            // NOTE: previously this called setIdle(), which immediately
            // showed "SYSTEM READY" even though Gemini was still
            // processing the turn in the background — making it look like
            // nothing was happening until a response eventually arrived
            // (or never did, if turnComplete had been silently dropped —
            // see the GeminiWebSocketClient fix). setThinking() reflects
            // the actual state; onTextReceived/onAudioReceived/onTurnComplete
            // will move it to SPEAKING or back to IDLE once a real response
            // (or its absence) is known.
            VoiceStateManager.setThinking()

            // Safety-net: if nothing comes back (network glitch, API
            // hiccup, etc.) don't leave the UI stuck on "THINKING" forever.
            scope.launch {
                kotlinx.coroutines.delay(15000)
                if (VoiceStateManager.state.value == Constants.STATE_THINKING) {
                    Logger.w(TAG, "No response within 15s of turnComplete — resetting to idle")
                    VoiceStateManager.setError("কোনো response আসলো না, আবার চেষ্টা করো")
                }
            }
            false
        } else {
            ensureGeminiConnected()
            // Stop wake word detection during button recording
            wakeWordDetector?.stop()
            currentMode = Mode.ACTIVE
            vad.reset()
            VoiceStateManager.setListening()
            resetInactivityTimer()
            // Same SpeechRecognizer/AudioRecorder mic-handoff race as
            // onWakeWordDetected() — brief delay so the previous mic
            // session has actually released before AudioRecorder claims it.
            scope.launch {
                kotlinx.coroutines.delay(200)
                audioRecorder.start()
            }
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
        Logger.w(TAG, "App swiped from recents — ending any active conversation, staying alive in background for 'Hey MAYA'")
        if (audioRecorder.isActive()) {
            audioRecorder.stop()
            geminiClient?.sendTurnComplete()
        }
        // Intentionally NOT stopping the wake-word detector or calling
        // stopForeground() here. The whole point of this service is to
        // keep listening for "Hey MAYA" in the background even after the
        // user has closed/swiped the app — only an explicit user action
        // (disabling the assistant in Settings, or a real onDestroy from
        // the system under extreme memory pressure) should stop that.
        if (currentMode == Mode.ACTIVE) {
            returnToWakeWordMode()
        }
    }

    override fun onDestroy() {
        Logger.d(TAG, "=== SERVICE DESTROY ===")
        isRunning = false
        instance = null
        wakeWordDetector?.stop()
        inactivityJob?.cancel()
        audioRecorder.stop()
        audioPlayer.release()
        audioFocus.abandonFocus()
        geminiClient?.disconnect()
        job.cancel()
        super.onDestroy()
    }
}
