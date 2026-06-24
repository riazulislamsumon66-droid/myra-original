package com.maya.assistant.jarvis

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * JarvisOrbView — Iron Man style animated glowing orb.
 * 
 * States:
 * - IDLE: Slow pulsing blue ring
 * - LISTENING: Pulsing cyan with waveform rings
 * - THINKING: Spinning purple/orange energy
 * - SPEAKING: Red waveform with audio amplitude
 */
class JarvisOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // State
    private var state = State.IDLE
    private var amplitude = 0f
    private var rotationAngle = 0f

    // Animation
    private var pulsePhase = 0f
    private var rotationPhase = 0f
    private val pulseAnimator: ValueAnimator

    // Paints
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    enum class State {
        IDLE, LISTENING, THINKING, SPEAKING
    }

    init {
        pulseAnimator = ValueAnimator.ofFloat(0f, 2f * PI.toFloat()).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                pulsePhase = it.animatedValue as Float
                if (state == State.THINKING) {
                    rotationPhase += 3f
                }
                invalidate()
            }
        }
        pulseAnimator.start()
    }

    fun setState(newState: State) {
        state = newState
        invalidate()
    }

    fun setListening() = setState(State.LISTENING)
    fun setThinking() = setState(State.THINKING)
    fun setSpeaking() = setState(State.SPEAKING)
    fun setIdle() = setState(State.IDLE)

    fun setAmplitude(amp: Float) {
        amplitude = amp.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        val baseRadius = Math.min(centerX, centerY) * 0.5f

        when (state) {
            State.IDLE -> drawIdleOrb(canvas, centerX, centerY, baseRadius)
            State.LISTENING -> drawListeningOrb(canvas, centerX, centerY, baseRadius)
            State.THINKING -> drawThinkingOrb(canvas, centerX, centerY, baseRadius)
            State.SPEAKING -> drawSpeakingOrb(canvas, centerX, centerY, baseRadius)
        }
    }

    private fun drawIdleOrb(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        // Gentle blue pulse
        val pulse = 0.5f + 0.5f * sin(pulsePhase)
        val glowAlpha = (80 + 40 * pulse).toInt()
        val ringRadius = radius * (0.9f + 0.05f * pulse)

        // Outer glow ring
        glowPaint.color = Color.argb(glowAlpha, 0, 150, 255)
        canvas.drawCircle(cx, cy, ringRadius, glowPaint)

        // Inner core
        val coreAlpha = (100 + 50 * pulse).toInt()
        corePaint.color = Color.argb(coreAlpha, 0, 100, 200)
        canvas.drawCircle(cx, cy, radius * 0.3f, corePaint)

        // Arc segments (Jarvis-style partial rings)
        drawArcSegments(canvas, cx, cy, ringRadius * 0.85f, glowAlpha / 2, 4)
    }

    private fun drawListeningOrb(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        // Cyan pulsing rings
        val pulse = 0.5f + 0.5f * sin(pulsePhase * 2f)
        val glowAlpha = (120 + 80 * pulse).toInt()

        // Outer ring
        glowPaint.color = Color.argb(glowAlpha, 0, 255, 255)
        glowPaint.strokeWidth = 4f + 2f * pulse
        canvas.drawCircle(cx, cy, radius, glowPaint)

        // Waveform rings (3 concentric)
        for (i in 0..2) {
            val ringRadius = radius * (0.5f + i * 0.2f)
            val alpha = (100 - i * 25).toInt()
            ringPaint.color = Color.argb(alpha, 0, 200, 255)
            canvas.drawCircle(cx, cy, ringRadius * (0.9f + 0.1f * sin(pulsePhase + i * 0.5f)), ringPaint)
        }

        // Core
        val coreSize = radius * 0.2f * (1f + 0.1f * pulse)
        corePaint.color = Color.argb(200, 0, 255, 255)
        canvas.drawCircle(cx, cy, coreSize, corePaint)

        // Audio level indicator ring
        if (amplitude > 0f) {
            val ampRadius = radius * (0.6f + amplitude * 0.3f)
            arcPaint.color = Color.argb((200 * amplitude).toInt(), 0, 255, 255)
            canvas.drawArc(
                cx - ampRadius, cy - ampRadius, cx + ampRadius, cy + ampRadius,
                -90f, 360f * amplitude, false, arcPaint
            )
        }
    }

    private fun drawThinkingOrb(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        // Purple spinning energy
        val pulse = 0.5f + 0.5f * sin(pulsePhase * 3f)

        // Rotating arcs
        for (i in 0..3) {
            val startAngle = rotationPhase + i * 90f
            val sweepAngle = 60f + 30f * pulse
            val arcRadius = radius * (0.7f + i * 0.1f)
            val alpha = (100 + 50 * pulse).toInt()

            arcPaint.color = Color.argb(alpha, 180, 0, 255)
            canvas.drawArc(
                cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius,
                startAngle, sweepAngle, false, arcPaint
            )
        }

        // Spinning dots
        for (i in 0..7) {
            val angle = Math.toRadians((rotationPhase * 2 + i * 45.0).toDouble())
            val dotRadius = radius * 0.85f
            val dotX = cx + dotRadius * cos(angle).toFloat()
            val dotY = cy + dotRadius * sin(angle).toFloat()
            corePaint.color = Color.argb(200, 200, 0, 255)
            canvas.drawCircle(dotX, dotY, 4f, corePaint)
        }

        // Core
        corePaint.color = Color.argb((150 + 80 * pulse).toInt(), 100, 0, 200)
        canvas.drawCircle(cx, cy, radius * 0.25f, corePaint)
    }

    private fun drawSpeakingOrb(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        // Red with amplitude-driven waveform
        val pulse = 0.5f + 0.5f * sin(pulsePhase * 4f)
        val effectiveAmplitude = amplitude.coerceIn(0.1f, 1f)

        // Outer glow
        val glowRadius = radius * (1f + effectiveAmplitude * 0.2f)
        glowPaint.color = Color.argb((100 + 80 * effectiveAmplitude).toInt(), 255, 50, 50)
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)

        // Waveform bars around orb
        val barCount = 12
        for (i in 0 until barCount) {
            val angle = (i * 360f / barCount + pulsePhase * 30f) % 360f
            val rad = Math.toRadians(angle.toDouble())
            val barHeight = 10f + effectiveAmplitude * 25f * (
                0.5f + 0.5f * sin(pulsePhase * 2f + i * 0.5f)
            )
            val innerR = radius * 0.6f
            val outerR = innerR + barHeight

            val x1 = cx + innerR * cos(rad).toFloat()
            val y1 = cy + innerR * sin(rad).toFloat()
            val x2 = cx + outerR * cos(rad).toFloat()
            val y2 = cy + outerR * sin(rad).toFloat()

            ringPaint.color = Color.argb((180 * effectiveAmplitude).toInt(), 255, 80, 80)
            canvas.drawLine(x1, y1, x2, y2, ringPaint)
        }

        // Core - size follows amplitude
        val coreSize = radius * 0.3f * (0.8f + effectiveAmplitude * 0.4f)
        corePaint.color = Color.argb(220, 255, 30, 30)
        canvas.drawCircle(cx, cy, coreSize, corePaint)
    }

    private fun drawArcSegments(
        canvas: Canvas, cx: Float, cy: Float,
        radius: Float, alpha: Int, segments: Int
    ) {
        val sweepAngle = 360f / segments * 0.6f
        val gap = 360f / segments - sweepAngle

        arcPaint.color = Color.argb(alpha, 0, 180, 255)
        for (i in 0 until segments) {
            val startAngle = i * (sweepAngle + gap) + pulsePhase * 50f
            canvas.drawArc(
                cx - radius, cy - radius, cx + radius, cy + radius,
                startAngle, sweepAngle, false, arcPaint
            )
        }
    }

    fun release() {
        pulseAnimator.cancel()
    }
}
