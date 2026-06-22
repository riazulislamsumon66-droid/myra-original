package com.maya.assistant.ui.character

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.*
import kotlin.math.min

class Live2DCharacterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class MotionType { IDLE, TAP, FLIC }

    private var currentMotion = MotionType.IDLE
    private var amplitude = 0f
    private var isVisible = true
    private var breatheScale = 1f
    private var floatOffset = 0f
    private var blinkValue = 1f
    private var mouthOpen = 0f

    private val breatheAnim = android.animation.ValueAnimator.ofFloat(1f, 1.03f, 1f).apply {
        duration = 3000; repeatCount = android.animation.ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { breatheScale = it.animatedValue as Float; invalidate() }
    }

    private val floatAnim = android.animation.ValueAnimator.ofFloat(-6f, 6f).apply {
        duration = 2500; repeatCount = android.animation.ValueAnimator.INFINITE
        repeatMode = android.animation.ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { floatOffset = it.animatedValue as Float; invalidate() }
    }

    private val blinkAnim = android.animation.ValueAnimator.ofFloat(1f, 0f, 1f).apply {
        duration = 4000; repeatCount = android.animation.ValueAnimator.INFINITE
        repeatMode = android.animation.ValueAnimator.RESTART
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            val f = it.animatedFraction
            blinkValue = if (f < 0.1f) it.animatedValue as Float else 1f
            invalidate()
        }
    }

    private val mouthAnim = android.animation.ValueAnimator.ofFloat(0f, 1f, 0f).apply {
        duration = 300; repeatCount = android.animation.ValueAnimator.INFINITE
        addUpdateListener { mouthOpen = it.animatedValue as Float; invalidate() }
    }

    private val skinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFDAB9") }
    private val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A1A2E") }
    private val clothPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF4FC3F7") }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A1A2E") }
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C2185B"); style = Paint.Style.STROKE; strokeWidth = 3f; strokeCap = Paint.Cap.ROUND
    }
    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 255, 100, 130) }

    init {
        breatheAnim.start()
        floatAnim.start()
        blinkAnim.start()
    }

    fun loadModel(model3JsonPath: String) {
        invalidate()
    }

    fun playMotion(type: MotionType) {
        currentMotion = type
        if (type == MotionType.TAP) {
            mouthAnim.start()
        } else {
            mouthAnim.cancel()
            mouthOpen = 0f
        }
        invalidate()
    }

    fun setLipSync(value: Float) {
        amplitude = value.coerceIn(0f, 1f)
        if (value > 0.1f) mouthAnim.start() else mouthAnim.cancel()
        invalidate()
    }

    fun setVisible(visible: Boolean) {
        isVisible = visible
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!isVisible) return
        val cx = width / 2f
        val cy = height / 2f
        val s = min(width, height) / 200f
        canvas.save()
        canvas.translate(cx, cy + floatOffset)
        canvas.scale(breatheScale, breatheScale)
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(40, 0, 0, 0)
            maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawOval(RectF(-25f * s, 55f * s, 25f * s, 65f * s), shadowPaint)
        canvas.drawRoundRect(RectF(-18f * s, 15f * s, 18f * s, 55f * s, 8f * s, 8f * s, clothPaint)
        val legPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        legPaint.color = clothPaint.color
        canvas.drawRoundRect(RectF(-15f * s, 48f * s, -5f * s, 65f * s, 4f * s, 4f * s, legPaint)
        canvas.drawRoundRect(RectF(5f * s, 48f * s, 15f * s, 65f * s, 4f * s, 4f * s, legPaint)
        val armPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        armPaint.color = skinPaint.color
        canvas.drawRoundRect(RectF(-28f * s, 18f * s, -18f * s, 45f * s, 5f * s, 5f * s, armPaint)
        canvas.drawRoundRect(RectF(18f * s, 18f * s, 28f * s, 45f * s, 5f * s, 5f * s, armPaint)
        canvas.drawRoundRect(RectF(-6f * s, 8f * s, 6f * s, 18f * s, 3f * s, 3f * s, skinPaint)
        val headShader = RadialGradient(-3f * s, -10f * s, 25f * s, intArrayOf(Color.parseColor("#FFE0C8"), skinPaint.color), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        skinPaint.shader = headShader
        canvas.drawCircle(0f, -15f * s, 25f * s, skinPaint)
        skinPaint.shader = null
        canvas.drawCircle(0f, -28f * s, 27f * s, hairPaint)
        canvas.drawCircle(-18f * s, -8f * s, 12f * s, hairPaint)
        canvas.drawCircle(18f * s, -8f * s, 12f * s, hairPaint)
        val bowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF4FC3F7") }
        canvas.drawOval(RectF(-22f * s, -38f * s, -10f * s, -30f * s), bowPaint)
        canvas.drawOval(RectF(-8f * s, -38f * s, 4f * s, -30f * s), bowPaint)
        canvas.drawCircle(-9f * s, -34f * s, 3f * s, bowPaint)
        canvas.drawOval(RectF(-18f * s, -5f * s, -8f * s, 0f * s), blushPaint)
        canvas.drawOval(RectF(8f * s, -5f * s, 18f * s, 0f * s), blushPaint)
        val eyeY = -18f * s
        val eyeSpacing = 9f * s
        val eyeRadius = 5f * s
        val pupilRadius = 2.5f * s
        canvas.drawCircle(-eyeSpacing, eyeY, eyeRadius * blinkValue, eyePaint)
        if (blinkValue > 0.3f) {
            canvas.drawCircle(-eyeSpacing, eyeY, pupilRadius, pupilPaint)
            val shine = Paint(Paint.ANTI_ALIAS_FLAG)
            shine.color = Color.WHITE
            canvas.drawCircle(-eyeSpacing - 1.5f * s, eyeY - 1.5f * s, 1f * s, shine)
        }
        canvas.drawCircle(eyeSpacing, eyeY, eyeRadius * blinkValue, eyePaint)
        if (blinkValue > 0.3f) {
            canvas.drawCircle(eyeSpacing, eyeY, pupilRadius, pupilPaint)
            val shine2 = Paint(Paint.ANTI_ALIAS_FLAG)
            shine2.color = Color.WHITE
            canvas.drawCircle(eyeSpacing - 1.5f * s, eyeY - 1.5f * s, 1f * s, shine2)
        }
        val mouthY = -5f * s
        val mouthWidth = 6f * s
        val openAmt = (mouthOpen + amplitude * 0.5f).coerceIn(0f, 1f)
        if (openAmt > 0.1f) {
            val path = Path().apply {
                moveTo(-mouthWidth, mouthY)
                quadTo(0f, mouthY + 5f * s * openAmt, mouthWidth, mouthY)
            }
            mouthPaint.style = Paint.Style.STROKE
            canvas.drawPath(path, mouthPaint)
        } else {
            val path = Path().apply {
                moveTo(-mouthWidth, mouthY)
                quadTo(0f, mouthY + 3f * s, mouthWidth, mouthY)
            }
            mouthPaint.style = Paint.Style.STROKE
            canvas.drawPath(path, mouthPaint)
        }
        canvas.restore()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        breatheAnim.cancel()
        floatAnim.cancel()
        blinkAnim.cancel()
        mouthAnim.cancel()
    }
}
