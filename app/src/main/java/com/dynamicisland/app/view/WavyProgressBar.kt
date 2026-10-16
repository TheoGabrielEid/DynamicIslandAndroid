package com.dynamicisland.app.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
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
    private var showRemainingTime: Boolean = true

    private var primaryColor: Int = Color.parseColor("#A8C7FA")
    private var tertiaryColor: Int = Color.parseColor("#E7B9F5")

    private var wavePhase: Float = 0f
    private val wavePath = Path()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(38, 255, 255, 255)
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
        color = Color.argb(60, 168, 199, 250)
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
        color = Color.argb(110, 0, 0, 0)
    }

    private val timeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10.5f)
        color = Color.parseColor("#C4C6D0")
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private var waveAnimator: ValueAnimator? = null

    init {
        setupWaveAnimation()
    }

    private fun setupWaveAnimation() {
        waveAnimator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
            duration = 1350
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

    fun setAccentColor(color: Int) {
        primaryColor = color
        thumbInnerDotPaint.color = color
        glowPaint.color = Color.argb(65, Color.red(color), Color.green(color), Color.blue(color))
        invalidate()
    }

    fun setPlaybackState(position: Long, duration: Long, playing: Boolean) {
        this.durationMs = max(0, duration)
        if (!isDragging) {
            this.basePositionMs = max(0, min(position, if (duration > 0) duration else Long.MAX_VALUE))
            this.baseTimeMs = SystemClock.elapsedRealtime()
        }
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
        if (durationMs <= 0) return if (isPlaying) 0.35f else 0f
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
        val pad = dp(10)
        val trackWidth = (width - pad * 2).coerceAtLeast(1f)

        // Allow tapping the bottom-right timestamp area to toggle remaining vs total time
        if (event.action == MotionEvent.ACTION_DOWN && event.y > dp(16) && event.x > width * 0.65f) {
            showRemainingTime = !showRemainingTime
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            invalidate()
            return true
        }

        if (durationMs <= 0) return false

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
        val pad = dp(10)
        val w = width.toFloat()
        val trackWidth = w - pad * 2
        val trackY = dp(9.5f)
        val strokeW = if (isDragging) dp(4.8f) else dp(3.8f)

        trackPaint.strokeWidth = strokeW
        wavePaint.strokeWidth = strokeW
        glowPaint.strokeWidth = strokeW + dp(3.2f)

        // 1. Recessed full background track groove
        canvas.drawLine(pad, trackY, w - pad, trackY, trackPaint)

        val frac = currentFraction()
        val activeW = trackWidth * frac

        // 2. Wavy animated active progress
        if (frac > 0.004f) {
            wavePath.reset()
            wavePath.moveTo(pad, trackY)

            val xStart = pad
            val xEnd = pad + activeW

            if (activeW > dp(10) && isPlaying) {
                val wavelength = dp(22)
                val k = (2 * PI / wavelength).toFloat()
                val maxAmp = dp(3.2f)
                val step = dp(1.6f)
                val nSteps = max(2, (activeW / step).toInt())
                val dx = activeW / nSteps

                for (i in 1..nSteps) {
                    val x = xStart + i * dx
                    val leadIn = min(1f, (x - xStart) / dp(8))
                    val leadOut = min(1f, (xEnd - x) / dp(8))
                    val env = leadIn * leadOut
                    val y = trackY + maxAmp * env * sin((x - xStart) * k - wavePhase)
                    wavePath.lineTo(x, y)
                }
            } else {
                wavePath.lineTo(xEnd, trackY)
            }

            wavePaint.shader = LinearGradient(
                xStart, 0f, w - pad, 0f,
                primaryColor,
                tertiaryColor,
                Shader.TileMode.CLAMP
            )

            canvas.drawPath(wavePath, glowPaint)
            canvas.drawPath(wavePath, wavePaint)

            // 3. Interactive luminous thumb knob
            val thumbRadius = if (isDragging) dp(6.5f) else dp(4.6f)
            canvas.drawCircle(xEnd, trackY + dp(1.2f), thumbRadius + dp(1.2f), thumbShadowPaint)
            canvas.drawCircle(xEnd, trackY, thumbRadius, thumbPaint)
            if (isDragging || isPlaying) {
                canvas.drawCircle(xEnd, trackY, thumbRadius * 0.44f, thumbInnerDotPaint)
            }
        }

        // 4. Timestamps
        val curSec = currentPositionMs() / 1000
        val durSec = durationMs / 1000
        val leftTime = formatTime(curSec)
        val rightTime = if (durSec > 0) {
            if (showRemainingTime) "-${formatTime(max(0, durSec - curSec))}" else formatTime(durSec)
        } else {
            "--:--"
        }

        val textY = trackY + dp(18.5f)
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

    override fun onDetachedFromWindow() {
        waveAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
