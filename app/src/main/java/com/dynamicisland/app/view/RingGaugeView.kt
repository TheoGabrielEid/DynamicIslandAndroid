package com.dynamicisland.app.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class RingGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var percent: Int = 0
    private var label: String = "CPU"
    private var subtext: String = "--"
    private var ringColor: Int = Color.parseColor("#A8C7FA")

    private val bgArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(32, 255, 255, 255)
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
    }

    private val arcRect = RectF()

    fun setData(percent: Int, label: String, subtext: String, color: Int) {
        this.percent = percent.coerceIn(0, 100)
        this.label = label
        this.subtext = subtext
        this.ringColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val d = resources.displayMetrics.density
        val strokeW = 5.5f * d
        bgArcPaint.strokeWidth = strokeW
        fgArcPaint.strokeWidth = strokeW
        fgArcPaint.color = ringColor

        glowArcPaint.strokeWidth = strokeW + 3f * d
        glowArcPaint.color = ringColor
        glowArcPaint.alpha = 50

        val ringSize = min(w * 0.72f, h * 0.60f)
        val cx = w / 2f
        val cy = ringSize / 2f + 6f * d
        val r = (ringSize - strokeW * 2f) / 2f

        arcRect.set(cx - r, cy - r, cx + r, cy + r)

        // Draw background circle
        canvas.drawArc(arcRect, 0f, 360f, false, bgArcPaint)

        // Draw active arc starting from top (-90 deg)
        val sweep = (percent / 100f) * 360f
        if (sweep > 1f) {
            canvas.drawArc(arcRect, -90f, sweep, false, glowArcPaint)
            canvas.drawArc(arcRect, -90f, sweep, false, fgArcPaint)
        }

        // Draw percentage in ring center
        valuePaint.textSize = 12.5f * d
        val textOffset = (valuePaint.descent() + valuePaint.ascent()) / 2f
        canvas.drawText("$percent%", cx, cy - textOffset, valuePaint)

        // Draw label below ring
        labelPaint.textSize = 11f * d
        val labelY = cy + r + 15f * d
        canvas.drawText(label, cx, labelY, labelPaint)

        // Draw subtext below label
        subPaint.textSize = 9.5f * d
        canvas.drawText(subtext, cx, labelY + 12f * d, subPaint)
    }
}
