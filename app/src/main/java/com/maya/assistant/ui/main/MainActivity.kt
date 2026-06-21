package com.maya.assistant.ui.main

import android.app.ActivityManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.*
import android.view.WindowManager
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.maya.assistant.R
import com.maya.assistant.security.BiometricManager
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

    private val TAG = "MAIN"
    private val viewModel: MainViewModel by viewModels()

    private lateinit var orbView: OrbAnimationView
    private lateinit var waveformView: WaveformView
    private lateinit var statusText: TextView
    private lateinit var micButton: ImageButton
    private lateinit var chatRecycler: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var batteryText: TextView
    private lateinit var ramText: TextView
    private lateinit var timeText: TextView

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
            addBotMessage(text)
        }
    }

    companion object {
        const val PERM_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.TRANSPARENT
        setContentView(R.layout.activity_main)

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
        checkAccessibility()
    }

    private fun initViews() {
        orbView     = findViewById(R.id.orbView)
        waveformView = findViewById(R.id.waveformView)
        statusText  = findViewById(R.id.statusText)
        micButton   = findViewById(R.id.micButton)
        chatRecycler = findViewById(R.id.chatRecycler)
        batteryText = findViewById(R.id.batteryText)
        ramText     = findViewById(R.id.ramText)
        timeText    = findViewById(R.id.timeText)

        chatAdapter = ChatAdapter()
        chatRecycler.layoutManager = LinearLayoutManager(this).also { it.stackFromEnd = true }
        chatRecycler.adapter = chatAdapter

        micButton.setOnClickListener {
            val svc = ForegroundVoiceService.instance
            if (svc != null) {
                val text = "Hey MAYA"
                svc.sendTextToGemini(text)
                addUserMessage(text)
            } else {
                startVoiceService()
            }
        }

        micButton.setOnLongClickListener {
            ForegroundVoiceService.instance?.reconnectGemini()
            addBotMessage("Reconnecting...")
            true
        }

        findViewById<ImageButton>(R.id.settingsBtn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun observeVoiceState() {
        VoiceStateManager.state.observe(this) { state ->
            when (state) {
                Constants.STATE_LISTENING -> {
                    orbView.setListening()
                    micButton.setImageResource(R.drawable.ic_mic_on)
                    statusText.setTextColor(0xFF00E5FF.toInt())
                }
                Constants.STATE_THINKING -> {
                    orbView.setThinking()
                    statusText.setTextColor(0xFFD500F9.toInt())
                }
                Constants.STATE_SPEAKING -> {
                    orbView.setSpeaking()
                    micButton.setImageResource(R.drawable.ic_mic_off)
                    statusText.setTextColor(0xFFFF1744.toInt())
                }
                else -> {
                    orbView.setIdle()
                    micButton.setImageResource(R.drawable.ic_mic_off)
                    statusText.setTextColor(0xFFFF1744.toInt())
                }
            }
        }
        VoiceStateManager.amplitude.observe(this) { amp ->
            waveformView.updateAmplitude(amp)
            orbView.setAmplitude(amp)
        }
        VoiceStateManager.statusMessage.observe(this) { msg ->
            statusText.text = msg
        }
    }

    private fun observeViewModel() {
        viewModel.aiResponse.observe(this) { text ->
            if (!text.isNullOrBlank()) addBotMessage(text)
        }
    }

    private fun startVoiceService() {
        val apiKey = prefs().getString(Constants.KEY_API_KEY, "") ?: ""
        if (apiKey.isEmpty()) {
            addBotMessage("⚠️ API Key required. Please go to Settings → Enter Gemini API Key.")
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, ForegroundVoiceService::class.java))
        Logger.d(TAG, "Voice service started")
    }

    private fun checkAccessibility() {
        if (!AccessibilityHelperService.isEnabled(this)) {
            addBotMessage("⚠️ Enable Accessibility Service for app control. Settings → Accessibility.")
        }
    }

    fun addUserMessage(text: String) = runOnUiThread {
        chatAdapter.addMessage(ChatMessage(text, true))
        chatRecycler.scrollToPosition(chatAdapter.itemCount - 1)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(responseReceiver, IntentFilter("MAYA_RESPONSE"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(responseReceiver, IntentFilter("MAYA_RESPONSE"))
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(responseReceiver) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        timeHandler.removeCallbacks(timeRunnable)
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
