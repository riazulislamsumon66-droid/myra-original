package com.maya.assistant.ui.character

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.*
import kotlin.math.*

/**
 * CharacterOverlayView — 3D-feel animated character.
 *
 * Rendering approach: Custom Canvas 3D-style drawing + Lottie JSON animation.
 * - Idle: soft breathing + floating bob
 * - Talking: mouth opens/closes with voice rhythm
 * - Listening: ears/eyes animated, leaning forward
 * - Sleeping: eyes close, zzz particles float
 * - Happy: jump + sparkle particles
 * - Thinking: eye movement, finger to chin pose
 *
 * For real 3D (GLB model): swap this with SceneView implementation
 * after adding: implementation("io.github.sceneview:sceneview:2.2.1")
 */
class CharacterOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Enums ─────────────────────────────────────────────────
    enum class CharState { IDLE, LISTENING, TALKING, SLEEPING, HAPPY, THINKING }
    enum class CharMode { DEFAULT, GF, PROFESSIONAL, FRIEND }

    // ── State ─────────────────────────────────────────────────
    private var state = CharState.IDLE
    private var mode = CharMode.DEFAULT
    private var amplitude = 0f

    // ── Animation values ──────────────────────────────────────
    private var breatheScale = 1f
    private var floatOffset = 0f
    private var mouthOpen = 0f
    private var eyeBlink = 1f      // 1 = open, 0 = closed
    private var blushAlpha = 0f
    private var zzzOffset = 0f
    private var jumpOffset = 0f
    private var thinkAngle = 0f
    private var sparkleAlpha = 0f
    private var leanAngle = 0f     // forward lean when listening
    private var eyeGaze = 0f       // side-eye for thinking

    // ── Animators ─────────────────────────────────────────────
    private val breatheAnim = ValueAnimator.ofFloat(1f, 1.04f, 1f).apply {
        duration = 3000; repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { breatheScale = it.animatedValue as Float; invalidate() }
    }

    private val floatAnim = ValueAnimator.ofFloat(-8f, 8f).apply {
        duration = 2200; repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { floatOffset = it.animatedValue as Float; invalidate() }
    }

    private val mouthAnim = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
        duration = 300; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { mouthOpen = it.animatedValue as Float; invalidate() }
    }

    private val blinkAnim = ValueAnimator.ofFloat(1f, 0f, 1f).apply {
        duration = 200; repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { eyeBlink = it.animatedValue as Float; invalidate() }
    }

    private val zzzAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2000; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { zzzOffset = it.animatedValue as Float; invalidate() }
    }

    private val jumpAnim = ValueAnimator.ofFloat(0f, -30f, 0f).apply {
        duration = 500; repeatCount = 3
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { jumpOffset = it.animatedValue as Float; invalidate() }
    }

    private val sparkleAnim = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
        duration = 800; repeatCount = 4
        addUpdateListener { sparkleAlpha = it.animatedValue as Float; invalidate() }
    }

    private val thinkAnim = ValueAnimator.ofFloat(-20f, 20f).apply {
        duration = 1500; repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { eyeGaze = it.animatedValue as Float; invalidate() }
    }

    private val leanAnim = ValueAnimator.ofFloat(0f, -8f).apply {
        duration = 500
        interpolator = DecelerateInterpolator()
        addUpdateListener { leanAngle = it.animatedValue as Float; invalidate() }
    }

    // ── Paints ────────────────────────────────────────────────
    private val skinPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clothPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 0, 0, 0)
        maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // for BlurMaskFilter
        applyModeColors()
        startIdleAnims()
    }

    // ── Public API ────────────────────────────────────────────

    fun setState(newState: CharState) {
        cancelAllAnims()
        state = newState
        when (newState) {
            CharState.IDLE -> {
                startIdleAnims()
            }
            CharState.LISTENING -> {
                startIdleAnims()
                leanAnim.start()
            }
            CharState.TALKING -> {
                startIdleAnims()
                mouthAnim.start()
            }
            CharState.SLEEPING -> {
                floatAnim.apply { duration = 4000 }.start()
                zzzAnim.start()
                // eyes will draw as closed in onDraw
            }
            CharState.HAPPY -> {
                startIdleAnims()
                jumpAnim.start()
                sparkleAnim.start()
                blushAlpha = 200f
                invalidate()
            }
            CharState.THINKING -> {
                startIdleAnims()
                thinkAnim.start()
            }
        }
    }

    fun setMode(newMode: CharMode) {
        mode = newMode
        applyModeColors()
        invalidate()
    }

    fun setAmplitude(amp: Float) {
        amplitude = amp.coerceIn(0f, 1f)
        if (state == CharState.TALKING) invalidate()
    }

    // ── Drawing ───────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f

        val totalOffset = floatOffset + jumpOffset

        canvas.save()
        canvas.translate(cx, cy + totalOffset)

        // Lean for listening
        if (leanAngle != 0f) canvas.rotate(leanAngle)

        // Breathing scale
        canvas.scale(breatheScale, breatheScale)

        val scale = minOf(width, height) / 180f

        // Drop shadow under feet
        canvas.drawOval(RectF(-30f * scale, 70f * scale, 30f * scale, 78f * scale), shadowPaint)

        // Draw body parts bottom-to-top for overlap
        drawBody(canvas, scale)
        drawArms(canvas, scale)
        drawHead(canvas, scale)
        drawHair(canvas, scale)
        drawFace(canvas, scale)

        // Sparkles for happy state
        if (state == CharState.HAPPY && sparkleAlpha > 0) {
            drawSparkles(canvas, scale)
        }

        // Zzz for sleeping state
        if (state == CharState.SLEEPING) {
            drawZzz(canvas, scale)
        }

        // Mode badge (small icon)
        drawModeBadge(canvas, scale)

        canvas.restore()
    }

    private fun drawBody(canvas: Canvas, s: Float) {
        // Torso
        val torsoRect = RectF(-22f * s, 18f * s, 22f * s, 65f * s)
        canvas.drawRoundRect(torsoRect, 10f * s, 10f * s, clothPaint)

        // Legs
        val legPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = clothPaint.color }
        canvas.drawRoundRect(RectF(-18f * s, 55f * s, -6f * s, 78f * s), 5f * s, 5f * s, legPaint)
        canvas.drawRoundRect(RectF(6f * s, 55f * s, 18f * s, 78f * s), 5f * s, 5f * s, legPaint)

        // Shoes
        val shoePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2C2C2C") }
        canvas.drawRoundRect(RectF(-20f * s, 72f * s, -4f * s, 80f * s), 4f * s, 4f * s, shoePaint)
        canvas.drawRoundRect(RectF(4f * s, 72f * s, 20f * s, 80f * s), 4f * s, 4f * s, shoePaint)
    }

    private fun drawArms(canvas: Canvas, s: Float) {
        val armPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = skinPaint.color }

        if (state == CharState.THINKING) {
            // Right arm up with finger to chin
            canvas.drawRoundRect(RectF(22f * s, 20f * s, 34f * s, 50f * s), 5f * s, 5f * s, armPaint)
            canvas.drawCircle(28f * s, 15f * s, 6f * s, armPaint)
        } else {
            // Normal arms down (slight sway with float)
            val armSway = sin(floatOffset / 8f) * 3f * s
            canvas.drawRoundRect(RectF(-32f * s, 20f * s + armSway, -22f * s, 55f * s), 5f * s, 5f * s, clothPaint)
            canvas.drawRoundRect(RectF(22f * s, 20f * s - armSway, 32f * s, 55f * s), 5f * s, 5f * s, clothPaint)
        }
    }

    private fun drawHead(canvas: Canvas, s: Float) {
        // Neck
        canvas.drawRoundRect(RectF(-8f * s, 10f * s, 8f * s, 22f * s), 4f * s, 4f * s, skinPaint)

        // Head — 3D sphere effect with gradient
        val headShader = RadialGradient(
            -5f * s, -15f * s, 28f * s,
            intArrayOf(Color.parseColor("#FFE0C8"), skinPaint.color),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        skinPaint.shader = headShader
        canvas.drawCircle(0f, 0f, 28f * s, skinPaint)
        skinPaint.shader = null
    }

    private fun drawHair(canvas: Canvas, s: Float) {
        // Top hair
        canvas.drawCircle(0f, -22f * s, 30f * s, hairPaint)
        // Hair sides
        canvas.drawCircle(-20f * s, -5f * s, 14f * s, hairPaint)
        canvas.drawCircle(20f * s, -5f * s, 14f * s, hairPaint)

        // Mode-based hair accessory
        when (mode) {
            CharMode.GF -> {
                // Hair bow (cute)
                val bowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF4FC3F7") }
                canvas.drawOval(RectF(-26f * s, -38f * s, -10f * s, -28f * s), bowPaint)
                canvas.drawOval(RectF(-6f * s, -38f * s, 10f * s, -28f * s), bowPaint)
                canvas.drawCircle(-8f * s, -33f * s, 4f * s, bowPaint)
            }
            CharMode.PROFESSIONAL -> {
                // Glasses
                val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#333333")
                    style = Paint.Style.STROKE
                    strokeWidth = 2f * s
                }
                canvas.drawCircle(-10f * s, 2f * s, 8f * s, glassPaint)
                canvas.drawCircle(10f * s, 2f * s, 8f * s, glassPaint)
                canvas.drawLine(-2f * s, 2f * s, 2f * s, 2f * s, glassPaint)
            }
            else -> {} // no accessory
        }
    }

    private fun drawFace(canvas: Canvas, s: Float) {
        drawEyes(canvas, s)
        drawMouth(canvas, s)
        drawBlush(canvas, s)
    }

    private fun drawEyes(canvas: Canvas, s: Float) {
        val eyePositions = listOf(-10f * s to 2f * s, 10f * s to 2f * s)

        eyePositions.forEach { (ex, ey) ->
            if (state == CharState.SLEEPING) {
                // Closed eyes — curved line
                val path = Path().apply {
                    moveTo(ex - 7f * s, ey)
                    quadTo(ex, ey + 5f * s, ex + 7f * s, ey)
                }
                val sleepEyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#333333")
                    style = Paint.Style.STROKE
                    strokeWidth = 2.5f * s
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawPath(path, sleepEyePaint)
            } else {
                // White of eye
                canvas.drawCircle(ex, ey, 8f * s * eyeBlink, eyePaint)

                // Pupil (shifts for thinking / listening)
                val gazeX = if (state == CharState.THINKING) eyeGaze * 0.3f * s else 0f
                val gazeY = if (state == CharState.LISTENING) -2f * s else 0f
                canvas.drawCircle(ex + gazeX, ey + gazeY, 5f * s * eyeBlink, pupilPaint)

                // Shine
                canvas.drawCircle(
                    ex + gazeX - 2f * s, ey + gazeY - 2f * s,
                    1.5f * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                )

                // Happy state — star eyes
                if (state == CharState.HAPPY) {
                    val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD700") }
                    drawStar(canvas, ex, ey, 5f * s, starPaint)
                }
            }
        }
    }

    private fun drawMouth(canvas: Canvas, s: Float) {
        val mouthY = 14f * s
        val mouthWidth = 10f * s

        mouthPaint.color = Color.parseColor("#C2185B")
        mouthPaint.strokeWidth = 2.5f * s
        mouthPaint.strokeCap = Paint.Cap.ROUND

        when (state) {
            CharState.TALKING -> {
                // Open mouth with amplitude
                val openAmt = (mouthOpen + amplitude * 0.5f).coerceIn(0f, 1f)
                val path = Path().apply {
                    moveTo(-mouthWidth, mouthY)
                    quadTo(0f, mouthY + 8f * s * (1 - openAmt), mouthWidth, mouthY)
                }
                mouthPaint.style = Paint.Style.STROKE
                canvas.drawPath(path, mouthPaint)
                if (openAmt > 0.3f) {
                    // Teeth
                    mouthPaint.style = Paint.Style.FILL
                    mouthPaint.color = Color.WHITE
                    canvas.drawRoundRect(
                        RectF(-6f * s, mouthY, 6f * s, mouthY + 4f * s * openAmt),
                        2f * s, 2f * s, mouthPaint
                    )
                }
            }
            CharState.HAPPY -> {
                // Big smile with teeth
                val smilePath = Path().apply {
                    moveTo(-mouthWidth, mouthY)
                    quadTo(0f, mouthY + 12f * s, mouthWidth, mouthY)
                }
                mouthPaint.style = Paint.Style.STROKE
                canvas.drawPath(smilePath, mouthPaint)
            }
            CharState.SLEEPING -> {
                // Small O mouth
                mouthPaint.style = Paint.Style.STROKE
                canvas.drawOval(RectF(-4f * s, mouthY, 4f * s, mouthY + 5f * s), mouthPaint)
            }
            CharState.THINKING -> {
                // Side smirk
                mouthPaint.style = Paint.Style.STROKE
                val smirkPath = Path().apply {
                    moveTo(-mouthWidth, mouthY)
                    quadTo(-2f * s, mouthY - 2f * s, mouthWidth * 0.5f, mouthY + 3f * s)
                }
                canvas.drawPath(smirkPath, mouthPaint)
            }
            else -> {
                // Neutral smile
                mouthPaint.style = Paint.Style.STROKE
                val defaultPath = Path().apply {
                    moveTo(-mouthWidth, mouthY)
                    quadTo(0f, mouthY + 6f * s, mouthWidth, mouthY)
                }
                canvas.drawPath(defaultPath, mouthPaint)
            }
        }
    }

    private fun drawBlush(canvas: Canvas, s: Float) {
        val alpha = when {
            state == CharState.HAPPY -> 180
            mode == CharMode.GF -> 100
            blushAlpha > 0 -> blushAlpha.toInt()
            else -> 0
        }
        if (alpha <= 0) return

        blushPaint.color = Color.argb(alpha, 255, 100, 130)
        canvas.drawOval(RectF(-22f * s, 8f * s, -10f * s, 14f * s), blushPaint)
        canvas.drawOval(RectF(10f * s, 8f * s, 22f * s, 14f * s), blushPaint)
    }

    private fun drawZzz(canvas: Canvas, s: Float) {
        textPaint.color = Color.argb(200, 180, 200, 255)
        val sizes = listOf(10f, 14f, 18f)
        sizes.forEachIndexed { i, size ->
            val progress = (zzzOffset + i * 0.33f) % 1f
            val alpha = (sin(progress * PI).toFloat() * 200).toInt().coerceIn(0, 200)
            val x = 32f * s + i * 8f * s
            val y = -28f * s - progress * 30f * s
            textPaint.textSize = size * s
            textPaint.alpha = alpha
            canvas.drawText("z", x, y, textPaint)
        }
        textPaint.alpha = 255
    }

    private fun drawSparkles(canvas: Canvas, s: Float) {
        val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((sparkleAlpha * 255).toInt(), 255, 215, 0)
        }
        val positions = listOf(
            -35f * s to -30f * s,
            35f * s to -30f * s,
            -40f * s to 10f * s,
            40f * s to 5f * s
        )
        positions.forEach { (x, y) ->
            drawStar(canvas, x, y, 5f * s * sparkleAlpha, sparkPaint)
        }
    }

    private fun drawModeBadge(canvas: Canvas, s: Float) {
        val label = when (mode) {
            CharMode.GF -> "💕"
            CharMode.PROFESSIONAL -> "💼"
            CharMode.FRIEND -> "😊"
            CharMode.DEFAULT -> return
        }
        textPaint.textSize = 12f * s
        canvas.drawText(label, 0f, -42f * s, textPaint)
    }

    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, r: Float, paint: Paint) {
        val path = Path()
        for (i in 0..9) {
            val radius = if (i % 2 == 0) r else r * 0.4f
            val angle = (i * 36 - 90) * PI / 180
            val x = cx + (radius * cos(angle)).toFloat()
            val y = cy + (radius * sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, paint)
    }

    // ── Colors by mode ────────────────────────────────────────

    private fun applyModeColors() {
        skinPaint.color = Color.parseColor("#FFDAB9")   // peach skin

        hairPaint.color = when (mode) {
            CharMode.GF -> Color.parseColor("#1A1A2E")          // dark + cute
            CharMode.PROFESSIONAL -> Color.parseColor("#2C1810") // dark brown
            CharMode.FRIEND -> Color.parseColor("#8B4513")       // warm brown
            CharMode.DEFAULT -> Color.parseColor("#1A1A2E")
        }

        clothPaint.color = when (mode) {
            CharMode.GF -> Color.parseColor("#FF4FC3F7")         // sky blue dress
            CharMode.PROFESSIONAL -> Color.parseColor("#1A237E") // navy suit
            CharMode.FRIEND -> Color.parseColor("#E91E63")       // casual pink
            CharMode.DEFAULT -> Color.parseColor("#6200EE")      // purple
        }

        eyePaint.color = Color.WHITE
        pupilPaint.color = when (mode) {
            CharMode.PROFESSIONAL -> Color.parseColor("#1A237E")
            else -> Color.parseColor("#1A1A2E")
        }
    }

    // ── Anim helpers ──────────────────────────────────────────

    private fun startIdleAnims() {
        breatheAnim.start()
        floatAnim.apply { duration = 2200 }.start()
        blinkAnim.start()
    }

    private fun cancelAllAnims() {
        breatheAnim.cancel()
        floatAnim.cancel()
        mouthAnim.cancel()
        blinkAnim.cancel()
        zzzAnim.cancel()
        jumpAnim.cancel()
        sparkleAnim.cancel()
        thinkAnim.cancel()
        leanAnim.cancel()
        blushAlpha = 0f
        jumpOffset = 0f
        leanAngle = 0f
        eyeGaze = 0f
    }

    override fun onDetachedFromWindow() {
        cancelAllAnims()
        super.onDetachedFromWindow()
    }
}
