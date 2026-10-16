package com.dynamicisland.app.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

class RingGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var targetPercent: Int = 0
    private var animatedPercent: Float = 0f
    private var label: String = "CPU"
    private var subtext: String = "--"
    private var customValueText: String? = null
    private var ringColor: Int = Color.parseColor("#A8C7FA")

    private val bgArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(26, 255, 255, 255)
    }

    private val glowArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val fgArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E3E2E6")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E3E2E6")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8E9099")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val arcRect = RectF()
    private var animator: ValueAnimator? = null

    fun setData(
        percent: Int,
        label: String,
        subtext: String,
        color: Int,
        valueText: String? = null
    ) {
        val clamped = percent.coerceIn(0, 100)
        this.label = label
        this.subtext = subtext
        this.ringColor = color
        this.customValueText = valueText

        if (clamped != targetPercent) {
            val startVal = animatedPercent
            targetPercent = clamped
            animator?.cancel()
            animator = ValueAnimator.ofFloat(startVal, clamped.toFloat()).apply {
                duration = 380L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    animatedPercent = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val d = resources.displayMetrics.density
        val strokeW = 4.8f * d
        bgArcPaint.strokeWidth = strokeW
        fgArcPaint.strokeWidth = strokeW
        fgArcPaint.color = ringColor

        glowArcPaint.strokeWidth = strokeW + 2.8f * d
        glowArcPaint.color = ringColor
        glowArcPaint.alpha = 46

        val ringSize = min(w * 0.76f, h * 0.58f)
        val cx = w / 2f
        val cy = ringSize / 2f + 4f * d
        val r = (ringSize - strokeW * 2f) / 2f

        arcRect.set(cx - r, cy - r, cx + r, cy + r)

        // 1. Background circle
        canvas.drawArc(arcRect, 0f, 360f, false, bgArcPaint)

        // 2. Active arc starting from top (-90 deg)
        val sweep = (animatedPercent / 100f) * 360f
        if (sweep > 0.8f) {
            canvas.drawArc(arcRect, -90f, sweep, false, glowArcPaint)
            canvas.drawArc(arcRect, -90f, sweep, false, fgArcPaint)
        }

        // 3. Value inside ring center
        val displayVal = customValueText ?: "${animatedPercent.toInt()}%"
        valuePaint.textSize = if (displayVal.length > 4) 9.8f * d else 11.2f * d
        val textOffset = (valuePaint.descent() + valuePaint.ascent()) / 2f
        canvas.drawText(displayVal, cx, cy - textOffset, valuePaint)

        // 4. Label below ring
        labelPaint.textSize = 9.8f * d
        val labelY = cy + r + 13f * d
        canvas.drawText(label, cx, labelY, labelPaint)

        // 5. Subtitle below label
        subPaint.textSize = 8.5f * d
        canvas.drawText(subtext, cx, labelY + 10.5f * d, subPaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
