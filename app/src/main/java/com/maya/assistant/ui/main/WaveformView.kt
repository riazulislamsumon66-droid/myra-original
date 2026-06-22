package com.maya.assistant.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.*
import kotlin.random.Random

/**
 * WaveformView — real-time audio waveform for call assistant screen.
 * Shows animated bars that react to voice amplitude.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val BAR_COUNT = 32
    private val barHeights = FloatArray(BAR_COUNT) { 0.1f }
    private val targetHeights = FloatArray(BAR_COUNT) { 0.1f }

    private var isAnimating = false
    private var amplitude = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val idleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            updateIdleBars(it.animatedFraction)
            invalidate()
        }
    }

    private val activeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 80
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            smoothBars()
            invalidate()
        }
    }

    init {
        startIdle()
    }

    // ── Public API ────────────────────────────────────────────

    fun setAmplitude(amp: Float) {
        amplitude = amp.coerceIn(0f, 1f)
        if (!isAnimating) startActive()
        for (i in 0 until BAR_COUNT) {
            val noise = Random.nextFloat() * 0.3f
            targetHeights[i] = (amplitude * 0.7f + noise + 0.1f).coerceIn(0.05f, 1f)
        }
    }

    fun startIdle() {
        isAnimating = false
        activeAnimator.cancel()
        idleAnimator.start()
    }

    fun startActive() {
        isAnimating = true
        idleAnimator.cancel()
        activeAnimator.start()
    }

    fun stop() {
        idleAnimator.cancel()
        activeAnimator.cancel()
    }

    // ── Drawing ───────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return

        val barWidth = width.toFloat() / (BAR_COUNT * 1.6f)
        val gap = barWidth * 0.6f
        val totalWidth = BAR_COUNT * (barWidth + gap)
        val startX = (width - totalWidth) / 2f
        val centerY = height / 2f

        for (i in 0 until BAR_COUNT) {
            val barHeight = (barHeights[i] * height * 0.85f).coerceAtLeast(4f)
            val x = startX + i * (barWidth + gap)

            // Gradient color based on height
            val alpha = (150 + barHeights[i] * 100).toInt().coerceIn(100, 255)
            val color = when {
                barHeights[i] > 0.7f -> Color.argb(alpha, 224, 64, 251)   // purple — loud
                barHeights[i] > 0.4f -> Color.argb(alpha, 255, 90, 90)    // pink — mid
                else -> Color.argb(alpha, 120, 80, 200)                    // dim — quiet
            }
            paint.color = color

            val rect = RectF(
                x,
                centerY - barHeight / 2,
                x + barWidth,
                centerY + barHeight / 2
            )
            canvas.drawRoundRect(rect, barWidth / 2, barWidth / 2, paint)
        }
    }

    // ── Internal animation helpers ────────────────────────────

    private fun updateIdleBars(fraction: Float) {
        for (i in 0 until BAR_COUNT) {
            val phase = (i.toFloat() / BAR_COUNT) * 2 * PI.toFloat()
            barHeights[i] = 0.08f + 0.12f * abs(sin(fraction * 2 * PI.toFloat() + phase))
        }
    }

    private fun smoothBars() {
        for (i in 0 until BAR_COUNT) {
            barHeights[i] += (targetHeights[i] - barHeights[i]) * 0.4f
        }
    }

    override fun onDetachedFromWindow() {
        idleAnimator.cancel()
        activeAnimator.cancel()
        super.onDetachedFromWindow()
    }
}
