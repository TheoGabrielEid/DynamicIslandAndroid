package com.dynamicisland.app.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import kotlin.math.min

/**
 * Custom M3 Expressive Capsule Container matching the Windows PC DynamicCapsule:
 * - Pure OLED black rounded capsule body
 * - Soft top hairline specular glint across the upper curve
 * - Dynamic Aura / Toast neon perimeter glow + radial top bloom
 */
class IslandContainerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    var cornerRadiusDp: Float = 28f
        set(value) {
            field = value
            invalidate()
        }

    var isCompactPill: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var progressFraction: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var accentColor: Int = Color.parseColor("#A8C7FA")
        set(value) {
            field = value
            invalidate()
        }

    private var auraAlpha: Float = 0f
    private var auraColor: Int = Color.parseColor("#A8C7FA")
    private var auraAnimator: ValueAnimator? = null

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }

    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val auraBloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val miniProgressBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(30, 255, 255, 255)
    }

    private val miniProgressFgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val rectF = RectF()
    private val insetRectF = RectF()
    private val barRectF = RectF()
    private val clipPath = Path()

    init {
        setWillNotDraw(false)
    }

    fun triggerAura(color: Int, durationMs: Long = 1600L) {
        this.auraColor = color
        auraAnimator?.cancel()
        auraAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                auraAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            super.onDraw(canvas)
            return
        }

        val d = resources.displayMetrics.density
        val rad = if (isCompactPill) h / 2f else min(h / 2f, cornerRadiusDp * d)

        rectF.set(0f, 0f, w, h)

        // 1. Main OLED Black Capsule Body
        canvas.drawRoundRect(rectF, rad, rad, bodyPaint)

        // 2. Soft Hairline Highlight Glint across top curve (like PC DynamicCapsule)
        clipPath.reset()
        clipPath.addRoundRect(rectF, rad, rad, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        glintPaint.shader = LinearGradient(
            w / 2f, 0f, w / 2f, 7f * d,
            Color.argb(42, 255, 255, 255),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, 7f * d, glintPaint)

        // 3. Radial Aura Bloom if active
        if (auraAlpha > 0.01f) {
            val bloomAlpha = (auraAlpha * 68).toInt().coerceIn(0, 255)
            val bloomCol = Color.argb(
                bloomAlpha,
                Color.red(auraColor),
                Color.green(auraColor),
                Color.blue(auraColor)
            )
            auraBloomPaint.shader = RadialGradient(
                w / 2f, 0f,
                (w * 0.65f).coerceAtLeast(10f),
                bloomCol,
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rectF, rad, rad, auraBloomPaint)
        }

        // 4. Compact Pill bottom mini progress line when music is playing
        if (isCompactPill && progressFraction > 0.01f) {
            val barPad = 22f * d
            val barW = w - barPad * 2f
            if (barW > 12f * d) {
                val barH = 1.8f * d
                val barY = h - 2.8f * d
                barRectF.set(barPad, barY, barPad + barW, barY + barH)
                canvas.drawRoundRect(barRectF, barH / 2f, barH / 2f, miniProgressBgPaint)

                miniProgressFgPaint.shader = LinearGradient(
                    barPad, 0f, barPad + barW, 0f,
                    accentColor,
                    Color.parseColor("#E7B9F5"),
                    Shader.TileMode.CLAMP
                )
                barRectF.set(barPad, barY, barPad + barW * progressFraction, barY + barH)
                canvas.drawRoundRect(barRectF, barH / 2f, barH / 2f, miniProgressFgPaint)
            }
        }

        canvas.restore()

        // 5. Perimeter Border (Aura neon ring or subtle M3 outline)
        if (auraAlpha > 0.01f) {
            val strokeW = 1.8f * d
            borderPaint.strokeWidth = strokeW
            val ringAlpha = (auraAlpha * 235).toInt().coerceIn(28, 255)
            borderPaint.color = Color.argb(
                ringAlpha,
                Color.red(auraColor),
                Color.green(auraColor),
                Color.blue(auraColor)
            )
            val inset = strokeW / 2f
            insetRectF.set(inset, inset, w - inset, h - inset)
            canvas.drawRoundRect(insetRectF, rad - inset, rad - inset, borderPaint)
        } else {
            val strokeW = 1f * d
            borderPaint.strokeWidth = strokeW
            borderPaint.color = Color.argb(34, 255, 255, 255)
            val inset = strokeW / 2f
            insetRectF.set(inset, inset, w - inset, h - inset)
            canvas.drawRoundRect(insetRectF, rad - inset, rad - inset, borderPaint)
        }

        super.onDraw(canvas)
    }

    override fun onDetachedFromWindow() {
        auraAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
