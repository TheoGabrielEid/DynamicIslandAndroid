package com.dynamicisland.app.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class WavyProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onSeekListener: ((Float) -> Unit)? = null

    private var durationMs: Long = 0
    private var basePositionMs: Long = 0
    private var baseTimeMs: Long = SystemClock.elapsedRealtime()
    private var isPlaying: Boolean = false
    private var isDragging: Boolean = false
    private var dragFraction: Float = 0f

    private var wavePhase: Float = 0f
    private val wavePath = Path()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(45, 255, 255, 255)
    }

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.argb(55, 168, 199, 250)
    }

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val thumbInnerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#A8C7FA")
    }

    private val thumbShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(100, 0, 0, 0)
    }

    private val timeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10)
        color = Color.parseColor("#C4C6D0")
    }

    private var waveAnimator: ValueAnimator? = null

    init {
        setupWaveAnimation()
    }

    private fun setupWaveAnimation() {
        waveAnimator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (isPlaying && !isDragging) {
                    wavePhase = it.animatedValue as Float
                    invalidate()
                }
            }
        }
        if (isPlaying) waveAnimator?.start()
    }

    fun setPlaybackState(position: Long, duration: Long, playing: Boolean) {
        this.durationMs = max(0, duration)
        this.basePositionMs = max(0, min(position, if (duration > 0) duration else Long.MAX_VALUE))
        this.baseTimeMs = SystemClock.elapsedRealtime()
        this.isPlaying = playing

        if (playing) {
            if (waveAnimator?.isStarted != true) waveAnimator?.start()
        } else {
            waveAnimator?.cancel()
        }
        invalidate()
    }

    fun currentFraction(): Float {
        if (isDragging) return dragFraction
        if (durationMs <= 0) return 0f
        val now = SystemClock.elapsedRealtime()
        val pos = basePositionMs + if (isPlaying) (now - baseTimeMs) else 0L
        return (pos.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    private fun currentPositionMs(): Long {
        if (isDragging) return (dragFraction * durationMs).toLong()
        if (durationMs <= 0) return 0
        val now = SystemClock.elapsedRealtime()
        val pos = basePositionMs + if (isPlaying) (now - baseTimeMs) else 0L
        return pos.coerceIn(0L, durationMs)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationMs <= 0) return false
        val pad = dp(12)
        val trackWidth = width - pad * 2

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                dragFraction = ((event.x - pad) / trackWidth).coerceIn(0f, 1f)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    dragFraction = ((event.x - pad) / trackWidth).coerceIn(0f, 1f)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    dragFraction = ((event.x - pad) / trackWidth).coerceIn(0f, 1f)
                    basePositionMs = (dragFraction * durationMs).toLong()
                    baseTimeMs = SystemClock.elapsedRealtime()
                    onSeekListener?.invoke(dragFraction)
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = dp(12)
        val w = width.toFloat()
        val trackWidth = w - pad * 2
        val trackY = dp(10)
        val strokeW = dp(4)

        trackPaint.strokeWidth = strokeW
        wavePaint.strokeWidth = strokeW
        glowPaint.strokeWidth = strokeW + dp(3)

        // 1. Recessed full background track groove
        canvas.drawLine(pad, trackY, w - pad, trackY, trackPaint)

        val frac = currentFraction()
        val activeW = trackWidth * frac

        // 2. Wavy animated active progress
        if (frac > 0.005f) {
            wavePath.reset()
            wavePath.moveTo(pad, trackY)

            val xStart = pad
            val xEnd = pad + activeW

            if (activeW > dp(10)) {
                val wavelength = dp(22)
                val k = (2 * PI / wavelength).toFloat()
                val maxAmp = dp(3.2f)
                val step = dp(1.8f)
                val nSteps = max(2, (activeW / step).toInt())
                val dx = activeW / nSteps

                for (i in 1..nSteps) {
                    val x = xStart + i * dx
                    // Taper 8dp at beginning and end so it aligns cleanly with groove & knob
                    val leadIn = min(1f, (x - xStart) / dp(8))
                    val leadOut = min(1f, (xEnd - x) / dp(8))
                    val env = leadIn * leadOut
                    val y = trackY + maxAmp * env * sin((x - xStart) * k - wavePhase)
                    wavePath.lineTo(x, y)
                }
            } else {
                wavePath.lineTo(xEnd, trackY)
            }

            // Glowing gradient along track (M3.PRIMARY #A8C7FA -> M3.TERTIARY #E7B9F5)
            wavePaint.shader = LinearGradient(
                xStart, 0f, w - pad, 0f,
                Color.parseColor("#A8C7FA"),
                Color.parseColor("#E7B9F5"),
                Shader.TileMode.CLAMP
            )

            // Draw outer neon glow halo and crisp wave stroke
            canvas.drawPath(wavePath, glowPaint)
            canvas.drawPath(wavePath, wavePaint)

            // 3. Interactive luminous thumb knob
            val thumbRadius = if (isDragging) dp(6.5f) else dp(4.5f)
            canvas.drawCircle(xEnd, trackY + dp(1.2f), thumbRadius + dp(1.2f), thumbShadowPaint)
            canvas.drawCircle(xEnd, trackY, thumbRadius, thumbPaint)
            if (isDragging) {
                canvas.drawCircle(xEnd, trackY, thumbRadius * 0.45f, thumbInnerDotPaint)
            }
        }

        // 4. Timestamps
        val curSec = currentPositionMs() / 1000
        val durSec = durationMs / 1000
        val leftTime = formatTime(curSec)
        val rightTime = if (durSec > 0) "-${formatTime(max(0, durSec - curSec))}" else "--:--"

        val textY = trackY + dp(18)
        canvas.drawText(leftTime, pad, textY, timeTextPaint)

        val rightTextW = timeTextPaint.measureText(rightTime)
        canvas.drawText(rightTime, w - pad - rightTextW, textY, timeTextPaint)
    }

    private fun formatTime(sec: Long): String {
        val m = sec / 60
        val s = sec % 60
        return "%d:%02d".format(m, s)
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }

    private fun dp(value: Int): Float {
        return dp(value.toFloat())
    }
}
