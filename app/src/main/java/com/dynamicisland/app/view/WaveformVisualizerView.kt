package com.dynamicisland.app.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

class WaveformVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var barCount: Int = 10
        set(value) {
            field = value.coerceIn(3, 24)
            bars = FloatArray(field) { 0.22f }
            targets = FloatArray(field) { 0.22f }
            invalidate()
        }

    private var bars = FloatArray(barCount) { 0.22f }
    private var targets = FloatArray(barCount) { 0.22f }
    private var isPlaying: Boolean = false
    private var primaryColor: Int = Color.parseColor("#A8C7FA")
    private var tertiaryColor: Int = Color.parseColor("#E7B9F5")
    private var phase: Float = 0f

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rectF = RectF()
    private var animator: ValueAnimator? = null

    init {
        setupAnimator()
    }

    private fun setupAnimator() {
        animator = ValueAnimator.ofFloat(0f, 100f).apply {
            duration = 2000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                tick()
            }
        }
    }

    fun setPlaying(playing: Boolean) {
        if (this.isPlaying != playing) {
            this.isPlaying = playing
            if (playing) {
                if (animator?.isStarted != true) animator?.start()
            } else {
                // Let it settle to rest
                if (animator?.isStarted != true) animator?.start()
            }
            invalidate()
        }
    }

    fun setColors(primary: Int, tertiary: Int = Color.parseColor("#E7B9F5")) {
        this.primaryColor = primary
        this.tertiaryColor = tertiary
        invalidate()
    }

    private fun tick() {
        phase += 0.18f
        var anyMoving = false
        for (i in 0 until barCount) {
            if (isPlaying) {
                if (Random.nextFloat() < 0.35f) {
                    val wave = 0.35f + 0.35f * sin(phase + i * 0.7f)
                    targets[i] = (0.2f + Random.nextFloat() * 0.55f + wave * 0.35f).coerceIn(0.18f, 1.0f)
                }
            } else {
                targets[i] = 0.20f
            }
            val diff = targets[i] - bars[i]
            if (abs(diff) > 0.01f) {
                bars[i] += diff * 0.28f
                anyMoving = true
            } else {
                bars[i] = targets[i]
            }
        }
        invalidate()
        if (!isPlaying && !anyMoving) {
            animator?.cancel()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density
        val bw = if (barCount <= 5) 3.2f * density else 2.6f * density
        val gap = if (barCount <= 5) 3.0f * density else 2.4f * density
        val totalW = barCount * bw + (barCount - 1) * gap
        val startX = max(0f, (w - totalW) / 2f)

        barPaint.shader = LinearGradient(
            startX, 0f, startX + totalW, h,
            primaryColor, tertiaryColor,
            Shader.TileMode.CLAMP
        )
        barPaint.alpha = if (isPlaying) 255 else 110

        val maxBarH = h * 0.86f
        val minBarH = 3.2f * density

        for (i in 0 until barCount) {
            val bh = max(minBarH, bars[i] * maxBarH)
            val left = startX + i * (bw + gap)
            val top = (h - bh) / 2f
            rectF.set(left, top, left + bw, top + bh)
            canvas.drawRoundRect(rectF, bw / 2f, bw / 2f, barPaint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
