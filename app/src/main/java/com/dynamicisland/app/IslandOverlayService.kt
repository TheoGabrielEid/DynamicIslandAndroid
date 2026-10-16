package com.dynamicisland.app

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.dynamicisland.app.view.WavyProgressBar

class IslandOverlayService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_UPDATE_MEDIA = "ACTION_UPDATE_MEDIA"
        const val ACTION_BATTERY = "ACTION_BATTERY"

        const val EXTRA_TITLE = "EXTRA_TITLE"
        const val EXTRA_ARTIST = "EXTRA_ARTIST"
        const val EXTRA_PLAYING = "EXTRA_PLAYING"
        const val EXTRA_POS = "EXTRA_POS"
        const val EXTRA_DUR = "EXTRA_DUR"
        const val EXTRA_APP = "EXTRA_APP"
        const val EXTRA_CHARGING = "EXTRA_CHARGING"

        var isRunning: Boolean = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: PreferencesManager

    private var compactView: View? = null
    private var expandedView: View? = null

    private var compactParams: WindowManager.LayoutParams? = null
    private var expandedParams: WindowManager.LayoutParams? = null

    private var isExpanded: Boolean = false
    private val handler = Handler(Looper.getMainLooper())
    private var collapseRunnable: Runnable? = null

    // Current media state
    private var currentTitle: String = "Nothing playing"
    private var currentArtist: String = "Ready"
    private var isPlaying: Boolean = false
    private var currentPos: Long = 0L
    private var currentDur: Long = 0L
    private var currentApp: String = ""
    private var albumArt: Bitmap? = null

    private val mediaUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                ACTION_UPDATE_MEDIA -> {
                    currentTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Now Playing"
                    currentArtist = intent.getStringExtra(EXTRA_ARTIST) ?: ""
                    isPlaying = intent.getBooleanExtra(EXTRA_PLAYING, false)
                    currentPos = intent.getLongExtra(EXTRA_POS, 0L)
                    currentDur = intent.getLongExtra(EXTRA_DUR, 0L)
                    currentApp = intent.getStringExtra(EXTRA_APP) ?: ""
                    updateViews()
                }
                ACTION_BATTERY -> {
                    val charging = intent.getBooleanExtra(EXTRA_CHARGING, false)
                    if (charging) {
                        showToastPill("Charging", R.drawable.ic_bolt)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = PreferencesManager(this)

        startForeground(1001, createForegroundNotification())

        val filter = IntentFilter().apply {
            addAction(ACTION_UPDATE_MEDIA)
            addAction(ACTION_BATTERY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mediaUpdateReceiver, filter)
        }

        setupOverlayViews()
    }

    private fun createForegroundNotification(): Notification {
        val channelId = "dynamic_island_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Dynamic Island Overlay Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the punch-hole dynamic island active on your phone"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Dynamic Island Active")
            .setContentText("Tap to configure notch position and options")
            .setSmallIcon(R.drawable.ic_music)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun setupOverlayViews() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        // 1. Setup Compact Pill View
        compactView = LayoutInflater.from(this).inflate(R.layout.layout_island_compact, null)
        val density = resources.displayMetrics.density
        val pillWidthPx = (prefs.compactWidth * density).toInt()
        val pillHeightPx = (prefs.compactHeight * density).toInt()
        val pillYPx = (prefs.notchYOffset * density).toInt()
        val pillXPx = (prefs.notchXOffset * density).toInt()

        compactParams = WindowManager.LayoutParams(
            pillWidthPx,
            pillHeightPx,
            overlayType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = pillXPx
            y = pillYPx
        }

        compactView?.setOnClickListener {
            expandIsland()
        }

        // 2. Setup Expanded Card View
        expandedView = LayoutInflater.from(this).inflate(R.layout.layout_island_expanded, null)
        val screenWidth = resources.displayMetrics.widthPixels
        val expandedWidth = (screenWidth * 0.94f).toInt()

        expandedParams = WindowManager.LayoutParams(
            expandedWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = pillYPx
        }

        setupExpandedControls()

        // Attach compact view initially
        windowManager.addView(compactView, compactParams)
        updateViews()
    }

    private fun setupExpandedControls() {
        val exp = expandedView ?: return

        exp.findViewById<ImageButton>(R.id.btn_collapse)?.setOnClickListener {
            collapseIsland()
        }

        exp.findViewById<ImageButton>(R.id.btn_play_pause)?.setOnClickListener {
            sendMediaKey(if (isPlaying) "pause" else "play")
            isPlaying = !isPlaying
            updateViews()
        }

        exp.findViewById<ImageButton>(R.id.btn_next)?.setOnClickListener {
            sendMediaKey("next")
        }

        exp.findViewById<ImageButton>(R.id.btn_prev)?.setOnClickListener {
            sendMediaKey("prev")
        }

        val wavyBar = exp.findViewById<WavyProgressBar>(R.id.expanded_wavy_progress)
        wavyBar?.onSeekListener = { frac ->
            if (currentDur > 0) {
                val seekMs = (frac * currentDur).toLong()
                val seekIntent = Intent(MediaNotificationListener.ACTION_SEEK).apply {
                    putExtra("position_ms", seekMs)
                }
                sendBroadcast(seekIntent)
            }
        }
    }

    private fun sendMediaKey(command: String) {
        val intent = Intent(MediaNotificationListener.ACTION_COMMAND).apply {
            putExtra("command", command)
        }
        sendBroadcast(intent)
    }

    private fun expandIsland() {
        if (isExpanded) return
        isExpanded = true

        compactView?.visibility = View.GONE
        expandedView?.visibility = View.VISIBLE

        if (expandedView?.parent == null) {
            windowManager.addView(expandedView, expandedParams)
        } else {
            windowManager.updateViewLayout(expandedView, expandedParams)
        }

        // Smooth spring pop-in
        expandedView?.scaleX = 0.85f
        expandedView?.scaleY = 0.85f
        expandedView?.alpha = 0f
        expandedView?.animate()
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.alpha(1f)
            ?.setDuration(300)
            ?.setInterpolator(OvershootInterpolator(1.2f))
            ?.start()

        // Auto-collapse after 10s of inactivity if paused
        scheduleAutoCollapse(10000)
    }

    private fun collapseIsland() {
        if (!isExpanded) return
        isExpanded = false

        expandedView?.animate()
            ?.scaleX(0.85f)
            ?.scaleY(0.85f)
            ?.alpha(0f)
            ?.setDuration(220)
            ?.withEndAction {
                if (expandedView?.parent != null) {
                    windowManager.removeView(expandedView)
                }
                compactView?.visibility = View.VISIBLE
                compactView?.scaleX = 1f
                compactView?.scaleY = 1f
                compactView?.alpha = 1f
            }
            ?.start()
    }

    private fun scheduleAutoCollapse(delayMs: Long) {
        collapseRunnable?.let { handler.removeCallbacks(it) }
        collapseRunnable = Runnable {
            if (isExpanded && !isPlaying) {
                collapseIsland()
            }
        }
        handler.postDelayed(collapseRunnable!!, delayMs)
    }

    private fun updateViews() {
        // Update Compact
        compactView?.let { v ->
            val titleTv = v.findViewById<TextView>(R.id.compact_title)
            val iconIv = v.findViewById<ImageView>(R.id.compact_icon)
            val trailingTv = v.findViewById<TextView>(R.id.compact_trailing)

            titleTv?.text = if (isPlaying || currentTitle.isNotEmpty()) currentTitle else "Dynamic Island"
            iconIv?.setImageResource(R.drawable.ic_music)
            trailingTv?.text = if (isPlaying) "●" else ""
        }

        // Update Expanded
        expandedView?.let { v ->
            val titleTv = v.findViewById<TextView>(R.id.expanded_title)
            val artistTv = v.findViewById<TextView>(R.id.expanded_artist)
            val badgeTv = v.findViewById<TextView>(R.id.expanded_badge)
            val playBtn = v.findViewById<ImageButton>(R.id.btn_play_pause)
            val wavyBar = v.findViewById<WavyProgressBar>(R.id.expanded_wavy_progress)

            titleTv?.text = currentTitle
            artistTv?.text = if (currentArtist.isNotEmpty()) currentArtist else (currentApp.ifEmpty { "Playing on this device" })
            badgeTv?.text = if (currentApp.isNotEmpty()) "● ${currentApp.uppercase()}" else (if (isPlaying) "● PLAYING" else "● IDLE")

            playBtn?.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            wavyBar?.setPlaybackState(currentPos, currentDur, isPlaying)
        }
    }

    fun showToastPill(text: String, iconRes: Int) {
        compactView?.let { v ->
            val titleTv = v.findViewById<TextView>(R.id.compact_title)
            val iconIv = v.findViewById<ImageView>(R.id.compact_icon)
            titleTv?.text = text
            iconIv?.setImageResource(iconRes)
            handler.postDelayed({ updateViews() }, 3000)
        }
    }

    override fun onDestroy() {
        isRunning = false
        try {
            unregisterReceiver(mediaUpdateReceiver)
        } catch (e: Exception) {}

        if (compactView?.parent != null) windowManager.removeView(compactView)
        if (expandedView?.parent != null) windowManager.removeView(expandedView)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
