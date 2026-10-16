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

/**
 * Exact Android port of the Windows PC FocusRing widget:
 * - Smooth circular countdown arc with rounded caps
 * - Bold monospace MM:SS readout in the center
 * - Status sub-label ("FOCUSING", "PAUSED", or "READY") below the timer
 */
class FocusRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var targetFraction: Float = 1f
    private var displayFraction: Float = 1f
    private var timeText: String = "25:00"
    private var statusText: String = "READY"
    private var ringColor: Int = Color.parseColor("#E7B9F5")

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#26262B")
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E3E2E6")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8E9099")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val arcRect = RectF()
    private var animator: ValueAnimator? = null

    fun setState(
        fraction: Float,
        timeStr: String,
        statusStr: String,
        accentColor: Int
    ) {
        val clamped = fraction.coerceIn(0f, 1f)
        this.timeText = timeStr
        this.statusText = statusStr
        this.ringColor = accentColor

        if (kotlin.math.abs(clamped - targetFraction) > 0.002f) {
            val startVal = displayFraction
            targetFraction = clamped
            animator?.cancel()
            animator = ValueAnimator.ofFloat(startVal, clamped).apply {
                duration = 280L
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    displayFraction = anim.animatedValue as Float
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
        val strokeW = 7f * d
        bgPaint.strokeWidth = strokeW
        fgPaint.strokeWidth = strokeW
        fgPaint.color = ringColor

        glowPaint.strokeWidth = strokeW + 3.5f * d
        glowPaint.color = ringColor
        glowPaint.alpha = 50

        val size = min(w, h) - strokeW * 2.2f
        val cx = w / 2f
        val cy = h / 2f
        val r = size / 2f
        arcRect.set(cx - r, cy - r, cx + r, cy + r)

        // 1. Background ring track
        canvas.drawArc(arcRect, 0f, 360f, false, bgPaint)

        // 2. Active countdown arc
        val sweep = -displayFraction * 360f
        if (kotlin.math.abs(sweep) > 0.8f) {
            canvas.drawArc(arcRect, -90f, sweep, false, glowPaint)
            canvas.drawArc(arcRect, -90f, sweep, false, fgPaint)
        }

        // 3. Center Timer MM:SS
        timePaint.textSize = 18f * d
        canvas.drawText(timeText, cx, cy + 2f * d, timePaint)

        // 4. Status Label below
        statusPaint.textSize = 8.5f * d
        statusPaint.color = if (statusText == "FOCUSING") Color.parseColor("#7FD88F") else Color.parseColor("#8E9099")
        canvas.drawText(statusText, cx, cy + 16f * d, statusPaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
