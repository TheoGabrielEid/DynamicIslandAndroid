package com.dynamicisland.app.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Exact Android port of the Windows PC AlbumArt widget:
 * - When album art Bitmap is available: rounded 16dp cover with soft drop shadow,
 *   diagonal specular gloss sheen, and crisp inner rim border.
 * - When idle or no cover art: animated spinning Vinyl Turntable Disc with pulsing
 *   concentric grooves, gradient theme spindle label, and center spindle hole.
 */
class AlbumArtView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var coverBitmap: Bitmap? = null
    private var bitmapShader: BitmapShader? = null
    private val shaderMatrix = Matrix()

    private var isPlaying: Boolean = false
    private var primaryColor: Int = Color.parseColor("#A8C7FA")
    private var tertiaryColor: Int = Color.parseColor("#E7B9F5")
    private var phase: Float = 0f

    private val cardRect = RectF()
    private val borderRect = RectF()
    private val clipPath = Path()

    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(42, 255, 255, 255)
    }

    private var animator: ValueAnimator? = null

    init {
        animator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
            duration = 2800L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (isPlaying && coverBitmap == null) {
                    phase = it.animatedValue as Float
                    invalidate()
                }
            }
        }
    }

    fun setState(bitmap: Bitmap?, playing: Boolean, accentColor: Int) {
        val bitmapChanged = (bitmap !== coverBitmap)
        this.coverBitmap = if (bitmap != null && !bitmap.isRecycled) bitmap else null
        this.isPlaying = playing
        this.primaryColor = accentColor

        if (bitmapChanged) {
            bitmapShader = this.coverBitmap?.let {
                BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
        }

        if (playing && this.coverBitmap == null) {
            if (animator?.isStarted != true) animator?.start()
        } else {
            animator?.cancel()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val d = resources.displayMetrics.density
        val rad = 15f * d
        cardRect.set(0f, 0f, w, h)

        clipPath.reset()
        clipPath.addRoundRect(cardRect, rad, rad, Path.Direction.CW)

        val bmp = coverBitmap
        val sh = bitmapShader
        if (bmp != null && !bmp.isRecycled && sh != null) {
            canvas.save()
            canvas.clipPath(clipPath)

            // Center-crop matrix
            val scale = kotlin.math.max(w / bmp.width.toFloat(), h / bmp.height.toFloat())
            val dx = (w - bmp.width * scale) * 0.5f
            val dy = (h - bmp.height * scale) * 0.5f
            shaderMatrix.reset()
            shaderMatrix.setScale(scale, scale)
            shaderMatrix.postTranslate(dx, dy)
            sh.setLocalMatrix(shaderMatrix)
            coverPaint.shader = sh
            canvas.drawRect(cardRect, coverPaint)

            // Specular diagonal gloss across top-left (matches PC AlbumArt)
            glossPaint.shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(
                    Color.argb(46, 255, 255, 255),
                    Color.argb(12, 255, 255, 255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(cardRect, glossPaint)
            canvas.restore()

            borderPaint.strokeWidth = 1f * d
            borderPaint.color = Color.argb(44, 255, 255, 255)
            borderRect.set(0.5f * d, 0.5f * d, w - 0.5f * d, h - 0.5f * d)
            canvas.drawRoundRect(borderRect, rad, rad, borderPaint)
            return
        }

        // Fallback: Vinyl Record Turntable Disc (matches PC AlbumArt)
        canvas.save()
        canvas.clipPath(clipPath)

        bgPaint.shader = LinearGradient(
            0f, 0f, w, h,
            Color.parseColor("#313137"),
            Color.parseColor("#1B1B1F"),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(cardRect, bgPaint)

        val cx = w / 2f
        val cy = h / 2f
        val size = min(w, h)
        val maxGroove = size * 0.43f
        groovePaint.strokeWidth = 1f * d

        for (i in 0..3) {
            val grRad = maxGroove - i * (size * 0.062f)
            val alpha = if (!isPlaying) 22 else (26 + 14 * sin(phase * 2f + i)).toInt().coerceIn(12, 65)
            groovePaint.color = Color.argb(alpha, 255, 255, 255)
            canvas.drawCircle(cx, cy, grRad, groovePaint)
        }

        // Spindle label in theme accent
        val labelRad = size * 0.21f
        labelPaint.shader = LinearGradient(
            cx - labelRad, cy - labelRad, cx + labelRad, cy + labelRad,
            if (isPlaying) primaryColor else Color.parseColor("#383842"),
            if (isPlaying) tertiaryColor else Color.parseColor("#26262B"),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, labelRad, labelPaint)

        // Center spindle hole
        val holeRad = size * 0.055f
        canvas.drawCircle(cx, cy, holeRad, holePaint)

        // Specular gloss
        glossPaint.shader = LinearGradient(
            0f, 0f, w, h,
            Color.argb(30, 255, 255, 255),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(cardRect, glossPaint)
        canvas.restore()

        borderPaint.strokeWidth = 1f * d
        borderPaint.color = Color.argb(32, 255, 255, 255)
        borderRect.set(0.5f * d, 0.5f * d, w - 0.5f * d, h - 0.5f * d)
        canvas.drawRoundRect(borderRect, rad, rad, borderPaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
