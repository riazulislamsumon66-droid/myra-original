package com.maya.assistant.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.view.animation.DecelerateInterpolator
import androidx.core.app.NotificationCompat
import com.maya.assistant.R
import com.maya.assistant.ui.character.CharacterOverlayView
import com.maya.assistant.ui.main.MainActivity

/**
 * MayaCharacterService — 3D character যে সবসময় সব app-এর উপরে থাকে।
 * TYPE_APPLICATION_OVERLAY দিয়ে system-level floating window তৈরি করে।
 *
 * States:
 *  IDLE      → character দাঁড়িয়ে breathing করছে
 *  LISTENING → কান পেতে আছে (user কথা বলছে)
 *  TALKING   → মুখ নাড়ছে (AI respond করছে)
 *  SLEEPING  → screen অনেকক্ষণ idle থাকলে ঘুমায়
 *  HAPPY     → GF mode-এ excited reaction
 *  THINKING  → AI processing করছে
 */
class MayaCharacterService : Service() {

    companion object {
        var isRunning = false
        const val CHANNEL_ID = "maya_character_channel"
        const val NOTIF_ID = 2001

        // Intent actions
        const val ACTION_SHOW = "SHOW_CHARACTER"
        const val ACTION_HIDE = "HIDE_CHARACTER"
        const val ACTION_SET_STATE = "SET_CHARACTER_STATE"
        const val ACTION_SET_MODE = "SET_CHARACTER_MODE"
        const val ACTION_WAKE = "WAKE_CHARACTER"
        const val ACTION_SLEEP = "SLEEP_CHARACTER"

        // State extras
        const val EXTRA_STATE = "character_state"
        const val EXTRA_MODE = "character_mode"

        enum class State { IDLE, LISTENING, TALKING, SLEEPING, HAPPY, THINKING }
        enum class Mode { DEFAULT, GF, PROFESSIONAL, FRIEND }
    }

    private var windowManager: WindowManager? = null
    private var containerView: View? = null
    private var characterView: CharacterOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var isVisible = false
    private var currentState = State.IDLE
    private var currentMode = Mode.DEFAULT

    // Auto-sleep timer — 3 minutes idle-এ ঘুমায়
    private val sleepHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val sleepRunnable = Runnable { setState(State.SLEEPING) }
    private val SLEEP_DELAY_MS = 3 * 60 * 1000L

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        // Android 14+ requires 3-argument startForeground with service type
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showCharacter()
            ACTION_HIDE -> hideCharacter()
            ACTION_SET_STATE -> {
                val stateName = intent.getStringExtra(EXTRA_STATE) ?: "IDLE"
                setState(State.valueOf(stateName))
            }
            ACTION_SET_MODE -> {
                val modeName = intent.getStringExtra(EXTRA_MODE) ?: "DEFAULT"
                setMode(Mode.valueOf(modeName))
            }
            ACTION_WAKE -> wakeUp()
            ACTION_SLEEP -> setState(State.SLEEPING)
        }
        return START_STICKY
    }

    // ── Character Window ──────────────────────────────────────

    private fun showCharacter() {
        if (isVisible || containerView != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) return

        val inflater = LayoutInflater.from(this)
        containerView = inflater.inflate(R.layout.overlay_character_3d, null)
        characterView = containerView?.findViewById(R.id.characterOverlayView)

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 24
            y = 160
        }

        windowManager?.addView(containerView, overlayParams)
        isVisible = true

        setupDragAndTap()
        characterView?.setState(CharacterOverlayView.CharState.IDLE)
        startSleepTimer()

        // Entrance animation
        containerView?.apply {
            alpha = 0f
            scaleX = 0.5f
            scaleY = 0.5f
            animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(400)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun hideCharacter() {
        containerView?.animate()
            ?.alpha(0f)?.scaleX(0.5f)?.scaleY(0.5f)
            ?.setDuration(300)
            ?.withEndAction { removeOverlay() }
            ?.start() ?: removeOverlay()
    }

    private fun removeOverlay() {
        containerView?.let { windowManager?.removeView(it) }
        containerView = null
        characterView = null
        isVisible = false
        sleepHandler.removeCallbacks(sleepRunnable)
    }

    // ── Drag & Tap ────────────────────────────────────────────

    private fun setupDragAndTap() {
        val params = overlayParams ?: return
        var initialX = 0; var initialY = 0
        var initialTouchX = 0f; var initialTouchY = 0f
        var isDragging = false
        var downTime = 0L

        containerView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    downTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isDragging = true
                        params.x = initialX - dx.toInt()
                        params.y = initialY - dy.toInt()
                        try { windowManager?.updateViewLayout(containerView, params) }
                        catch (e: Exception) { /* ignore */ }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging && System.currentTimeMillis() - downTime < 300) {
                        onCharacterTapped()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onCharacterTapped() {
        wakeUp()
        // Open main app
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    // ── State Management ──────────────────────────────────────

    private fun setState(state: State) {
        currentState = state
        val charState = when (state) {
            State.IDLE -> CharacterOverlayView.CharState.IDLE
            State.LISTENING -> CharacterOverlayView.CharState.LISTENING
            State.TALKING -> CharacterOverlayView.CharState.TALKING
            State.SLEEPING -> CharacterOverlayView.CharState.SLEEPING
            State.HAPPY -> CharacterOverlayView.CharState.HAPPY
            State.THINKING -> CharacterOverlayView.CharState.THINKING
        }

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            characterView?.setState(charState)
        }

        // Sleep timer reset
        if (state != State.SLEEPING) startSleepTimer()
    }

    private fun setMode(mode: Mode) {
        currentMode = mode
        val charMode = when (mode) {
            Mode.DEFAULT -> CharacterOverlayView.CharMode.DEFAULT
            Mode.GF -> CharacterOverlayView.CharMode.GF
            Mode.PROFESSIONAL -> CharacterOverlayView.CharMode.PROFESSIONAL
            Mode.FRIEND -> CharacterOverlayView.CharMode.FRIEND
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            characterView?.setMode(charMode)
        }
    }

    private fun wakeUp() {
        if (currentState == State.SLEEPING) {
            setState(State.HAPPY) // excited wake-up
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                setState(State.IDLE)
            }, 2000)
        } else {
            startSleepTimer()
        }
    }

    private fun startSleepTimer() {
        sleepHandler.removeCallbacks(sleepRunnable)
        sleepHandler.postDelayed(sleepRunnable, SLEEP_DELAY_MS)
    }

    // ── Notification ──────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "MAYA Character", NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "MAYA character overlay"
                setShowBadge(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MAYA ✨")
            .setContentText("Character active")
            .setSmallIcon(R.drawable.ic_myra_notif)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        removeOverlay()
        super.onDestroy()
    }

    private fun abs(value: Float) = if (value < 0) -value else value
}
