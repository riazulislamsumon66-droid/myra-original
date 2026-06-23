package com.maya.assistant.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.*
import android.view.animation.DecelerateInterpolator
import androidx.core.app.NotificationCompat
import com.maya.assistant.R
import com.maya.assistant.ui.character.CharacterOverlayView
import com.maya.assistant.ui.main.MainActivity
import java.util.*
import kotlin.math.*

/**
 * MayaCharacterService — Shimeji-style 2D floating anime character.
 *
 * Features:
 * - System Alert Window overlay (TYPE_APPLICATION_OVERLAY)
 * - Autonomous walking/moving like Shimeji (random behavior)
 * - Bounce off screen edges
 * - Drag to move, pinch-to-zoom resize
 * - TTS sync via UtteranceProgressListener
 * - Random idle behaviors: walk, stand, sleep, climb walls
 */
class MayaCharacterService : Service() {

    companion object {
        var isRunning = false
        const val CHANNEL_ID = "maya_character_channel"
        const val NOTIF_ID = 2001

        const val ACTION_SHOW = "SHOW_CHARACTER"
        const val ACTION_HIDE = "HIDE_CHARACTER"
        const val ACTION_SET_STATE = "SET_CHARACTER_STATE"
        const val ACTION_SET_MODE = "SET_CHARACTER_MODE"
        const val ACTION_WAKE = "WAKE_CHARACTER"
        const val ACTION_SLEEP = "SLEEP_CHARACTER"
        const val ACTION_TOGGLE_AUTONOMY = "TOGGLE_AUTONOMY"

        const val EXTRA_STATE = "character_state"
        const val EXTRA_MODE = "character_mode"

        enum class State { IDLE, LISTENING, TALKING, SLEEPING, HAPPY, THINKING, WALKING }
        enum class Mode { DEFAULT, GF, PROFESSIONAL, FRIEND }

        // Shimeji behaviors
        enum class Behavior { IDLE, WALK_LEFT, WALK_RIGHT, WALK_UP, WALK_DOWN, FALL, CLIMB, SLEEP, JUMP }
    }

    private var windowManager: WindowManager? = null
    private var containerView: View? = null
    private var characterView: CharacterOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var isVisible = false
    private var currentState = State.IDLE
    private var currentMode = Mode.DEFAULT
    private var isAutonomous = true // Shimeji mode on/off

    // Screen dimensions
    private var screenWidth = 0
    private var screenHeight = 0

    // Shimeji movement
    private var currentBehavior = Behavior.IDLE
    private var behaviorRandom = Random()
    private var walkSpeed = 2f // pixels per frame
    private var behaviorDuration = 0L
    private var behaviorStartTime = 0L

    // Handler for autonomous movement loop
    private val moveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var moveRunnable: Runnable? = null
    private val MOVE_INTERVAL = 16L // ~60fps for smooth movement

    // Gravity for falling
    private var velocityY = 0f
    private val GRAVITY = 0.5f
    private val MAX_FALL_SPEED = 8f

    // Auto-sleep timer
    private val sleepHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val sleepRunnable = Runnable { setState(State.SLEEPING) }
    private val SLEEP_DELAY_MS = 3 * 60 * 1000L

    // TTS
    private var tts: TextToSpeech? = null
    private var ttsInitialized = false

    // Touch handling
    private var initialX = 0; private var initialY = 0
    private var initialTouchX = 0f; private var initialTouchY = 0f
    private var isDragging = false; private var isPinching = false
    private var downTime = 0L
    private var initialPinchDistance = 0f; private var initialPinchScale = 1f

    // ── Lifecycle ────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        initTts()
        updateScreenDimensions()
    }

    private fun updateScreenDimensions() {
        val displayMetrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getMetrics(displayMetrics)
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
    }

    // ── TTS Integration ──────────────────────────────────────

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsInitialized = true
                tts?.language = Locale("bn", "BD")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { setState(State.TALKING) }
                    override fun onDone(utteranceId: String?) { setState(State.IDLE) }
                    @Deprecated("Deprecated")
                    override fun onError(utteranceId: String?) { setState(State.IDLE) }
                    override fun onError(utteranceId: String?, errorCode: Int) { setState(State.IDLE) }
                })
            }
        }
    }

    fun speak(text: String, utteranceId: String = "maya_speech") {
        if (!ttsInitialized) {
            initTts()
            sleepHandler.postDelayed({ speakInternal(text, utteranceId) }, 500)
        } else {
            speakInternal(text, utteranceId)
        }
    }

    private fun speakInternal(text: String, utteranceId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val params = android.os.Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        } else {
            @Suppress("DEPRECATION")
            val params = HashMap<String, String>()
            params[TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID] = utteranceId
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params)
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        setState(State.IDLE)
    }

    // ── Character Window ──────────────────────────────────────

    private fun showCharacter() {
        if (isVisible || containerView != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) return

        updateScreenDimensions()

        // Lightweight Canvas-based character (ValueAnimator + Paint —
        // no bitmaps, no GPU/OpenGL, smooth on low-end phones)
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
            gravity = Gravity.BOTTOM or Gravity.START
            x = 0
            y = 200
        }

        windowManager?.addView(containerView, overlayParams)
        isVisible = true

        setupDragAndPinch()
        characterView?.setState(CharacterOverlayView.CharState.IDLE)
        startSleepTimer()

        // Start Shimeji autonomous movement
        startAutonomousMovement()

        // Entrance animation
        containerView?.apply {
            alpha = 0f; scaleX = 0.5f; scaleY = 0.5f
            animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(400).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun hideCharacter() {
        stopAutonomousMovement()
        containerView?.animate()
            ?.alpha(0f)?.scaleX(0.5f)?.scaleY(0.5f)
            ?.setDuration(300)?.withEndAction { removeOverlay() }?.start()
            ?: removeOverlay()
    }

    private fun removeOverlay() {
        stopAutonomousMovement()
        containerView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        containerView = null; characterView = null
        isVisible = false
        sleepHandler.removeCallbacks(sleepRunnable)
    }

    // ══════════════════════════════════════════════════════════
    //  SHIMEJI AUTONOMOUS MOVEMENT
    // ══════════════════════════════════════════════════════════

    private fun startAutonomousMovement() {
        isAutonomous = true
        scheduleNextBehavior()
        startMovementLoop()
    }

    private fun stopAutonomousMovement() {
        isAutonomous = false
        moveRunnable?.let { moveHandler.removeCallbacks(it) }
        moveRunnable = null
        currentBehavior = Behavior.IDLE
    }

    fun toggleAutonomy(): Boolean {
        isAutonomous = !isAutonomous
        if (isAutonomous) {
            startAutonomousMovement()
        } else {
            stopAutonomousMovement()
            setState(State.IDLE)
        }
        return isAutonomous
    }

    private fun startMovementLoop() {
        moveRunnable?.let { moveHandler.removeCallbacks(it) }
        moveRunnable = object : Runnable {
            override fun run() {
                if (!isAutonomous || !isVisible) return
                updateMovement()
                moveHandler.postDelayed(this, MOVE_INTERVAL)
            }
        }
        moveHandler.post(moveRunnable!!)
    }

    /**
     * Core Shimeji movement logic — called every frame (~60fps)
     */
    private fun updateMovement() {
        val params = overlayParams ?: return
        val charWidth = containerView?.width?.toFloat() ?: 160f
        val charHeight = containerView?.height?.toFloat() ?: 200f

        when (currentBehavior) {
            Behavior.IDLE -> {
                // Do nothing — just idle animation
            }

            Behavior.WALK_LEFT -> {
                params.x -= walkSpeed.toInt()
                // Bounce off left edge
                if (params.x <= 0) {
                    params.x = 0
                    currentBehavior = Behavior.WALK_RIGHT
                    containerView?.scaleX = 1f // face right
                }
            }

            Behavior.WALK_RIGHT -> {
                params.x += walkSpeed.toInt()
                // Bounce off right edge
                if (params.x >= screenWidth - charWidth) {
                    params.x = (screenWidth - charWidth).toInt()
                    currentBehavior = Behavior.WALK_LEFT
                    containerView?.scaleX = -1f // face left (flip)
                }
            }

            Behavior.WALK_UP -> {
                params.y -= walkSpeed.toInt()
                if (params.y <= 0) {
                    params.y = 0
                    currentBehavior = Behavior.FALL
                }
            }

            Behavior.WALK_DOWN -> {
                params.y += walkSpeed.toInt()
                // Fall if reached ground
                if (params.y >= screenHeight - charHeight) {
                    params.y = (screenHeight - charHeight).toInt().coerceAtLeast(0)
                    velocityY = 0f
                    // Random next behavior after landing
                    scheduleNextBehavior()
                    return
                }
            }

            Behavior.FALL -> {
                // Gravity pulls down
                velocityY = min(velocityY + GRAVITY, MAX_FALL_SPEED)
                params.y += velocityY.toInt()
                walkOnGround(charWidth, charHeight)
            }

            Behavior.CLIMB -> {
                params.y -= (walkSpeed * 0.7f).toInt()
                // Randomly start falling from wall
                if (behaviorRandom.nextFloat() < 0.01f) {
                    currentBehavior = Behavior.FALL
                    velocityY = 2f
                }
                // Reached top — walk on ceiling
                if (params.y <= 0) {
                    params.y = 0
                    currentBehavior = if (behaviorRandom.nextBoolean()) Behavior.WALK_LEFT else Behavior.WALK_RIGHT
                }
            }

            Behavior.SLEEP -> {
                // Zzz animation — no movement
            }

            Behavior.JUMP -> {
                velocityY = -10f // Jump up
                currentBehavior = Behavior.FALL
            }
        }

        // Apply position update
        try {
            windowManager?.updateViewLayout(containerView, params)
        } catch (_: Exception) {}
    }

    private fun walkOnGround(charWidth: Float, charHeight: Float) {
        val params = overlayParams ?: return
        // Landed on ground
        if (params.y >= screenHeight - charHeight) {
            params.y = (screenHeight - charHeight).toInt().coerceAtLeast(0)
            velocityY = 0f
            // Start walking on ground
            currentBehavior = if (behaviorRandom.nextBoolean()) Behavior.WALK_LEFT else Behavior.WALK_RIGHT
            containerView?.scaleX = if (currentBehavior == Behavior.WALK_LEFT) -1f else 1f
            setState(State.WALKING)
        }
    }

    /**
     * Schedule next random behavior (Shimeji-style AI)
     */
    private fun scheduleNextBehavior() {
        if (!isAutonomous) return
        val params = overlayParams ?: return
        val charWidth = containerView?.width?.toFloat() ?: 160f
        val charHeight = containerView?.height?.toFloat() ?: 200f

        // Duration for this behavior (1-5 seconds)
        behaviorDuration = (1000 + behaviorRandom.nextInt(4000)).toLong()
        behaviorStartTime = System.currentTimeMillis()

        // Random behavior weighted by probability
        val rand = behaviorRandom.nextFloat()
        val onGround = params.y >= screenHeight - charHeight - 50
        val onLeftEdge = params.x <= 50
        val onRightEdge = params.x >= screenWidth - charWidth - 50
        val onTop = params.y <= 50

        currentBehavior = when {
            onGround -> {
                when {
                    rand < 0.30f -> Behavior.WALK_LEFT
                    rand < 0.60f -> Behavior.WALK_RIGHT
                    rand < 0.75f -> Behavior.IDLE
                    rand < 0.85f -> Behavior.JUMP
                    rand < 0.95f -> Behavior.CLIMB
                    else -> Behavior.SLEEP
                }
            }
            onLeftEdge -> {
                when {
                    rand < 0.50f -> Behavior.WALK_RIGHT
                    rand < 0.70f -> Behavior.FALL
                    else -> Behavior.CLIMB
                }
            }
            onRightEdge -> {
                when {
                    rand < 0.50f -> Behavior.WALK_LEFT
                    rand < 0.70f -> Behavior.FALL
                    else -> Behavior.CLIMB
                }
            }
            onTop -> {
                when {
                    rand < 0.40f -> Behavior.FALL
                    rand < 0.60f -> Behavior.WALK_LEFT
                    else -> Behavior.WALK_RIGHT
                }
            }
            else -> {
                when {
                    rand < 0.40f -> Behavior.FALL
                    rand < 0.60f -> Behavior.WALK_LEFT
                    rand < 0.80f -> Behavior.WALK_RIGHT
                    else -> Behavior.CLIMB
                }
            }
        }

        // Update character state based on behavior
        val newState = when (currentBehavior) {
            Behavior.IDLE -> State.IDLE
            Behavior.SLEEP -> State.SLEEPING
            Behavior.JUMP -> State.HAPPY
            Behavior.WALK_LEFT, Behavior.WALK_RIGHT, Behavior.WALK_UP, Behavior.WALK_DOWN -> State.WALKING
            Behavior.FALL -> State.IDLE
            Behavior.CLIMB -> State.IDLE
        }
        setState(newState)

        // Flip character direction for left/right walking
        if (currentBehavior == Behavior.WALK_LEFT) containerView?.scaleX = -1f
        if (currentBehavior == Behavior.WALK_RIGHT) containerView?.scaleX = 1f

        // Schedule next behavior change
        moveHandler.postDelayed({ scheduleNextBehavior() }, behaviorDuration)
    }

    // ── Drag & Pinch-to-Zoom ──────────────────────────────────

    private fun setupDragAndPinch() {
        containerView?.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = overlayParams?.x ?: 0
                    initialY = overlayParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false; isPinching = false
                    downTime = System.currentTimeMillis()
                    // Stop autonomous movement while touching
                    if (isAutonomous) currentBehavior = Behavior.IDLE
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2) {
                        isPinching = true; isDragging = false
                        initialPinchDistance = getPinchDistance(event)
                        initialPinchScale = characterView?.getScale() ?: 1f
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isPinching && event.pointerCount == 2) {
                        val currentDistance = getPinchDistance(event)
                        if (initialPinchDistance > 0) {
                            val scaleFactor = currentDistance / initialPinchDistance
                            val newScale = (initialPinchScale * scaleFactor).coerceIn(0.5f, 3f)
                            characterView?.setScale(newScale)
                        }
                        true
                    } else if (!isPinching) {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            isDragging = true
                            overlayParams?.let { params ->
                                params.x = (initialX + dx.toInt()).coerceIn(0, screenWidth - (containerView?.width ?: 160))
                                params.y = (initialY + dy.toInt()).coerceIn(0, screenHeight - (containerView?.height ?: 200))
                                try { windowManager?.updateViewLayout(containerView, params) }
                                catch (_: Exception) {}
                            }
                        }
                        true
                    } else false
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging && !isPinching && System.currentTimeMillis() - downTime < 300) {
                        onCharacterTapped()
                    }
                    // Resume autonomous movement after touch
                    if (isAutonomous) {
                        velocityY = 0f
                        currentBehavior = Behavior.FALL // Start falling from current position
                        scheduleNextBehavior()
                    }
                    isDragging = false; isPinching = false
                    true
                }
                MotionEvent.ACTION_POINTER_UP -> { isPinching = false; true }
                MotionEvent.ACTION_CANCEL -> { isDragging = false; isPinching = false; true }
                else -> false
            }
        }
    }

    private fun getPinchDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return sqrt(x * x + y * y)
    }

    private fun onCharacterTapped() {
        wakeUp()
        // Cycle through behaviors on tap
        if (isAutonomous) {
            currentBehavior = when (behaviorRandom.nextInt(4)) {
                0 -> Behavior.JUMP
                1 -> Behavior.CLIMB
                2 -> Behavior.SLEEP
                else -> Behavior.IDLE
            }
            if (currentBehavior == Behavior.SLEEP) setState(State.SLEEPING)
            else setState(State.HAPPY)
        }
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
            State.WALKING -> CharacterOverlayView.CharState.IDLE // Use idle animation for walking
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            characterView?.setState(charState)
        }
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
            setState(State.HAPPY)
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

    // ── Lifecycle ─────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showCharacter()
            ACTION_HIDE -> hideCharacter()
            ACTION_SET_STATE -> {
                val stateName = intent.getStringExtra(EXTRA_STATE) ?: "IDLE"
                try { setState(State.valueOf(stateName)) } catch (_: Exception) {}
            }
            ACTION_SET_MODE -> {
                val modeName = intent.getStringExtra(EXTRA_MODE) ?: "DEFAULT"
                try { setMode(Mode.valueOf(modeName)) } catch (_: Exception) {}
            }
            ACTION_WAKE -> wakeUp()
            ACTION_SLEEP -> setState(State.SLEEPING)
            ACTION_TOGGLE_AUTONOMY -> toggleAutonomy()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        tts?.stop(); tts?.shutdown()
        removeOverlay()
        super.onDestroy()
    }

    // ── Notification ──────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "MAYA Character", NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "MAYA character overlay"
                setShowBadge(false); setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MAYA ✨")
            .setContentText("Character active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi).setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN).setSilent(true).build()
    }

    private fun abs(value: Float) = if (value < 0) -value else value
}
