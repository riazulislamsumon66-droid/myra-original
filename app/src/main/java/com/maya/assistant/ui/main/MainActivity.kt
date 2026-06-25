package com.maya.assistant.ui.main

import android.app.Activity
import android.app.ActivityManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.*
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.maya.assistant.R
import com.maya.assistant.jarvis.JarvisGreeting
import com.maya.assistant.jarvis.JarvisOrbView
import com.maya.assistant.jarvis.JarvisSession
import com.maya.assistant.jarvis.JarvisSuggestions
import com.maya.assistant.security.BiometricManager
import com.maya.assistant.security.SecurePrefs
import com.maya.assistant.service.AccessibilityHelperService
import com.maya.assistant.services.ForegroundVoiceService
import com.maya.assistant.ui.settings.SettingsActivity
import com.maya.assistant.utils.Constants
import com.maya.assistant.utils.Logger
import com.maya.assistant.utils.PermissionUtils
import com.maya.assistant.utils.prefs
import com.maya.assistant.viewmodel.MainViewModel
import com.maya.assistant.voice.VoiceStateManager
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private val TAG = "JARVIS_MAIN"
    private val viewModel: MainViewModel by viewModels()

    private lateinit var orbView: JarvisOrbView
    private lateinit var waveformView: WaveformView
    private lateinit var statusText: TextView
    private lateinit var micButton: ImageButton
    private lateinit var chatRecycler: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var batteryText: TextView
    private lateinit var ramText: TextView
    private lateinit var timeText: TextView
    private lateinit var suggestionCard: androidx.cardview.widget.CardView
    private lateinit var suggestionText: TextView

    private var isReceiverRegistered = false
    private var lastUserCommand: String? = null
    private var isFirstInteraction = true

    private val timeHandler = Handler(Looper.getMainLooper())
    private val timeRunnable = object : Runnable {
        override fun run() {
            updateSystemInfo()
            timeHandler.postDelayed(this, 1000)
        }
    }

    private val responseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra("text") ?: return
            Logger.d(TAG, "Broadcast received: '$text'")
            addBotMessage(text)
        }
    }

    companion object {
        const val PERM_CODE = 100
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            com.maya.assistant.screenvision.ScreenCaptureService.start(
                this, result.resultCode, result.data!!
            )
            addBotMessage("✅ Screen Vision চালু — OCR active।")
        } else {
            addBotMessage("⚠️ Screen Vision permission denied।")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.parseColor("#0A0E27")
        setContentView(R.layout.activity_main)

        // Initialize Jarvis Session
        JarvisSession.init(this)

        initViews()
        updateSystemInfo()
        timeHandler.post(timeRunnable)

        BiometricManager.authenticate(
            this,
            onSuccess = { setupAssistant() },
            onError = { setupAssistant() },
            onFallback = { setupAssistant() }
        )
    }

    private fun setupAssistant() {
        PermissionUtils.requestMissing(this, PERM_CODE)
        observeViewModel()
        observeVoiceState()
        startVoiceService()
        startCallMonitorService()
        checkAccessibility()
        checkBatteryOptimization()

        // Jarvis greeting for returning user
        if (isFirstInteraction) {
            isFirstInteraction = false
            showJarvisGreeting()
        }
    }

    private fun showJarvisGreeting() {
        val userName = JarvisSession.userName
        val greeting = JarvisGreeting.getGreeting(this, userName)
        addBotMessage(greeting)
    }

    private fun showWelcomeBack() {
        val faceName = JarvisSession.lastRecognizedFaceId
        val greeting = JarvisGreeting.getWelcomeBack(this, JarvisSession.userName, faceName)
        addBotMessage(greeting)
    }

    private fun showProactiveSuggestion() {
        val suggestion = JarvisSuggestions.getProactiveSuggestion(this, lastUserCommand)
        if (suggestion != null) {
            suggestionText.text = suggestion
            suggestionCard.visibility = android.view.View.VISIBLE
            // Auto-hide after 8 seconds
            Handler(Looper.getMainLooper()).postDelayed({
                suggestionCard.visibility = android.view.View.GONE
            }, 8000)
        }
    }

    private fun requestScreenCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startCallMonitorService() {
        if (PermissionUtils.hasPermission(this, android.Manifest.permission.READ_PHONE_STATE)) {
            try {
                ContextCompat.startForegroundService(
                    this, Intent(this, com.maya.assistant.service.CallMonitorService::class.java)
                )
                Logger.d(TAG, "CallMonitorService started")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to start CallMonitorService: ${e.message}")
            }
        }
    }

    private fun checkBatteryOptimization() {
        if (!PermissionUtils.isBatteryOptimizationIgnored(this)) {
            addBotMessage("⚠️ ব্যাটারি optimization বন্ধ করো — background-এ চলতে পারবে না।")
        }
    }

    private fun initViews() {
        orbView = findViewById(R.id.orbView)
        waveformView = findViewById(R.id.waveformView)
        statusText = findViewById(R.id.statusText)
        micButton = findViewById(R.id.micButton)
        chatRecycler = findViewById(R.id.chatRecycler)
        batteryText = findViewById(R.id.batteryText)
        ramText = findViewById(R.id.ramText)
        timeText = findViewById(R.id.timeText)
        suggestionCard = findViewById(R.id.suggestionCard)
        suggestionText = findViewById(R.id.suggestionText)

        chatAdapter = ChatAdapter()
        chatRecycler.layoutManager = LinearLayoutManager(this).also { it.stackFromEnd = true }
        chatRecycler.adapter = chatAdapter

        micButton.setOnClickListener {
            Logger.d(TAG, "MIC BUTTON TAPPED")
            val svc = ForegroundVoiceService.instance
            if (svc != null) {
                val isRecording = svc.toggleRecording()
                if (isRecording) {
                    Logger.d(TAG, "Mic button: STARTED recording (conversation mode)")
                    addUserMessage("🎤 Listening...")
                } else {
                    Logger.d(TAG, "Mic button: STOPPED recording → processing")
                    addUserMessage("🎤 Processing...")
                }
            } else {
                Logger.w(TAG, "Mic button: Service not running, starting service...")
                startVoiceService()
            }
        }

        micButton.setOnLongClickListener {
            Logger.d(TAG, "MIC BUTTON LONG PRESSED → reconnect Gemini")
            ForegroundVoiceService.instance?.reconnectGemini()
            addBotMessage("Reconnecting...")
            true
        }

        findViewById<ImageButton>(R.id.settingsBtn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<ImageButton>(R.id.screenBtn).setOnClickListener {
            requestScreenCapture()
        }

        // Suggestion card click to dismiss
        suggestionCard.setOnClickListener {
            suggestionCard.visibility = android.view.View.GONE
        }
    }

    private fun observeVoiceState() {
        VoiceStateManager.state.observe(this) { state ->
            when (state) {
                Constants.STATE_LISTENING -> {
                    orbView.setListening()
                    micButton.setImageResource(R.drawable.ic_mic_on)
                    statusText.setTextColor(Color.parseColor("#00E5FF"))
                    statusText.text = "LISTENING"
                }
                Constants.STATE_THINKING -> {
                    orbView.setThinking()
                    statusText.setTextColor(Color.parseColor("#7C4DFF"))
                    statusText.text = "PROCESSING"
                }
                Constants.STATE_SPEAKING -> {
                    orbView.setSpeaking()
                    micButton.setImageResource(R.drawable.ic_mic_off)
                    statusText.setTextColor(Color.parseColor("#FF1744"))
                    statusText.text = "SPEAKING"
                }
                else -> {
                    orbView.setIdle()
                    micButton.setImageResource(R.drawable.ic_mic_off)
                    statusText.setTextColor(Color.parseColor("#00E5FF"))
                    statusText.text = "SYSTEM READY"
                }
            }
        }
        VoiceStateManager.amplitude.observe(this) { amp ->
            waveformView.updateAmplitude(amp)
            orbView.setAmplitude(amp)
        }
        VoiceStateManager.statusMessage.observe(this) { msg ->
            if (msg.isNotEmpty()) statusText.text = msg
        }
    }

    private fun observeViewModel() {
        viewModel.aiResponse.observe(this) { text ->
            if (!text.isNullOrBlank()) addBotMessage(text)
        }
    }

    private fun startVoiceService() {
        if (!PermissionUtils.hasMicPermission(this)) {
            Logger.w(TAG, "RECORD_AUDIO not granted")
            addBotMessage("⚠️ Microphone permission দরকার। Settings → Permissions → Allow।")
            return
        }
        val apiKey = SecurePrefs.getApiKey(this).ifEmpty {
            prefs().getString(Constants.KEY_API_KEY, "") ?: ""
        }
        if (apiKey.isEmpty()) {
            Logger.w(TAG, "API Key empty — cannot start voice service")
            addBotMessage("⚠️ API Key required. Settings → Enter Gemini API Key.")
            return
        }
        Logger.d(TAG, "Starting ForegroundVoiceService...")
        ContextCompat.startForegroundService(this, Intent(this, ForegroundVoiceService::class.java))
        Logger.d(TAG, "ForegroundVoiceService started")
    }

    private fun checkAccessibility() {
        if (!AccessibilityHelperService.isEnabled(this)) {
            addBotMessage("⚠️ Accessibility Service enable করো — app control করতে। Settings → Accessibility → MAYA → ON")
        }
    }

    fun addUserMessage(text: String) = runOnUiThread {
        chatAdapter.addMessage(ChatMessage(text, true))
        chatRecycler.scrollToPosition(chatAdapter.itemCount - 1)
        lastUserCommand = text
        // Show proactive suggestion after user command
        Handler(Looper.getMainLooper()).postDelayed({ showProactiveSuggestion() }, 2000)
    }

    fun addBotMessage(text: String) = runOnUiThread {
        chatAdapter.addMessage(ChatMessage(text, false))
        chatRecycler.scrollToPosition(chatAdapter.itemCount - 1)
    }

    private fun updateSystemInfo() {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        timeText.text = sdf.format(Date())
        val bm = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        batteryText.text = "${bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0}%"
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        ramText.text = "${(mi.totalMem - mi.availMem) / 1048576}MB"
    }

    override fun onResume() {
        super.onResume()
        if (com.maya.assistant.security.SecurityManager.isAppLockEnabled(this)
            || com.maya.assistant.security.PatternManager.isPatternLockEnabled(this)
        ) {
            if (!com.maya.assistant.security.AppLockActivity.isUnlockedThisSession) {
                com.maya.assistant.security.AppLockActivity.launch(this)
                return
            }
        }
        if (!isReceiverRegistered) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(responseReceiver, IntentFilter("MAYA_RESPONSE"), Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(responseReceiver, IntentFilter("MAYA_RESPONSE"))
            }
            isReceiverRegistered = true
        }
        timeHandler.post(timeRunnable)
    }

    override fun onPause() {
        super.onPause()
        timeHandler.removeCallbacks(timeRunnable)
        if (isReceiverRegistered) {
            try { unregisterReceiver(responseReceiver) } catch (_: Exception) {}
            isReceiverRegistered = false
        }
    }

    override fun onDestroy() {
        timeHandler.removeCallbacks(timeRunnable)
        (orbView as? JarvisOrbView)?.release()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startVoiceService()
        }
    }
}
