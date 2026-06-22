package com.maya.assistant.ui.character

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.*
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.maya.assistant.R
import kotlin.math.*

/**
 * CharacterOverlayView — 2D animated character overlay.
 *
 * Supports three animation sources (in priority order):
 * 1. GIF (android.graphics.Movie) — for simple animated GIFs
 * 2. Lottie JSON — via LottieAnimationView (if lottie dependency added)
 * 3. Sprite Sheet — grid-based frame animation from a single bitmap
 * 4. Fallback: Custom Canvas drawing (original implementation)
 *
 * States: IDLE, LISTENING, TALKING, SLEEPING, HAPPY, THINKING
 */
class CharacterOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // ── Enums ─────────────────────────────────────────────────
    enum class CharState { IDLE, LISTENING, TALKING, SLEEPING, HAPPY, THINKING }
    enum class CharMode { DEFAULT, GF, PROFESSIONAL, FRIEND }

    // ── Animation Source Type ─────────────────────────────────
    enum class SourceType { GIF, LOTTIE, SPRITE_SHEET, CANVAS }

    // ── State ─────────────────────────────────────────────────
    private var state = CharState.IDLE
    private var mode = CharMode.DEFAULT
    private var amplitude = 0f
    private var sourceType = SourceType.CANVAS

    // ── Image Views for asset-based rendering ──────────────────
    private val characterImageView: ImageView
    private val fallbackCanvasView: CharacterCanvasView

    // ── Sprite Sheet ──────────────────────────────────────────
    private var spriteSheet: Bitmap? = null
    private var spriteRows = 1
    private var spriteCols = 1
    private var currentFrame = 0
    private var frameCount = 1

    // ── GIF ───────────────────────────────────────────────────
    private var gifMovie: android.graphics.Movie? = null
    private var gifStartTime = 0L

    // ── Resize ────────────────────────────────────────────────
    private var baseWidth = 160
    private var baseHeight = 200
    private var currentScale = 1f

    init {
        // Character image view (for GIF/Sprite/Lottie)
        characterImageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(characterImageView)

        // Fallback canvas view (original drawing)
        fallbackCanvasView = CharacterCanvasView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            visibility = GONE
        }
        addView(fallbackCanvasView)

        setBackgroundColor(Color.TRANSPARENT)
        applyModeColors()
    }

    // ── Public API ────────────────────────────────────────────

    fun setState(newState: CharState) {
        if (state == newState) return
        state = newState
        when (sourceType) {
            SourceType.CANVAS -> fallbackCanvasView.setState(CharacterCanvasView.CharState.valueOf(newState.name))
            SourceType.SPRITE_SHEET -> updateSpriteState(newState)
            SourceType.GIF -> updateGifState(newState)
            SourceType.LOTTIE -> updateLottieState(newState)
        }
    }

    fun setMode(newMode: CharMode) {
        mode = newMode
        applyModeColors()
        fallbackCanvasView.setMode(CharacterCanvasView.CharMode.valueOf(newMode.name))
    }

    fun setAmplitude(amp: Float) {
        amplitude = amp.coerceIn(0f, 1f)
        fallbackCanvasView.setAmplitude(amp)
    }

    fun setScale(scale: Float) {
        currentScale = scale.coerceIn(0.5f, 3f)
        val w = (baseWidth * currentScale).toInt()
        val h = (baseHeight * currentScale).toInt()
        layoutParams = layoutParams.apply {
            width = w
            height = h
        }
        characterImageView.layoutParams = LayoutParams(w, h)
        fallbackCanvasView.layoutParams = LayoutParams(w, h)
    }

    fun getScale(): Float = currentScale

    // ── Asset Loading ─────────────────────────────────────────

    /**
     * Load character from GIF file path
     */
    fun loadGif(assetPath: String) {
        try {
            sourceType = SourceType.GIF
            val inputStream = context.assets.open(assetPath)
            gifMovie = android.graphics.Movie.decodeStream(inputStream)
            inputStream.close()
            characterImageView.visibility = VISIBLE
            fallbackCanvasView.visibility = GONE
            startGifAnimation()
        } catch (e: Exception) {
            // Fallback to canvas
            sourceType = SourceType.CANVAS
            characterImageView.visibility = GONE
            fallbackCanvasView.visibility = VISIBLE
        }
    }

    /**
     * Load character from sprite sheet bitmap
     * @param bitmap The sprite sheet image
     * @param rows Number of rows (states)
     * @param cols Number of columns (frames per state)
     */
    fun loadSpriteSheet(bitmap: Bitmap, rows: Int, cols: Int) {
        sourceType = SourceType.SPRITE_SHEET
        spriteSheet = bitmap
        spriteRows = rows
        spriteCols = cols
        frameCount = cols
        characterImageView.visibility = VISIBLE
        fallbackCanvasView.visibility = GONE
        updateSpriteState(state)
    }

    /**
     * Load character from drawable resource (static image per state)
     */
    fun loadDrawableForState(state: CharState, resId: Int) {
        // Store drawable for state, use when state changes
        stateDrawables[state] = ContextCompat.getDrawable(context, resId)
        if (this.state == state) {
            characterImageView.setImageDrawable(stateDrawables[state])
        }
    }

    private val stateDrawables = mutableMapOf<CharState, Drawable?>()

    /**
     * Switch to canvas fallback mode
     */
    fun useCanvasFallback() {
        sourceType = SourceType.CANVAS
        characterImageView.visibility = GONE
        fallbackCanvasView.visibility = VISIBLE
        fallbackCanvasView.setState(CharacterCanvasView.CharState.valueOf(state.name))
    }

    // ── GIF Animation ─────────────────────────────────────────

    private var gifAnimRunnable: Runnable? = null

    private fun startGifAnimation() {
        gifStartTime = System.currentTimeMillis()
        gifAnimRunnable = object : Runnable {
            override fun run() {
                val movie = gifMovie ?: return
                val now = System.currentTimeMillis()
                val duration = movie.duration().coerceAtLeast(100)
                val frameTime = ((now - gifStartTime) % duration).toInt()
                movie.setTime(frameTime)
                // Draw current frame
                val bitmap = Bitmap.createBitmap(
                    movie.width().coerceAtLeast(1),
                    movie.height().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bitmap)
                movie.draw(canvas, 0f, 0f)
                characterImageView.setImageBitmap(bitmap)
                postDelayed(this, 50) // ~20fps
            }
        }
        post(gifAnimRunnable!!)
    }

    private fun updateGifState(newState: CharState) {
        // For GIF: load different GIF file per state
        // e.g., "character/idle.gif", "character/listening.gif"
        stopGifAnimation()
        startGifAnimation()
    }

    private fun stopGifAnimation() {
        gifAnimRunnable?.let { removeCallbacks(it) }
        gifAnimRunnable = null
    }

    // ── Sprite Sheet Animation ────────────────────────────────

    private fun updateSpriteState(newState: CharState) {
        val sheet = spriteSheet ?: return
        val stateRow = when (newState) {
            CharState.IDLE -> 0
            CharState.LISTENING -> 1
            CharState.TALKING -> 2
            CharState.SLEEPING -> if (spriteRows > 3) 3 else 0
            CharState.HAPPY -> if (spriteRows > 4) 4 else 0
            CharState.THINKING -> if (spriteRows > 5) 5 else 0
        }.coerceAtMost(spriteRows - 1)

        val frameWidth = sheet.width / spriteCols
        val frameHeight = sheet.height / spriteRows

        // Show first frame of the state row
        val x = 0
        val y = stateRow * frameHeight
        val frameBitmap = Bitmap.createBitmap(sheet, x, y, frameWidth, frameHeight)
        characterImageView.setImageBitmap(frameBitmap)

        // Animate through columns
        startSpriteAnimation(stateRow, frameWidth, frameHeight)
    }

    private var spriteAnimRunnable: Runnable? = null

    private fun startSpriteAnimation(row: Int, frameWidth: Int, frameHeight: Int) {
        spriteAnimRunnable?.let { removeCallbacks(it) }
        currentFrame = 0
        spriteAnimRunnable = object : Runnable {
            override fun run() {
                val sheet = spriteSheet ?: return
                val x = currentFrame * frameWidth
                val y = row * frameHeight
                if (x + frameWidth <= sheet.width && y + frameHeight <= sheet.height) {
                    val frameBitmap = Bitmap.createBitmap(sheet, x, y, frameWidth, frameHeight)
                    characterImageView.setImageBitmap(frameBitmap)
                }
                currentFrame = (currentFrame + 1) % spriteCols
                postDelayed(this, 100) // 10fps
            }
        }
        post(spriteAnimRunnable!!)
    }

    // ── Lottie (placeholder — requires lottie dependency) ────

    private fun updateLottieState(newState: CharState) {
        // If lottie is added: LottieAnimationView.setAnimation("idle.json")
        // For now, fallback to canvas
        useCanvasFallback()
    }

    // ── Mode Colors ───────────────────────────────────────────

    private fun applyModeColors() {
        // Mode-based visual adjustments
        when (mode) {
            CharMode.GF -> { /* Cute mode */ }
            CharMode.PROFESSIONAL -> { /* Professional mode */ }
            CharMode.FRIEND -> { /* Friendly mode */ }
            CharMode.DEFAULT -> { /* Default */ }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopGifAnimation()
        spriteAnimRunnable?.let { removeCallbacks(it) }
    }
}

// ═══════════════════════════════════════════════════════════════
// CharacterCanvasView — Original Canvas-based drawing (fallback)
// ═══════════════════════════════════════════════════════════════

class CharacterCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class CharState { IDLE, LISTENING, TALKING, SLEEPING, HAPPY, THINKING }
    enum class CharMode { DEFAULT, GF, PROFESSIONAL, FRIEND }

    private var state = CharState.IDLE
    private var mode = CharMode.DEFAULT
    private var amplitude = 0f

    // Animation values
    private var breatheScale = 1f
    private var floatOffset = 0f
    private var mouthOpen = 0f
    private var eyeBlink = 1f
    private var blushAlpha = 0f
    private var zzzOffset = 0f
    private var jumpOffset = 0f
    private var thinkAngle = 0f
    private var sparkleAlpha = 0f
    private var leanAngle = 0f
    private var eyeGaze = 0f

    // Animators
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
        duration = 4000; repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            val f = it.animatedFraction
            eyeBlink = if (f < 0.15f) it.animatedValue as Float else 1f
            invalidate()
        }
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

    // Paints
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
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        applyModeColors()
        startIdleAnims()
    }

    fun setState(newState: CharState) {
        cancelAllAnims()
        state = newState
        when (newState) {
            CharState.IDLE -> startIdleAnims()
            CharState.LISTENING -> { startIdleAnims(); leanAnim.start() }
            CharState.TALKING -> { startIdleAnims(); mouthAnim.start() }
            CharState.SLEEPING -> {
                floatAnim.apply { duration = 4000 }.start()
                zzzAnim.start()
            }
            CharState.HAPPY -> {
                startIdleAnims(); jumpAnim.start(); sparkleAnim.start()
                blushAlpha = 200f; invalidate()
            }
            CharState.THINKING -> { startIdleAnims(); thinkAnim.start() }
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

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val totalOffset = floatOffset + jumpOffset

        canvas.save()
        canvas.translate(cx, cy + totalOffset)
        if (leanAngle != 0f) canvas.rotate(leanAngle)
        canvas.scale(breatheScale, breatheScale)

        val scale = minOf(width, height) / 180f

        canvas.drawOval(RectF(-30f * scale, 70f * scale, 30f * scale, 78f * scale), shadowPaint)
        drawBody(canvas, scale)
        drawArms(canvas, scale)
        drawHead(canvas, scale)
        drawHair(canvas, scale)
        drawFace(canvas, scale)

        if (state == CharState.HAPPY && sparkleAlpha > 0) drawSparkles(canvas, scale)
        if (state == CharState.SLEEPING) drawZzz(canvas, scale)
        drawModeBadge(canvas, scale)

        canvas.restore()
    }

    private fun drawBody(canvas: Canvas, s: Float) {
        val torsoRect = RectF(-22f * s, 18f * s, 22f * s, 65f * s)
        canvas.drawRoundRect(torsoRect, 10f * s, 10f * s, clothPaint)
        val legPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = clothPaint.color }
        canvas.drawRoundRect(RectF(-18f * s, 55f * s, -6f * s, 78f * s), 5f * s, 5f * s, legPaint)
        canvas.drawRoundRect(RectF(6f * s, 55f * s, 18f * s, 78f * s), 5f * s, 5f * s, legPaint)
        val shoePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2C2C2C") }
        canvas.drawRoundRect(RectF(-20f * s, 72f * s, -4f * s, 80f * s), 4f * s, 4f * s, shoePaint)
        canvas.drawRoundRect(RectF(4f * s, 72f * s, 20f * s, 80f * s), 4f * s, 4f * s, shoePaint)
    }

    private fun drawArms(canvas: Canvas, s: Float) {
        val armPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = skinPaint.color }
        if (state == CharState.THINKING) {
            canvas.drawRoundRect(RectF(22f * s, 20f * s, 34f * s, 50f * s), 5f * s, 5f * s, armPaint)
            canvas.drawCircle(28f * s, 15f * s, 6f * s, armPaint)
        } else {
            val armSway = sin(floatOffset / 8f) * 3f * s
            canvas.drawRoundRect(RectF(-32f * s, 20f * s + armSway, -22f * s, 55f * s), 5f * s, 5f * s, clothPaint)
            canvas.drawRoundRect(RectF(22f * s, 20f * s - armSway, 32f * s, 55f * s), 5f * s, 5f * s, clothPaint)
        }
    }

    private fun drawHead(canvas: Canvas, s: Float) {
        canvas.drawRoundRect(RectF(-8f * s, 10f * s, 8f * s, 22f * s), 4f * s, 4f * s, skinPaint)
        val headShader = RadialGradient(
            -5f * s, -15f * s, 28f * s,
            intArrayOf(Color.parseColor("#FFE0C8"), skinPaint.color),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        skinPaint.shader = headShader
        canvas.drawCircle(0f, 0f, 28f * s, skinPaint)
        skinPaint.shader = null
    }

    private fun drawHair(canvas: Canvas, s: Float) {
        canvas.drawCircle(0f, -22f * s, 30f * s, hairPaint)
        canvas.drawCircle(-20f * s, -5f * s, 14f * s, hairPaint)
        canvas.drawCircle(20f * s, -5f * s, 14f * s, hairPaint)
        when (mode) {
            CharMode.GF -> {
                val bowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF4FC3F7") }
                canvas.drawOval(RectF(-26f * s, -38f * s, -10f * s, -28f * s), bowPaint)
                canvas.drawOval(RectF(-6f * s, -38f * s, 10f * s, -28f * s), bowPaint)
                canvas.drawCircle(-8f * s, -33f * s, 4f * s, bowPaint)
            }
            CharMode.PROFESSIONAL -> {
                val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#333333"); style = Paint.Style.STROKE; strokeWidth = 2f * s
                }
                canvas.drawCircle(-10f * s, 2f * s, 8f * s, glassPaint)
                canvas.drawCircle(10f * s, 2f * s, 8f * s, glassPaint)
                canvas.drawLine(-2f * s, 2f * s, 2f * s, 2f * s, glassPaint)
            }
            else -> {}
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
                val path = Path().apply {
                    moveTo(ex - 7f * s, ey)
                    quadTo(ex, ey + 5f * s, ex + 7f * s, ey)
                }
                val sleepEyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#333333"); style = Paint.Style.STROKE
                    strokeWidth = 2.5f * s; strokeCap = Paint.Cap.ROUND
                }
                canvas.drawPath(path, sleepEyePaint)
            } else {
                canvas.drawCircle(ex, ey, 8f * s * eyeBlink, eyePaint)
                val gazeX = if (state == CharState.THINKING) eyeGaze * 0.3f * s else 0f
                val gazeY = if (state == CharState.LISTENING) -2f * s else 0f
                canvas.drawCircle(ex + gazeX, ey + gazeY, 5f * s * eyeBlink, pupilPaint)
                canvas.drawCircle(ex + gazeX - 2f * s, ey + gazeY - 2f * s, 1.5f * s,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
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
                val openAmt = (mouthOpen + amplitude * 0.5f).coerceIn(0f, 1f)
                val path = Path().apply {
                    moveTo(-mouthWidth, mouthY)
                    quadTo(0f, mouthY + 8f * s * (1 - openAmt), mouthWidth, mouthY)
                }
                mouthPaint.style = Paint.Style.STROKE
                canvas.drawPath(path, mouthPaint)
            }
            CharState.HAPPY -> {
                mouthPaint.style = Paint.Style.STROKE
                val smilePath = Path().apply {
                    moveTo(-mouthWidth, mouthY)
                    quadTo(0f, mouthY + 12f * s, mouthWidth, mouthY)
                }
                canvas.drawPath(smilePath, mouthPaint)
            }
            CharState.SLEEPING -> {
                mouthPaint.style = Paint.Style.STROKE
                canvas.drawOval(RectF(-4f * s, mouthY, 4f * s, mouthY + 5f * s), mouthPaint)
            }
            CharState.THINKING -> {
                mouthPaint.style = Paint.Style.STROKE
                val smirkPath = Path().apply {
                    moveTo(-mouthWidth, mouthY)
                    quadTo(-2f * s, mouthY - 2f * s, mouthWidth * 0.5f, mouthY + 3f * s)
                }
                canvas.drawPath(smirkPath, mouthPaint)
            }
            else -> {
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
        val positions = listOf(-35f * s to -30f * s, 35f * s to -30f * s, -40f * s to 10f * s, 40f * s to 5f * s)
        positions.forEach { (x, y) -> drawStar(canvas, x, y, 5f * s * sparkleAlpha, sparkPaint) }
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

    private fun applyModeColors() {
        skinPaint.color = Color.parseColor("#FFDAB9")
        hairPaint.color = when (mode) {
            CharMode.GF -> Color.parseColor("#1A1A2E")
            CharMode.PROFESSIONAL -> Color.parseColor("#2C1810")
            CharMode.FRIEND -> Color.parseColor("#8B4513")
            CharMode.DEFAULT -> Color.parseColor("#1A1A2E")
        }
        clothPaint.color = when (mode) {
            CharMode.GF -> Color.parseColor("#FF4FC3F7")
            CharMode.PROFESSIONAL -> Color.parseColor("#37474F")
            CharMode.FRIEND -> Color.parseColor("#FF7043")
            CharMode.DEFAULT -> Color.parseColor("#5C6BC0")
        }
        eyePaint.color = Color.WHITE
        pupilPaint.color = Color.parseColor("#1A1A2E")
    }

    private fun startIdleAnims() {
        breatheAnim.start()
        floatAnim.start()
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
    }
}
