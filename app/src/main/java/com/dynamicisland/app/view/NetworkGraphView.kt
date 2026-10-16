package com.dynamicisland.app.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * PC Dynamic Island style 2-row Network Telemetry Strip:
 * - Row 1: UL speed (Kbps/Mbps/Gbps) + 60-sample Cyan histogram + LAN IP
 * - Row 2: DL speed (Kbps/Mbps/Gbps) + 60-sample Amber histogram + WAN IP (Green when VPN ON, Red when VPN OFF)
 */
class NetworkGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val HISTORY = 48
        private const val MIN_SCALE = 125_000f // 1 Mbps in bytes/sec floor so idle noise stays small
    }

    private val histUp = FloatArray(HISTORY) { 0f }
    private val histDown = FloatArray(HISTORY) { 0f }

    private var upBps: Float = 0f
    private var downBps: Float = 0f
    private var lanIp: String = "—"
    private var wanIp: String = "…"
    private var vpnActive: Boolean = false
    private var colorWanByVpn: Boolean = true

    private val upColor = Color.parseColor("#6FD8F5")   // Cyan (matches Windows PC)
    private val downColor = Color.parseColor("#F5C04A") // Amber (matches Windows PC)
    private val vpnOnColor = Color.parseColor("#4ADE80")  // Bright Green when VPN is active
    private val vpnOffColor = Color.parseColor("#FF5F5F") // Red when VPN is off
    private val onSurfaceColor = Color.parseColor("#E3E2E6")
    private val mutedColor = Color.parseColor("#8E9099")
    private val surface1Color = Color.parseColor("#1B1B1F")
    private val surface3Color = Color.parseColor("#313137")

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = surface1Color
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(26, 255, 255, 255)
    }

    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val speedValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = onSurfaceColor
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }

    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = mutedColor
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
    }

    private val ipTagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }

    private val ipValPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val baseLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = surface3Color
    }

    private val cardRect = RectF()
    private val barRect = RectF()

    fun pushSample(
        upBytesPerSec: Float,
        downBytesPerSec: Float,
        lan: String,
        wan: String,
        isVpn: Boolean,
        wanVpnColorEnabled: Boolean = true
    ) {
        this.upBps = max(0f, upBytesPerSec)
        this.downBps = max(0f, downBytesPerSec)
        this.lanIp = lan.ifBlank { "—" }
        this.wanIp = wan.ifBlank { "…" }
        this.vpnActive = isVpn
        this.colorWanByVpn = wanVpnColorEnabled

        System.arraycopy(histUp, 1, histUp, 0, HISTORY - 1)
        histUp[HISTORY - 1] = this.upBps

        System.arraycopy(histDown, 1, histDown, 0, HISTORY - 1)
        histDown[HISTORY - 1] = this.downBps

        invalidate()
    }

    private fun formatRate(bps: Float): Pair<String, String> {
        val bits = bps * 8f
        return when {
            bits < 999_500f -> Pair("%.0f".format(java.util.Locale.US, bits / 1e3f), "Kbps")
            bits < 99.95e6f -> Pair("%.1f".format(java.util.Locale.US, bits / 1e6f), "Mbps")
            bits < 999.5e6f -> Pair("%.0f".format(java.util.Locale.US, bits / 1e6f), "Mbps")
            else -> Pair("%.2f".format(java.util.Locale.US, bits / 1e9f), "Gbps")
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val d = resources.displayMetrics.density
        val rad = 14f * d
        borderPaint.strokeWidth = 1f * d

        cardRect.set(0.5f * d, 0.5f * d, w - 0.5f * d, h - 0.5f * d)
        canvas.drawRoundRect(cardRect, rad, rad, bgPaint)
        canvas.drawRoundRect(cardRect, rad, rad, borderPaint)

        val rowH = (h - 8f * d) / 2f
        val wanColor = if (colorWanByVpn) {
            if (vpnActive) vpnOnColor else vpnOffColor
        } else {
            onSurfaceColor
        }

        tagPaint.textSize = 9.5f * d
        speedValuePaint.textSize = 11f * d
        unitPaint.textSize = 9f * d
        ipTagPaint.textSize = 9.2f * d
        ipValPaint.textSize = 10.2f * d

        val ipBlockW = 118f * d
        val gx0 = 96f * d
        val gx1 = w - 10f * d - ipBlockW - 8f * d

        for (i in 0..1) {
            val isUp = (i == 0)
            val tag = if (isUp) "UL" else "DL"
            val rate = if (isUp) upBps else downBps
            val hist = if (isUp) histUp else histDown
            val seriesColor = if (isUp) upColor else downColor
            val ipTag = if (isUp) "LAN" else "WAN"
            val ipStr = if (isUp) lanIp else wanIp
            val ipColor = if (isUp) onSurfaceColor else wanColor

            val rowTop = 4f * d + i * rowH
            val rowCenterY = rowTop + rowH / 2f
            val textBaseline = rowCenterY - (speedValuePaint.descent() + speedValuePaint.ascent()) / 2f

            // 1. UL / DL Tag
            tagPaint.color = seriesColor
            canvas.drawText(tag, 10f * d, textBaseline, tagPaint)

            // 2. Speed Number + Unit
            val (numStr, unitStr) = formatRate(rate)
            canvas.drawText(numStr, 58f * d, textBaseline, speedValuePaint)
            canvas.drawText(unitStr, 61f * d, textBaseline, unitPaint)

            // 3. 60s Live Histogram Bars (autoscaled per series like PC NetStrip)
            if (gx1 - gx0 > 24f * d) {
                val base = rowTop + rowH - 3.5f * d
                val maxBarH = (rowH - 6.5f * d).coerceAtLeast(4f * d)

                // Baseline
                canvas.drawRect(gx0, base, gx1, base + 1f * d, baseLinePaint)

                var peak = MIN_SCALE
                for (v in hist) {
                    if (v > peak) peak = v
                }

                val pitch = (gx1 - gx0) / HISTORY.toFloat()
                val bw = max(1f * d, pitch - 0.8f * d)
                barPaint.color = seriesColor

                for (n in 0 until HISTORY) {
                    val v = hist[n]
                    if (v > 0f) {
                        val bh = max(1.2f * d, (v / peak) * maxBarH)
                        val bx = gx0 + n * pitch
                        barRect.set(bx, base - bh, bx + bw, base)
                        canvas.drawRect(barRect, barPaint)
                    }
                }
            }

            // 4. LAN / WAN IP Readout (WAN turns bright green on VPN, red when unshielded)
            val ipStartX = w - 10f * d - ipBlockW
            ipTagPaint.color = if (isUp) mutedColor else ipColor
            val tagLabel = if (!isUp && vpnActive) "VPN" else ipTag
            canvas.drawText(tagLabel, ipStartX, textBaseline, ipTagPaint)

            ipValPaint.color = ipColor
            canvas.drawText(ipStr, w - 10f * d, textBaseline, ipValPaint)
        }
    }
}
