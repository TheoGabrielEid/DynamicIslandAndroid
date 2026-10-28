package com.dynamicisland.app

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.dynamicisland.app.view.AlbumArtView
import com.dynamicisland.app.view.FocusRingView
import com.dynamicisland.app.view.IslandContainerView
import com.dynamicisland.app.view.MaxHeightNestedScrollView
import com.dynamicisland.app.view.NetworkGraphView
import com.dynamicisland.app.view.RingGaugeView
import com.dynamicisland.app.view.WaveformVisualizerView
import com.dynamicisland.app.view.WavyProgressBar
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class IslandOverlayService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_UPDATE_MEDIA = "ACTION_UPDATE_MEDIA"
        const val ACTION_BATTERY = "ACTION_BATTERY"
        const val ACTION_REFRESH_PREFS = "ACTION_REFRESH_PREFS"

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
    private var isPinned: Boolean = false
    private var activeTab: Int = 0 // 0=Media, 1=System, 2=Focus, 3=Tools, 4=Apps

    private val handler = Handler(Looper.getMainLooper())
    private var collapseRunnable: Runnable? = null
    private val bgExecutor = Executors.newSingleThreadExecutor()

    // Media state
    private var currentTitle: String = "Nothing playing"
    private var currentArtist: String = "Play music on Spotify, YouTube or Chrome"
    private var isPlaying: Boolean = false
    private var currentPos: Long = 0L
    private var currentDur: Long = 0L
    private var currentApp: String = ""
    private var currentAlbumArt: Bitmap? = null
    private var lastTrackTitle: String = ""

    // Focus Pomodoro Timer state
    private var focusTotalSec: Int = 25 * 60
    private var focusRemainingSec: Int = 25 * 60
    private var focusRunning: Boolean = false

    private var timerFinishAtMs: Long = 0L
    private var timerRemainingMs: Long = 0L
    private var timerRunning: Boolean = false
    private var previousBatteryCharging: Boolean? = null
    private var previousBatteryPercent: Int = -1
    private var previousBatterySampleAtMs: Long = 0L
    private var batteryPercentPerHour: Double = 0.0
    private var lowBatteryAlertShown: Boolean = false

    // Flashlight / Torch state
    private var isTorchOn: Boolean = false
    private var cameraId: String? = null
    private var cameraManager: CameraManager? = null

    // Toast override state
    private var toastActiveUntil: Long = 0L
    private var toastMessage: String = ""

    // Clipboard state
    private var lastClipboardText: String = ""

    // Google Maps Navigation state
    private var isNavigating: Boolean = false
    private var navManeuver: String = ""
    private var navDistance: String = ""
    private var navEta: String = ""
    private var navIcon: Bitmap? = null

    // Audio Output / Headset state
    private var isHeadsetPlugged: Boolean = false

    // Live Network Traffic & LAN/WAN/VPN Telemetry (matches Windows PC NetMeter)
    private var prevRxBytes: Long = -1L
    private var prevTxBytes: Long = -1L
    private var prevTrafficTimeMs: Long = 0L
    private var curDownBps: Float = 0f
    private var curUpBps: Float = 0f
    private var netScalePeak: Float = 125_000f // 1 Mbps floor
    private var cachedLanIp: String = "—"
    private var cachedWanIp: String = "…"
    private var isVpnActive: Boolean = false
    private var lastRouteSignature: String = ""
    private var lastWanFetchMs: Long = 0L
    @Volatile
    private var wanFetchBusy: Boolean = false

    private val navListener: (NavigationStateBridge.NavSnapshot) -> Unit = { snap ->
        val wasNavigating = isNavigating
        isNavigating = snap.isNavigating && !snap.isStale
        navManeuver = snap.maneuverText
        navDistance = snap.distanceText
        navEta = snap.etaText
        navIcon = snap.icon

        if (isNavigating && !wasNavigating && prefs.showMapsNavigation) {
            val dist = if (navDistance.isNotBlank()) navDistance else "Navigation"
            showToastPill("🧭 $dist", R.drawable.ic_navigation, Color.parseColor("#4ADE80"))
        }
        updateViews()
    }

    // App Notification State (WhatsApp, Telegram, Instagram, etc.)
    private var activeNotification: NotificationStateBridge.AppNotification? = null
    private var notifActiveUntil: Long = 0L

    private val notifListener: (NotificationStateBridge.AppNotification?) -> Unit = { notif ->
        activeNotification = notif
        if (notif != null && (prefs.showAppNotifications || notif.isCall)) {
            notifActiveUntil = if (notif.isCall) Long.MAX_VALUE else SystemClock.elapsedRealtime() + 6500L
            val color = getNotificationAccentColor(notif.packageName)
            vibrateAlert()
            if (notif.isCall && !isExpanded) {
                expandIsland()
            } else if (isExpanded) {
                triggerExpandedAura(color, 1600L)
            } else {
                (compactView as? IslandContainerView)?.triggerAura(color, 2600L)
            }
            updateViews()
            handler.postDelayed({
                if (SystemClock.elapsedRealtime() >= notifActiveUntil) {
                    updateViews()
                }
            }, 6600L)
        } else {
            updateViews()
        }
    }

    private fun getNotificationAccentColor(packageName: String): Int {
        val lower = packageName.lowercase()
        return when {
            lower.contains("whatsapp") -> Color.parseColor("#25D366")
            lower.contains("telegram") -> Color.parseColor("#2AABEE")
            lower.contains("instagram") -> Color.parseColor("#E1306C")
            lower.contains("twitter") || lower.contains(".x.") -> Color.parseColor("#1DA1F2")
            lower.contains("messenger") -> Color.parseColor("#0084FF")
            lower.contains("snapchat") -> Color.parseColor("#FFFC00")
            lower.contains("discord") -> Color.parseColor("#5865F2")
            lower.contains("gmail") || lower.contains("mail") -> Color.parseColor("#EA4335")
            lower.contains("sms") || lower.contains("messaging") -> Color.parseColor("#34A853")
            else -> parseAccentColor()
        }
    }

    private val bridgeListener: (MediaStateBridge.MediaSnapshot) -> Unit = { snap ->
        val trackChanged = snap.isPlaying && snap.title != lastTrackTitle && snap.title != "Nothing playing"
        if (snap.title != "Nothing playing") {
            lastTrackTitle = snap.title
        }
        currentTitle = snap.title
        currentArtist = snap.artist
        currentApp = snap.appName
        isPlaying = snap.isPlaying
        currentPos = snap.livePositionMs()
        currentDur = snap.durationMs
        currentAlbumArt = snap.albumArt
        updateViews()

        if (trackChanged && prefs.autoExpandOnSong && !isExpanded) {
            showToastPill("♫ ${snap.title}", R.drawable.ic_music, parseAccentColor())
        }
    }

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(camId: String, enabled: Boolean) {
            if (camId == cameraId) {
                isTorchOn = enabled
                updateToolsState()
                updateViews()
            }
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            try {
                // 1. Update Focus Timer if running
                if (focusRunning && focusRemainingSec > 0) {
                    focusRemainingSec--
                    if (focusRemainingSec <= 0) {
                        focusRunning = false
                        vibrateAlert()
                        showToastPill("🎉 Focus Complete!", R.drawable.ic_music, Color.parseColor("#7FD88F"))
                    }
                    updateFocusUI()
                }

                if (timerRunning) {
                    timerRemainingMs = (timerFinishAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
                    if (timerRemainingMs == 0L) {
                        timerRunning = false
                        persistCountdownTimer()
                        vibrateAlert()
                        showToastPill("Timer complete", R.drawable.ic_timer, Color.parseColor("#7FD88F"))
                    }
                }

                // 2. Sample Network Traffic (always keep 60-sec history fresh like PC NetMeter)
                sampleNetworkTraffic()

                // 3. Refresh live media progress & system stats
                if (isPlaying) {
                    currentPos = MediaStateBridge.current.livePositionMs()
                }
                if (isExpanded && activeTab == 1) {
                    updateSystemMetrics()
                }
                updateViews()
            } catch (e: Exception) {
                // Zero-crash guarantee on periodic tick
            }

            handler.postDelayed(this, 1000L)
        }
    }

    private val mediaUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                ACTION_UPDATE_MEDIA -> {
                    val title = intent.getStringExtra(EXTRA_TITLE) ?: "Now Playing"
                    val artist = intent.getStringExtra(EXTRA_ARTIST) ?: ""
                    val playing = intent.getBooleanExtra(EXTRA_PLAYING, false)
                    val pos = intent.getLongExtra(EXTRA_POS, 0L)
                    val dur = intent.getLongExtra(EXTRA_DUR, 0L)
                    val app = intent.getStringExtra(EXTRA_APP) ?: ""
                    MediaStateBridge.update(title, artist, app, playing, pos, dur, currentAlbumArt)
                }
                ACTION_BATTERY -> {
                    val charging = intent.getBooleanExtra(EXTRA_CHARGING, false)
                    val pct = intent.getIntExtra("EXTRA_BATTERY_PCT", getBatteryPercent())
                    if (charging) {
                        showToastPill("⚡ Charging $pct%", R.drawable.ic_bolt, Color.parseColor("#7FD88F"))
                    }
                }
                Intent.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", -1)
                    val name = intent.getStringExtra("name") ?: "Headphones"
                    if (state == 1) {
                        isHeadsetPlugged = true
                        if (prefs.showHeadsetConnected) {
                            showToastPill("🎧 $name Connected", R.drawable.ic_headphones, parseAccentColor())
                        }
                    } else if (state == 0) {
                        isHeadsetPlugged = false
                        if (prefs.showHeadsetConnected) {
                            showToastPill("🎧 Headset Disconnected", R.drawable.ic_headphones, Color.parseColor("#FFB4AB"))
                        }
                    }
                    updateViews()
                }
                android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                    }
                    val devName = try { device?.name } catch (e: SecurityException) { null } ?: "Earbuds / Headset"
                    isHeadsetPlugged = true
                    if (prefs.showHeadsetConnected) {
                        showToastPill("🎧 $devName Connected", R.drawable.ic_headphones, parseAccentColor())
                    }
                    updateViews()
                }
                android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    isHeadsetPlugged = false
                    if (prefs.showHeadsetConnected) {
                        showToastPill("🎧 Earbuds Disconnected", R.drawable.ic_headphones, Color.parseColor("#FFB4AB"))
                    }
                    updateViews()
                }
                ACTION_REFRESH_PREFS -> {
                    applyWindowLayoutUpdates()
                    updateViews()
                    if (isExpanded && activeTab == 1) updateSystemMetrics()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = PreferencesManager(this)
        restoreCountdownTimer()

        startForeground(1001, createForegroundNotification())

        initFlashlight()
        initClipboardWatcher()

        val filter = IntentFilter().apply {
            addAction(ACTION_UPDATE_MEDIA)
            addAction(ACTION_BATTERY)
            addAction(ACTION_REFRESH_PREFS)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mediaUpdateReceiver, filter)
        }

        setupOverlayViews()
        MediaStateBridge.addListener(bridgeListener)
        NavigationStateBridge.addListener(navListener)
        NotificationStateBridge.addListener(notifListener)
        handler.post(tickRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        applyWindowLayoutUpdates()
        updateViews()
        return START_STICKY
    }

    private fun initClipboardWatcher() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.addPrimaryClipChangedListener {
                try {
                    val clip = cm.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val txt = clip.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: ""
                        if (txt.isNotEmpty() && txt != lastClipboardText) {
                            lastClipboardText = txt
                            updateToolsState()
                            if (!isExpanded) {
                                val preview = if (txt.length > 22) txt.take(22) + "…" else txt
                                showToastPill("📋 Copied: $preview", R.drawable.ic_music, parseAccentColor())
                            }
                        }
                    }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
    }

    private fun initFlashlight() {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
            cameraManager = cm
            var chosenId: String? = null
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (hasFlash) {
                    chosenId = id
                    break
                }
            }
            cameraId = chosenId ?: cm.cameraIdList.firstOrNull()
            cm.registerTorchCallback(torchCallback, handler)
        } catch (e: Exception) {
            // Device may not have flash
        }
    }

    private fun toggleFlashlight() {
        val cm = cameraManager ?: return
        val id = cameraId ?: return
        try {
            val nextState = !isTorchOn
            cm.setTorchMode(id, nextState)
            isTorchOn = nextState
            vibrateTick()
            val gold = Color.parseColor("#FFB86C")
            showToastPill(
                if (isTorchOn) "⚡ Flashlight ON" else "Flashlight OFF",
                R.drawable.ic_bolt,
                gold
            )
            updateToolsState()
        } catch (e: Exception) {
            // Ignore torch error
        }
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
            .setContentTitle("Dynamic Island Pro Active")
            .setContentText("Tap to customize themes, network graph, and cutout position")
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
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)

        val density = resources.displayMetrics.density
        val pillWidthPx = (prefs.compactWidth * density).toInt()
        val pillHeightPx = (prefs.compactHeight * density).toInt()
        val pillYPx = (prefs.notchYOffset * density).toInt()
        val pillXPx = (prefs.notchXOffset * density).toInt()

        // 1. Compact Pill View
        compactView = LayoutInflater.from(this).inflate(R.layout.layout_island_compact, null).apply {
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        (compactView as? IslandContainerView)?.isCompactPill = true
        compactView?.findViewById<WaveformVisualizerView>(R.id.compact_visualizer)?.barCount = 4

        // Clip compact badge to circle so album art never bleeds
        val compactIcon = compactView?.findViewById<ImageView>(R.id.compact_icon)
        compactIcon?.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        compactIcon?.clipToOutline = true

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }

        setupCompactGestures()

        // 2. Expanded Multi-Tab View
        expandedView = LayoutInflater.from(this).inflate(R.layout.layout_island_expanded, null)
        (expandedView as? IslandContainerView)?.apply {
            isCompactPill = false
            cornerRadiusDp = 28f
        }
        val screenWidth = resources.displayMetrics.widthPixels
        val expandedWidth = min((screenWidth * 0.92f).toInt(), (440f * density).toInt())
        val expandedHeight = min((resources.displayMetrics.heightPixels * 0.42f).toInt(), (340f * density).toInt())
            .coerceAtLeast((220f * density).toInt())
        expandedView?.findViewById<MaxHeightNestedScrollView>(R.id.expanded_scroll)?.maxHeightPx = expandedHeight

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }

        setupExpandedControls()

        try {
            windowManager.addView(compactView, compactParams)
        } catch (e: Exception) {}
        updateViews()
    }

    private fun applyWindowLayoutUpdates() {
        val density = resources.displayMetrics.density
        val pillWidthPx = (prefs.compactWidth * density).toInt()
        val pillHeightPx = (prefs.compactHeight * density).toInt()
        val pillYPx = (prefs.notchYOffset * density).toInt()
        val pillXPx = (prefs.notchXOffset * density).toInt()

        compactParams?.let { p ->
            p.width = pillWidthPx
            p.height = pillHeightPx
            p.x = pillXPx
            p.y = pillYPx
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            if (compactView?.parent != null) {
                try {
                    windowManager.updateViewLayout(compactView, p)
                } catch (e: Exception) {}
            }
        }
        expandedParams?.let { p ->
            val screenWidth = resources.displayMetrics.widthPixels
            p.width = min((screenWidth * 0.92f).toInt(), (440f * density).toInt())
            (expandedView?.findViewById<MaxHeightNestedScrollView>(R.id.expanded_scroll))?.maxHeightPx =
                min((resources.displayMetrics.heightPixels * 0.42f).toInt(), (340f * density).toInt())
                    .coerceAtLeast((220f * density).toInt())
            p.height = WindowManager.LayoutParams.WRAP_CONTENT
            p.y = pillYPx
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            if (expandedView?.parent != null) {
                try {
                    windowManager.updateViewLayout(expandedView, p)
                } catch (e: Exception) {}
            }
        }
    }

    private fun setupCompactGestures() {
        var downX = 0f
        var downY = 0f
        var downTime = 0L
        var isPotentialTap = false
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat().coerceAtLeast(24f)
        var lastTapTime = 0L

        compactView?.setOnClickListener { v ->
            // A notification tap opens its app; otherwise toggle the combined island panel.
            if (notifActiveUntil > SystemClock.elapsedRealtime() && activeNotification != null) {
                val notif = activeNotification
                notifActiveUntil = 0L
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                try {
                    if (notif?.contentIntent != null) notif.contentIntent.send()
                    else launchPackageOrWeb(notif?.packageName ?: "", "")
                } catch (e: Exception) {
                    launchPackageOrWeb(notif?.packageName ?: "", "")
                }
                updateViews()
            } else {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (isExpanded) collapseIsland(force = true) else expandIsland()
            }
        }

        compactView?.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    downTime = SystemClock.uptimeMillis()
                    isPotentialTap = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > touchSlop || abs(event.rawY - downY) > touchSlop) {
                        isPotentialTap = false
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val duration = SystemClock.uptimeMillis() - downTime
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY

                    if (isPotentialTap && duration < 500L) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTapTime < 320L && prefs.doubleTapPlayPause) {
                            // Double tap: toggle music play/pause
                            lastTapTime = 0L
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            MediaStateBridge.playOrPause(this@IslandOverlayService)
                            showToastPill(
                                if (MediaStateBridge.current.isPlaying) "▶ Playing" else "⏸ Paused",
                                R.drawable.ic_music,
                                parseAccentColor()
                            )
                        } else {
                            lastTapTime = now
                            // performClick also gives accessibility services the same action.
                            v.performClick()
                        }
                    } else if (abs(dx) > touchSlop * 2f && abs(dx) > abs(dy)) {
                        // Horizontal swipe across compact pill: dismiss notification
                        if (notifActiveUntil > SystemClock.elapsedRealtime()) {
                            notifActiveUntil = 0L
                            NotificationStateBridge.dismissCurrent()
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            updateViews()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    isPotentialTap = false
                    true
                }
                else -> false
            }
        }
    }

    private fun setupExpandedControls() {
        val exp = expandedView ?: return

        val artView = exp.findViewById<AlbumArtView>(R.id.expanded_art_view)

        // Tap album art or app badge to jump directly into active music player app
        val openPlayerClick = View.OnClickListener {
            vibrateTick()
            if (MediaStateBridge.launchActivePlayerApp(this)) {
                collapseIsland(force = true)
            }
        }
        artView?.setOnClickListener(openPlayerClick)
        exp.findViewById<View>(R.id.expanded_badge)?.setOnClickListener(openPlayerClick)

        exp.findViewById<WaveformVisualizerView>(R.id.expanded_visualizer)?.barCount = 12

        // 6-Tab Navigation (Media, System, Focus, Tools, Apps, Nav)
        exp.findViewById<TextView>(R.id.tab_media)?.setOnClickListener { switchTab(0) }
        exp.findViewById<TextView>(R.id.tab_system)?.setOnClickListener { switchTab(1) }
        exp.findViewById<TextView>(R.id.tab_focus)?.setOnClickListener { switchTab(2) }
        exp.findViewById<TextView>(R.id.tab_tools)?.setOnClickListener { switchTab(3) }
        exp.findViewById<TextView>(R.id.tab_apps)?.setOnClickListener { switchTab(4) }
        exp.findViewById<TextView>(R.id.tab_nav)?.setOnClickListener { switchTab(5) }

        // Pin button (matching PC DynamicCapsule pin feature)
        val pinBtn = exp.findViewById<TextView>(R.id.btn_pin)
        pinBtn?.setOnClickListener {
            vibrateTick()
            isPinned = !isPinned
            pinBtn.setBackgroundResource(if (isPinned) R.drawable.bg_tab_selected else R.drawable.bg_pill_button)
            triggerExpandedAura(parseAccentColor())
            if (!isPinned) {
                resetAutoCollapseTimer()
            } else {
                collapseRunnable?.let { handler.removeCallbacks(it) }
            }
        }

        // Collapse triggers: top camera notch dismiss bar, close button, clock, and bottom grabber
        exp.findViewById<View>(R.id.top_notch_dismiss_bar)?.setOnClickListener {
            collapseIsland(force = true)
        }
        exp.findViewById<View>(R.id.tv_header_time)?.setOnClickListener {
            collapseIsland(force = true)
        }
        exp.findViewById<View>(R.id.tv_header_date)?.setOnClickListener {
            collapseIsland(force = true)
        }
        exp.findViewById<ImageButton>(R.id.btn_collapse)?.setOnClickListener {
            collapseIsland(force = true)
        }
        exp.findViewById<View>(R.id.bottom_grabber_area)?.setOnClickListener {
            collapseIsland(force = true)
        }

        // Notification Banner Actions (Open WhatsApp/app chat or dismiss)
        exp.findViewById<View>(R.id.banner_notif_btn_open)?.setOnClickListener {
            val notif = activeNotification
            if (notif != null) {
                vibrateTick()
                collapseIsland(force = true)
                try {
                    if (notif.contentIntent != null) {
                        notif.contentIntent.send()
                    } else {
                        launchPackageOrWeb(notif.packageName, "")
                    }
                } catch (e: Exception) {
                    launchPackageOrWeb(notif.packageName, "")
                }
            }
        }
        exp.findViewById<View>(R.id.banner_notif_btn_dismiss)?.setOnClickListener {
            vibrateTick()
            activeNotification = null
            notifActiveUntil = 0L
            NotificationStateBridge.dismissCurrent()
            exp.findViewById<View>(R.id.banner_notification)?.visibility = View.GONE
            updateViews()
        }
        exp.findViewById<View>(R.id.banner_call_answer)?.setOnClickListener {
            performCallAction(answer = true)
        }
        exp.findViewById<View>(R.id.banner_call_decline)?.setOnClickListener {
            performCallAction(answer = false)
        }
        exp.findViewById<View>(R.id.banner_notif_mark_read)?.setOnClickListener { performMarkReadAction() }
        exp.findViewById<View>(R.id.timer_5m)?.setOnClickListener { startCountdown(5) }
        exp.findViewById<View>(R.id.timer_15m)?.setOnClickListener { startCountdown(15) }
        exp.findViewById<View>(R.id.timer_30m)?.setOnClickListener { startCountdown(30) }
        exp.findViewById<View>(R.id.timer_toggle)?.setOnClickListener { toggleCountdown() }
        exp.findViewById<View>(R.id.timer_stop)?.setOnClickListener { stopCountdown() }

        // Swipe up to collapse or swipe left/right to cycle tabs!
        val swipeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val dy = e1.y - e2.y
                if (dy > 45f && abs(velocityY) > abs(velocityX) && abs(velocityY) > 180f) {
                    collapseIsland(force = true)
                    return true
                }
                return false
            }
        })
        exp.setOnTouchListener { _, ev ->
            swipeDetector.onTouchEvent(ev)
            false
        }

        // Media Transport Controls
        exp.findViewById<ImageButton>(R.id.btn_play_pause)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            MediaStateBridge.playOrPause(this)
            triggerExpandedAura(parseAccentColor())
            resetAutoCollapseTimer()
        }

        exp.findViewById<ImageButton>(R.id.btn_next)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            MediaStateBridge.next(this)
            triggerExpandedAura(parseAccentColor())
            resetAutoCollapseTimer()
        }

        exp.findViewById<ImageButton>(R.id.btn_prev)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            MediaStateBridge.previous(this)
            triggerExpandedAura(parseAccentColor())
            resetAutoCollapseTimer()
        }

        val wavyBar = exp.findViewById<WavyProgressBar>(R.id.expanded_wavy_progress)
        wavyBar?.onSeekListener = { frac ->
            if (currentDur > 0) {
                val seekMs = (frac * currentDur).toLong()
                MediaStateBridge.seekTo(this, seekMs)
            }
            resetAutoCollapseTimer()
        }

        // Frosted Volume Pill
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val volSlider = exp.findViewById<SeekBar>(R.id.slider_volume)
        val volLbl = exp.findViewById<TextView>(R.id.tv_volume_lbl)
        val volBtn = exp.findViewById<ImageButton>(R.id.btn_vol_icon)

        audioManager?.let { am ->
            syncVolumeUI(am, volSlider, volLbl, volBtn)

            volSlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        if (maxVol > 0) {
                            val targetVol = (progress * maxVol / 100f).toInt()
                            try {
                                am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                            } catch (e: Exception) {}
                            volLbl?.text = "$progress%"
                            volBtn?.setImageResource(if (targetVol > 0) R.drawable.ic_volume else R.drawable.ic_volume_mute)
                        }
                        resetAutoCollapseTimer()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })

            volBtn?.setOnClickListener {
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                try {
                    if (cur > 0) {
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                        volSlider?.progress = 0
                        volLbl?.text = "0%"
                        volBtn.setImageResource(R.drawable.ic_volume_mute)
                    } else {
                        val half = (maxVol / 2).coerceAtLeast(1)
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, half, 0)
                        val pct = half * 100 / maxVol
                        volSlider?.progress = pct
                        volLbl?.text = "$pct%"
                        volBtn.setImageResource(R.drawable.ic_volume)
                    }
                } catch (e: Exception) {}
                resetAutoCollapseTimer()
            }
        }

        // Focus Pomodoro Controls
        setupFocusControls(exp)

        // Quick Tools Controls
        setupQuickToolsControls(exp)

        // Apps Quick Launcher Controls
        setupAppsLauncherControls(exp)

        // Navigation Controls
        setupNavControls(exp)
    }

    private fun performCallAction(answer: Boolean) {
        val notif = activeNotification ?: return
        val action = if (answer) notif.answerIntent else notif.declineIntent
        if (action == null) {
            Toast.makeText(this, "This call notification has no ${if (answer) "answer" else "decline"} action", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            action.send()
            vibrateTick()
            activeNotification = null
            notifActiveUntil = 0L
            NotificationStateBridge.dismissCurrent(notif.key)
            collapseIsland(force = true)
            updateViews()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not ${if (answer) "answer" else "decline"} this call", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performMarkReadAction() {
        val notif = activeNotification ?: return
        val action = notif.markReadIntent ?: return
        try {
            action.send()
            vibrateTick()
            activeNotification = null
            notifActiveUntil = 0L
            NotificationStateBridge.dismissCurrent(notif.key)
            updateViews()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not mark this notification as read", Toast.LENGTH_SHORT).show()
        }
    }

    private fun timerStore() = getSharedPreferences("dynamic_island_timer", Context.MODE_PRIVATE)

    private fun restoreCountdownTimer() {
        val store = timerStore()
        timerRunning = store.getBoolean("running", false)
        timerFinishAtMs = store.getLong("finish_at", 0L)
        timerRemainingMs = if (timerRunning) {
            (timerFinishAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
        } else store.getLong("remaining", 0L).coerceAtLeast(0L)
        if (timerRunning && timerRemainingMs == 0L) {
            timerRunning = false
            persistCountdownTimer()
        }
    }

    private fun persistCountdownTimer() {
        timerStore().edit().putBoolean("running", timerRunning)
            .putLong("finish_at", timerFinishAtMs)
            .putLong("remaining", timerRemainingMs).apply()
    }

    private fun startCountdown(minutes: Int) {
        timerRemainingMs = minutes.coerceAtLeast(1) * 60_000L
        timerFinishAtMs = System.currentTimeMillis() + timerRemainingMs
        timerRunning = true
        persistCountdownTimer()
        vibrateTick()
        updateViews()
    }

    private fun toggleCountdown() {
        if (timerRunning) {
            timerRemainingMs = (timerFinishAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
            timerRunning = false
        } else if (timerRemainingMs > 0L) {
            timerFinishAtMs = System.currentTimeMillis() + timerRemainingMs
            timerRunning = true
        } else {
            Toast.makeText(this, "Choose 5, 15, or 30 minutes first", Toast.LENGTH_SHORT).show()
            return
        }
        persistCountdownTimer()
        vibrateTick()
        updateViews()
    }

    private fun stopCountdown() {
        timerRunning = false
        timerFinishAtMs = 0L
        timerRemainingMs = 0L
        persistCountdownTimer()
        vibrateTick()
        updateViews()
    }

    private fun formatCountdown(milliseconds: Long): String {
        val seconds = (milliseconds.coerceAtLeast(0L) + 999L) / 1000L
        return "%02d:%02d".format(seconds / 60L, seconds % 60L)
    }

    private fun batteryChargeDescription(batteryIntent: Intent?, percent: Int, charging: Boolean): String {
        val now = SystemClock.elapsedRealtime()
        if (previousBatteryCharging != charging) {
            previousBatteryCharging = charging
            previousBatteryPercent = percent
            previousBatterySampleAtMs = now
            batteryPercentPerHour = 0.0
        } else if (percent != previousBatteryPercent) {
            val elapsed = now - previousBatterySampleAtMs
            val gained = percent - previousBatteryPercent
            if (charging && gained > 0 && elapsed >= 15_000L) {
                val sample = gained * 3_600_000.0 / elapsed
                batteryPercentPerHour = if (batteryPercentPerHour > 0.0) batteryPercentPerHour * 0.65 + sample * 0.35 else sample
            }
            previousBatteryPercent = percent
            previousBatterySampleAtMs = now
        }

        if (!charging) return if (percent <= 15) "Low battery · connect charger" else "On battery"
        val parts = mutableListOf("Charging")
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val currentUa = try {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: Int.MIN_VALUE
        } catch (e: Exception) { Int.MIN_VALUE }
        val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val currentMagnitude = if (currentUa == Int.MIN_VALUE) 0.0 else kotlin.math.abs(currentUa.toDouble())
        if (currentMagnitude > 0.0 && voltageMv > 0) {
            val watts = currentMagnitude * voltageMv / 1_000_000_000.0
            if (watts >= 0.05) parts += "%.1f W".format(Locale.getDefault(), watts)
        }

        var minutesToFull: Long? = null
        val chargeCounter = try {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: Int.MIN_VALUE
        } catch (e: Exception) { Int.MIN_VALUE }
        if (chargeCounter > 0 && currentMagnitude > 0.0 && percent in 1..99) {
            val fullCapacity = chargeCounter * 100.0 / percent
            minutesToFull = (((fullCapacity - chargeCounter).coerceAtLeast(0.0) / currentMagnitude) * 60.0).toLong()
        } else if (batteryPercentPerHour > 0.1 && percent < 100) {
            minutesToFull = ((100 - percent) / batteryPercentPerHour * 60.0).toLong()
        }
        if (minutesToFull != null && minutesToFull in 1L..(7L * 24L * 60L)) {
            val hours = minutesToFull / 60L
            val minutes = minutesToFull % 60L
            parts += if (hours > 0L) "~${hours}h ${minutes}m to full" else "~${minutes}m to full"
        } else if (percent < 100) {
            parts += "estimating time to full"
        } else {
            parts += "Fully charged"
        }
        return parts.joinToString(" · ")
    }

    private fun setupNavControls(exp: View) {
        exp.findViewById<View>(R.id.btn_open_maps)?.setOnClickListener {
            vibrateTick()
            collapseIsland(force = true)
            launchPackageOrWeb("com.google.android.apps.maps", "https://maps.google.com")
        }
    }

    private fun updateNavUI() {
        val exp = expandedView ?: return
        val distTv = exp.findViewById<TextView>(R.id.nav_distance_tv)
        val manTv = exp.findViewById<TextView>(R.id.nav_maneuver_tv)
        val etaTv = exp.findViewById<TextView>(R.id.nav_eta_tv)
        val iconIv = exp.findViewById<ImageView>(R.id.nav_turn_icon)

        if (isNavigating) {
            distTv?.text = navDistance.ifEmpty { "Navigating" }
            manTv?.text = navManeuver.ifEmpty { "Follow route" }
            etaTv?.text = navEta.ifEmpty { "Google Maps Active" }
            if (navIcon != null && !navIcon!!.isRecycled) {
                iconIv?.clearColorFilter()
                iconIv?.imageTintList = null
                iconIv?.setImageBitmap(navIcon)
            } else {
                iconIv?.setImageResource(R.drawable.ic_navigation)
                iconIv?.imageTintList = ColorStateList.valueOf(Color.parseColor("#4ADE80"))
            }
        } else {
            distTv?.text = "No Active Navigation"
            manTv?.text = "Start directions in Google Maps"
            etaTv?.text = "Tap Maps button to plan a route"
            iconIv?.setImageResource(R.drawable.ic_navigation)
            iconIv?.imageTintList = ColorStateList.valueOf(Color.parseColor("#8E9099"))
        }
    }

    private fun switchTab(tabIndex: Int) {
        activeTab = tabIndex.coerceIn(0, 5)
        val exp = expandedView ?: return
        vibrateTick()
        triggerExpandedAura(parseAccentColor(), 900L)
        resetAutoCollapseTimer()

        val tabs = listOf(
            exp.findViewById<TextView>(R.id.tab_media),
            exp.findViewById<TextView>(R.id.tab_system),
            exp.findViewById<TextView>(R.id.tab_focus),
            exp.findViewById<TextView>(R.id.tab_tools),
            exp.findViewById<TextView>(R.id.tab_apps),
            exp.findViewById<TextView>(R.id.tab_nav)
        )
        val pages = listOf(
            exp.findViewById<View>(R.id.page_media),
            exp.findViewById<View>(R.id.page_system),
            exp.findViewById<View>(R.id.page_focus),
            exp.findViewById<View>(R.id.page_tools),
            exp.findViewById<View>(R.id.page_apps),
            exp.findViewById<View>(R.id.page_nav)
        )

        for (i in tabs.indices) {
            val selected = (i == activeTab)
            tabs[i]?.setBackgroundResource(if (selected) R.drawable.bg_tab_selected else R.drawable.bg_tab_unselected)
            tabs[i]?.setTextColor(if (selected) Color.parseColor("#E3E2E6") else Color.parseColor("#8E9099"))
            pages[i]?.visibility = if (selected) View.VISIBLE else View.GONE
        }

        when (activeTab) {
            1 -> {
                probeNetworkRouteAndWan(forceWan = cachedWanIp == "…" || cachedWanIp == "Offline")
                updateSystemMetrics()
            }
            2 -> updateFocusUI()
            3 -> updateToolsState()
            5 -> updateNavUI()
        }
    }

    private fun setupFocusControls(exp: View) {
        val chip5 = exp.findViewById<TextView>(R.id.chip_5m)
        val chip15 = exp.findViewById<TextView>(R.id.chip_15m)
        val chip25 = exp.findViewById<TextView>(R.id.chip_25m)
        val chip45 = exp.findViewById<TextView>(R.id.chip_45m)
        val chips = listOf(chip5 to 5, chip15 to 15, chip25 to 25, chip45 to 50)

        for ((chipView, mins) in chips) {
            chipView?.setOnClickListener {
                vibrateTick()
                focusRunning = false
                focusTotalSec = mins * 60
                focusRemainingSec = focusTotalSec
                for ((cv, m) in chips) {
                    val sel = (m == mins)
                    cv?.setBackgroundResource(if (sel) R.drawable.bg_tab_selected else R.drawable.bg_tab_unselected)
                    cv?.setTextColor(if (sel) Color.parseColor("#E3E2E6") else Color.parseColor("#C4C6D0"))
                }
                updateFocusUI()
                resetAutoCollapseTimer()
            }
        }

        exp.findViewById<TextView>(R.id.btn_focus_toggle)?.setOnClickListener {
            vibrateTick()
            focusRunning = !focusRunning
            triggerExpandedAura(Color.parseColor("#E7B9F5"))
            updateFocusUI()
            updateViews()
            resetAutoCollapseTimer()
        }

        exp.findViewById<TextView>(R.id.btn_focus_reset)?.setOnClickListener {
            vibrateTick()
            focusRunning = false
            focusRemainingSec = focusTotalSec
            updateFocusUI()
            updateViews()
            resetAutoCollapseTimer()
        }
    }

    private fun updateFocusUI() {
        val exp = expandedView ?: return
        val ringView = exp.findViewById<FocusRingView>(R.id.focus_ring_view)
        val toggleBtn = exp.findViewById<TextView>(R.id.btn_focus_toggle)

        val m = focusRemainingSec / 60
        val s = focusRemainingSec % 60
        val timeStr = "%02d:%02d".format(m, s)
        val frac = if (focusTotalSec > 0) focusRemainingSec.toFloat() / focusTotalSec.toFloat() else 1f

        val statusLabel = when {
            focusRunning -> "FOCUSING"
            focusRemainingSec < focusTotalSec -> "PAUSED"
            else -> "READY"
        }

        ringView?.setState(frac, timeStr, statusLabel, parseAccentColor())

        if (focusRunning) {
            toggleBtn?.text = "⏸ Pause"
        } else if (focusRemainingSec < focusTotalSec) {
            toggleBtn?.text = "▶ Resume"
        } else {
            toggleBtn?.text = "▶ Start"
        }
    }

    private fun setupQuickToolsControls(exp: View) {
        exp.findViewById<TextView>(R.id.tile_torch)?.setOnClickListener {
            toggleFlashlight()
            triggerExpandedAura(Color.parseColor("#FFB86C"))
            resetAutoCollapseTimer()
        }

        exp.findViewById<TextView>(R.id.tile_ringer)?.setOnClickListener {
            vibrateTick()
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null) {
                try {
                    am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
                } catch (e: Exception) {}
            }
            updateToolsState()
            resetAutoCollapseTimer()
        }

        exp.findViewById<TextView>(R.id.tile_camera)?.setOnClickListener {
            vibrateTick()
            collapseIsland(force = true)
            launchSafeIntent(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        }

        exp.findViewById<TextView>(R.id.tile_calc)?.setOnClickListener {
            vibrateTick()
            collapseIsland(force = true)
            val calcIntent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)
            launchSafeIntent(calcIntent)
        }

        exp.findViewById<TextView>(R.id.tile_alarms)?.setOnClickListener {
            vibrateTick()
            collapseIsland(force = true)
            launchSafeIntent(Intent(AlarmClock.ACTION_SHOW_ALARMS))
        }

        exp.findViewById<TextView>(R.id.tile_settings)?.setOnClickListener {
            vibrateTick()
            collapseIsland(force = true)
            launchSafeIntent(Intent(this, MainActivity::class.java))
        }

        // Clipboard share / inspect action
        exp.findViewById<TextView>(R.id.tv_clipboard_content)?.setOnClickListener {
            vibrateTick()
            refreshClipboardFromSystem()
            if (lastClipboardText.isNotBlank()) {
                collapseIsland(force = true)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, lastClipboardText)
                }
                val chooser = Intent.createChooser(share, "Share Clipboard Text").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    startActivity(chooser)
                } catch (e: Exception) {}
            }
        }

        // Quick Wi-Fi / Internet panel launcher (Android 10+ Settings Panel)
        exp.findViewById<TextView>(R.id.btn_wifi_panel)?.setOnClickListener {
            vibrateTick()
            collapseIsland(force = true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                launchSafeIntent(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
            } else {
                launchSafeIntent(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
        }
    }

    private fun setupAppsLauncherControls(exp: View) {
        exp.findViewById<TextView>(R.id.app_spotify)?.setOnClickListener {
            vibrateTick()
            collapseIsland(force = true)
            launchPackageOrWeb("com.spotify.music", "https://open.spotify.com")
        }

        exp.findViewById<TextView>(R.id.app_youtube)?.setOnClickListener {
            vibrateTick()
            collapseIsland(force = true)
            launchPackageOrWeb("com.google.android.youtube", "https://m.youtube.com")
        }

        exp.findViewById<TextView>(R.id.app_browser)?.setOnClickListener {
            vibrateTick()
            collapseIsland(force = true)
            launchSafeIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")))
        }

        exp.findViewById<TextView>(R.id.app_whatsapp)?.setOnClickListener {
            vibrateTick()
            collapseIsland(force = true)
            launchPackageOrWeb("com.whatsapp", "https://web.whatsapp.com")
        }

        exp.findViewById<TextView>(R.id.app_instagram)?.setOnClickListener {
            vibrateTick()
            collapseIsland(force = true)
            launchPackageOrWeb("com.instagram.android", "https://www.instagram.com")
        }

        exp.findViewById<TextView>(R.id.app_maps)?.setOnClickListener {
            vibrateTick()
            collapseIsland(force = true)
            launchPackageOrWeb("com.google.android.apps.maps", "https://maps.google.com")
        }

        exp.findViewById<TextView>(R.id.app_phone)?.setOnClickListener {
            vibrateTick()
            collapseIsland(force = true)
            launchSafeIntent(Intent(Intent.ACTION_DIAL))
        }

        exp.findViewById<TextView>(R.id.app_messages)?.setOnClickListener {
            vibrateTick()
            collapseIsland(force = true)
            val smsIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_MESSAGING)
            }
            launchSafeIntent(smsIntent)
        }

        exp.findViewById<TextView>(R.id.app_files)?.setOnClickListener {
            vibrateTick()
            collapseIsland(force = true)
            launchSafeIntent(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun launchPackageOrWeb(pkg: String, fallbackUrl: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            }
        } catch (e: Exception) {}
        launchSafeIntent(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)))
    }

    private fun refreshClipboardFromSystem() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val txt = clip.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: ""
                if (txt.isNotEmpty()) {
                    lastClipboardText = txt
                }
            }
        } catch (e: Exception) {}
    }

    private fun updateToolsState() {
        val exp = expandedView ?: return
        val torchTile = exp.findViewById<TextView>(R.id.tile_torch)
        torchTile?.text = if (isTorchOn) "⚡ Torch ON" else "⚡ Torch Off"
        torchTile?.setBackgroundResource(if (isTorchOn) R.drawable.bg_tool_tile_active else R.drawable.bg_tool_tile)

        val ringerTile = exp.findViewById<TextView>(R.id.tile_ringer)
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val modeText = when (am?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "🔕 Silent"
            AudioManager.RINGER_MODE_VIBRATE -> "📳 Vibrate"
            else -> "🔔 Ring"
        }
        ringerTile?.text = modeText

        refreshClipboardFromSystem()
        val clipTag = exp.findViewById<TextView>(R.id.tv_clipboard_tag)
        val clipTv = exp.findViewById<TextView>(R.id.tv_clipboard_content)
        clipTag?.setTextColor(parseAccentColor())
        clipTv?.text = if (lastClipboardText.isNotBlank()) {
            lastClipboardText.replace("\n", " ")
        } else {
            "Nothing copied yet · Tap to refresh"
        }
    }

    // ───────────────────────── Live Network Traffic & VPN / LAN / WAN ─────────────────────────

    private fun sampleNetworkTraffic() {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val now = SystemClock.elapsedRealtime()

        if (rx != TrafficStats.UNSUPPORTED.toLong() && tx != TrafficStats.UNSUPPORTED.toLong()) {
            if (prevRxBytes >= 0L && prevTxBytes >= 0L && now > prevTrafficTimeMs) {
                val dtSec = (now - prevTrafficTimeMs) / 1000f
                if (dtSec >= 0.2f) {
                    val dRx = (rx - prevRxBytes).coerceAtLeast(0L)
                    val dTx = (tx - prevTxBytes).coerceAtLeast(0L)
                    curDownBps = dRx / dtSec
                    curUpBps = dTx / dtSec
                    netScalePeak = max(125_000f, max(netScalePeak * 0.97f, max(curDownBps, curUpBps)))
                }
            }
            prevRxBytes = rx
            prevTxBytes = tx
            prevTrafficTimeMs = now
        }

        probeNetworkRouteAndWan(forceWan = false)

        expandedView?.findViewById<NetworkGraphView>(R.id.network_graph_strip)?.pushSample(
            upBytesPerSec = curUpBps,
            downBytesPerSec = curDownBps,
            lan = cachedLanIp,
            wan = cachedWanIp,
            isVpn = isVpnActive,
            wanVpnColorEnabled = prefs.wanVpnColor
        )
    }

    private fun probeNetworkRouteAndWan(forceWan: Boolean) {
        val vpnNow = detectVpnState()
        val lanNow = getLocalLanIpAddress()
        isVpnActive = vpnNow
        cachedLanIp = lanNow.ifBlank { "—" }

        val sig = "$lanNow|$vpnNow"
        val now = SystemClock.elapsedRealtime()
        val routeChanged = sig != lastRouteSignature
        if (routeChanged) {
            lastRouteSignature = sig
        }

        // Refresh WAN IP when route/VPN state changes, when forced, or every 60s while viewing System tab
        val shouldFetchWan = forceWan || routeChanged || (isExpanded && activeTab == 1 && (now - lastWanFetchMs > 60_000L))
        if (shouldFetchWan && !wanFetchBusy) {
            wanFetchBusy = true
            lastWanFetchMs = now
            bgExecutor.execute {
                val wan = fetchPublicWanIp()
                handler.post {
                    cachedWanIp = if (wan.isNotEmpty()) wan else if (lanNow.isEmpty()) "Offline" else cachedWanIp
                    wanFetchBusy = false
                    if (isExpanded && activeTab == 1) {
                        expandedView?.findViewById<NetworkGraphView>(R.id.network_graph_strip)?.invalidate()
                    }
                }
            }
        }
    }

    private fun detectVpnState(): Boolean {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                @Suppress("DEPRECATION")
                val allNets = cm.allNetworks
                for (net in allNets) {
                    val caps = cm.getNetworkCapabilities(net) ?: continue
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {}

        // Also check active network interfaces for tun/ppp/wg/nordlynx/tailscale/tap tunnels
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            val vpnNames = listOf("tun", "ppp", "wg", "nordlynx", "tailscale", "tap", "ipsec", "utun")
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val low = intf.name.lowercase(Locale.US)
                if (vpnNames.any { low.startsWith(it) || low.contains(it) }) {
                    if (intf.inetAddresses.hasMoreElements()) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {}
        return false
    }

    private fun getLocalLanIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ""
            var fallbackIp = ""
            val tunnelPrefixes = listOf("tun", "ppp", "wg", "tap", "ipsec")
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase(Locale.US)
                val isTunnel = tunnelPrefixes.any { name.startsWith(it) }
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("169.254.")) continue
                        // Prefer physical Wi-Fi / Ethernet / Cellular interface over VPN tunnel IP for LAN display
                        if (!isTunnel && (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("rmnet") || name.startsWith("ccmni"))) {
                            return ip
                        }
                        if (!isTunnel && fallbackIp.isEmpty()) {
                            fallbackIp = ip
                        } else if (fallbackIp.isEmpty()) {
                            fallbackIp = ip
                        }
                    }
                }
            }
            return fallbackIp
        } catch (e: Exception) {}
        return ""
    }

    private fun fetchPublicWanIp(): String {
        val endpoints = listOf(
            "https://api.ipify.org",
            "https://icanhazip.com",
            "https://checkip.amazonaws.com"
        )
        for (urlStr in endpoints) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3500
                    readTimeout = 3500
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "DynamicIslandPro/1.0")
                }
                if (conn.responseCode == 200) {
                    BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                        val line = reader.readLine()?.trim() ?: ""
                        if (line.count { it == '.' } == 3 && line.length in 7..15) {
                            return line
                        }
                    }
                }
            } catch (e: Exception) {
                // try next endpoint
            } finally {
                try {
                    conn?.disconnect()
                } catch (e: Exception) {}
            }
        }
        return ""
    }

    private fun formatRateNumberAndUnit(bps: Float): Pair<String, String> {
        val bits = bps * 8f
        return when {
            bits < 999_500f -> Pair("%.0f".format(Locale.US, bits / 1e3f), "Kbps")
            bits < 99.95e6f -> Pair("%.1f".format(Locale.US, bits / 1e6f), "Mbps")
            bits < 999.5e6f -> Pair("%.0f".format(Locale.US, bits / 1e6f), "Mbps")
            else -> Pair("%.2f".format(Locale.US, bits / 1e9f), "Gbps")
        }
    }

    private fun updateSystemMetrics() {
        val exp = expandedView ?: return

        // 1. Memory (RAM) Gauge
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val totalGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
        val availGb = memInfo.availMem / (1024.0 * 1024.0 * 1024.0)
        val usedGb = (totalGb - availGb).coerceAtLeast(0.0)
        val ramPct = if (totalGb > 0) ((usedGb / totalGb) * 100).toInt() else 0
        exp.findViewById<RingGaugeView>(R.id.gauge_ram)?.setData(
            percent = ramPct,
            label = "Memory",
            subtext = "%.1f/%.0fG".format(Locale.US, usedGb, totalGb),
            color = Color.parseColor("#7FD88F")
        )

        // 2. Battery & Temperature Gauge
        val batIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 100
        val status = batIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val tempTenths = batIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 300) ?: 300
        val tempC = tempTenths / 10.0
        val batColor = if (charging) Color.parseColor("#7FD88F") else Color.parseColor("#FFB86C")
        exp.findViewById<RingGaugeView>(R.id.gauge_battery)?.setData(
            percent = batPct,
            label = "Battery",
            subtext = "%.0f°C·%s".format(Locale.US, tempC, if (charging) "Chg" else "Idle"),
            color = batColor
        )

        // 3. Internal Disk / Storage Gauge
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalBytes = stat.blockSizeLong * stat.blockCountLong
            val availBytes = stat.blockSizeLong * stat.availableBlocksLong
            val usedBytes = totalBytes - availBytes
            val diskPct = if (totalBytes > 0) ((usedBytes * 100L) / totalBytes).toInt() else 0
            val freeGb = availBytes / (1024.0 * 1024.0 * 1024.0)
            exp.findViewById<RingGaugeView>(R.id.gauge_storage)?.setData(
                percent = diskPct,
                label = "Storage",
                subtext = "%.0fG free".format(Locale.US, freeGb),
                color = Color.parseColor("#E89AB5")
            )
        } catch (e: Exception) {}

        // 4. Download Speed Ring Gauge (matches PC MONITORS["net_down"])
        val (dlNum, dlUnit) = formatRateNumberAndUnit(curDownBps)
        val dlPct = min(100f, (curDownBps / netScalePeak) * 100f).toInt()
        exp.findViewById<RingGaugeView>(R.id.gauge_net_down)?.setData(
            percent = dlPct,
            label = "Download",
            subtext = dlUnit,
            color = Color.parseColor("#F5C04A"),
            valueText = dlNum
        )

        // 5. Upload Speed Ring Gauge (matches PC MONITORS["net_up"])
        val (ulNum, ulUnit) = formatRateNumberAndUnit(curUpBps)
        val ulPct = min(100f, (curUpBps / netScalePeak) * 100f).toInt()
        exp.findViewById<RingGaugeView>(R.id.gauge_net_up)?.setData(
            percent = ulPct,
            label = "Upload",
            subtext = ulUnit,
            color = Color.parseColor("#6FD8F5"),
            valueText = ulNum
        )
    }

    private fun getBatteryPercent(): Int {
        val batIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (level >= 0 && scale > 0) (level * 100 / scale) else 100
    }

    private fun launchSafeIntent(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {}
    }

    private fun expandIsland() {
        if (isExpanded) return
        isExpanded = true

        compactView?.visibility = View.GONE
        expandedView?.visibility = View.VISIBLE

        try {
            if (expandedView?.parent == null) {
                windowManager.addView(expandedView, expandedParams)
            } else {
                windowManager.updateViewLayout(expandedView, expandedParams)
            }
        } catch (e: Exception) {}

        // Sync volume & metrics on open
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        expandedView?.let { exp ->
            if (am != null) {
                syncVolumeUI(
                    am,
                    exp.findViewById(R.id.slider_volume),
                    exp.findViewById(R.id.tv_volume_lbl),
                    exp.findViewById(R.id.btn_vol_icon)
                )
            }
        }
        updateViews()
        when (activeTab) {
            1 -> {
                probeNetworkRouteAndWan(forceWan = cachedWanIp == "…")
                updateSystemMetrics()
            }
            2 -> updateFocusUI()
            3 -> updateToolsState()
        }

        triggerExpandedAura(parseAccentColor(), 1200L)

        expandedView?.scaleX = 0.84f
        expandedView?.scaleY = 0.84f
        expandedView?.alpha = 0f
        expandedView?.animate()
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.alpha(1f)
            ?.setDuration(300)
            ?.setInterpolator(OvershootInterpolator(1.15f))
            ?.start()

        resetAutoCollapseTimer()
    }

    private fun collapseIsland(force: Boolean = false) {
        if (!isExpanded) return
        if (isPinned && !force) return
        isExpanded = false

        expandedView?.animate()
            ?.scaleX(0.84f)
            ?.scaleY(0.84f)
            ?.alpha(0f)
            ?.setDuration(210)
            ?.withEndAction {
                if (expandedView?.parent != null) {
                    try {
                        windowManager.removeView(expandedView)
                    } catch (e: Exception) {}
                }
                compactView?.visibility = View.VISIBLE
                compactView?.scaleX = 1f
                compactView?.scaleY = 1f
                compactView?.alpha = 1f
                updateViews()
            }
            ?.start()
    }

    private fun resetAutoCollapseTimer() {
        collapseRunnable?.let { handler.removeCallbacks(it) }
        if (isPinned) return
        collapseRunnable = Runnable {
            if (isExpanded && !isPinned && !isPlaying && !focusRunning && activeTab != 1) {
                collapseIsland()
            }
        }
        handler.postDelayed(collapseRunnable!!, 15000L)
    }

    private fun syncVolumeUI(am: AudioManager, slider: SeekBar?, lbl: TextView?, btn: ImageButton?) {
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val curPct = if (maxVol > 0) (curVol * 100 / maxVol) else 0
        slider?.progress = curPct
        lbl?.text = "$curPct%"
        btn?.setImageResource(if (curVol > 0) R.drawable.ic_volume else R.drawable.ic_volume_mute)
    }

    private fun parseAccentColor(): Int {
        return try {
            Color.parseColor(prefs.accentColorHex)
        } catch (e: Exception) {
            Color.parseColor("#A8C7FA")
        }
    }

    private fun triggerExpandedAura(color: Int, durationMs: Long = 1400L) {
        (expandedView as? IslandContainerView)?.triggerAura(color, durationMs)
    }

    private fun shouldShowCompactIsland(now: Long): Boolean {
        val hasMediaSession = currentTitle.isNotBlank() && currentTitle != "Nothing playing"
        val hasNotification = activeNotification != null && now < notifActiveUntil
        val hasToast = toastMessage.isNotBlank() && now < toastActiveUntil
        val hasTimer = timerRunning || timerRemainingMs > 0L || focusRunning
        val hasNavigation = isNavigating && prefs.showMapsNavigation
        val hasDeviceActivity = isTorchOn || (isHeadsetPlugged && prefs.showHeadsetConnected)
        return isPlaying || hasMediaSession || hasNotification || hasToast || hasTimer || hasNavigation || hasDeviceActivity
    }

    private fun updateViews() {
        val accentColor = parseAccentColor()
        val now = SystemClock.elapsedRealtime()
        val nowDate = Date()

        // Keep the notch out of the way unless it has something useful to show.
        // GONE removes its touch region too, so taps pass through to the app below.
        val shouldShowIsland = isExpanded || shouldShowCompactIsland(now)
        compactView?.let { view ->
            if (isExpanded) {
                view.animate().cancel()
                view.visibility = View.GONE
                view.alpha = 1f
                view.scaleX = 1f
                view.scaleY = 1f
            } else if (shouldShowIsland && view.visibility != View.VISIBLE) {
                view.animate().cancel()
                view.visibility = View.VISIBLE
                view.alpha = 0f
                view.scaleX = 0.94f
                view.scaleY = 0.94f
                view.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            } else if (!shouldShowIsland && view.visibility == View.VISIBLE) {
                view.animate().cancel()
                view.animate()
                    .alpha(0f)
                    .scaleX(0.96f)
                    .scaleY(0.96f)
                    .setDuration(150L)
                    .withEndAction {
                        if (!isExpanded && !shouldShowCompactIsland(SystemClock.elapsedRealtime())) {
                            view.visibility = View.GONE
                        }
                        view.alpha = 1f
                        view.scaleX = 1f
                        view.scaleY = 1f
                    }
                    .start()
            }
        }

        // 1. Update Compact Pill
        compactView?.let { v ->
            val container = v as? IslandContainerView
            container?.accentColor = accentColor

            val titleTv = v.findViewById<TextView>(R.id.compact_title)
            val iconIv = v.findViewById<ImageView>(R.id.compact_icon)
            val badgeContainer = v.findViewById<View>(R.id.compact_badge_container)
            val trailingTv = v.findViewById<TextView>(R.id.compact_trailing)
            val visView = v.findViewById<WaveformVisualizerView>(R.id.compact_visualizer)

            badgeContainer?.backgroundTintList = ColorStateList.valueOf(accentColor)
            visView?.setColors(accentColor)

            // Update bottom mini progress bar on compact pill
            val frac = if (isPlaying && currentDur > 0L) {
                (currentPos.toFloat() / currentDur.toFloat()).coerceIn(0f, 1f)
            } else 0f
            container?.progressFraction = frac

            if (now < toastActiveUntil && toastMessage.isNotEmpty()) {
                titleTv?.text = toastMessage
                visView?.visibility = View.GONE
                trailingTv?.visibility = View.VISIBLE
                trailingTv?.text = "●"
                trailingTv?.setTextColor(accentColor)
            } else if (now < notifActiveUntil && activeNotification != null && prefs.showAppNotifications) {
                // App Notification Alert Mode (WhatsApp, Telegram, Instagram, etc.)
                val notif = activeNotification!!
                val pad = (2 * resources.displayMetrics.density).toInt()
                iconIv?.setPadding(pad, pad, pad, pad)
                if (notif.icon != null && !notif.icon.isRecycled) {
                    iconIv?.clearColorFilter()
                    iconIv?.imageTintList = null
                    iconIv?.setImageBitmap(notif.icon)
                } else {
                    iconIv?.setImageResource(R.drawable.ic_music)
                    iconIv?.imageTintList = ColorStateList.valueOf(Color.parseColor("#062E6F"))
                }

                val notifText = if (notif.title.isNotEmpty() && notif.text.isNotEmpty()) {
                    "${notif.title}: ${notif.text}"
                } else if (notif.title.isNotEmpty()) {
                    notif.title
                } else {
                    notif.text
                }
                titleTv?.text = "${notif.appName} · $notifText"
                visView?.visibility = View.GONE
                trailingTv?.visibility = View.VISIBLE
                trailingTv?.text = "💬"
                val notifColor = getNotificationAccentColor(notif.packageName)
                trailingTv?.setTextColor(notifColor)
            } else if (isNavigating && prefs.showMapsNavigation) {
                // Navigation Active Mode (Google Maps directions)
                val pad = (3 * resources.displayMetrics.density).toInt()
                iconIv?.setPadding(pad, pad, pad, pad)
                if (navIcon != null && !navIcon!!.isRecycled) {
                    iconIv?.clearColorFilter()
                    iconIv?.imageTintList = null
                    iconIv?.setImageBitmap(navIcon)
                } else {
                    iconIv?.setImageResource(R.drawable.ic_navigation)
                    iconIv?.imageTintList = ColorStateList.valueOf(Color.parseColor("#062E6F"))
                }

                val dist = if (navDistance.isNotBlank()) navDistance else "Turn"
                val maneuver = if (navManeuver.isNotBlank()) " · $navManeuver" else ""
                titleTv?.text = "$dist$maneuver"
                visView?.visibility = View.GONE
                trailingTv?.visibility = View.VISIBLE
                trailingTv?.text = if (navEta.isNotBlank()) navEta.substringBefore("·").trim() else "🧭"
                trailingTv?.setTextColor(Color.parseColor("#4ADE80"))
            } else if (isPlaying || (currentTitle.isNotEmpty() && currentTitle != "Nothing playing")) {
                titleTv?.text = currentTitle
                if (currentAlbumArt != null && !currentAlbumArt!!.isRecycled) {
                    iconIv?.setPadding(0, 0, 0, 0)
                    iconIv?.clearColorFilter()
                    iconIv?.imageTintList = null
                    iconIv?.setImageBitmap(currentAlbumArt)
                } else {
                    val pad = (4 * resources.displayMetrics.density).toInt()
                    iconIv?.setPadding(pad, pad, pad, pad)
                    iconIv?.setImageResource(if (isHeadsetPlugged) R.drawable.ic_headphones else R.drawable.ic_music)
                    iconIv?.imageTintList = ColorStateList.valueOf(Color.parseColor("#062E6F"))
                }

                if (isPlaying) {
                    visView?.visibility = View.VISIBLE
                    visView?.setPlaying(true)
                    trailingTv?.visibility = View.GONE
                } else {
                    visView?.setPlaying(false)
                    visView?.visibility = View.GONE
                    trailingTv?.visibility = View.VISIBLE
                    trailingTv?.text = "⏸"
                    trailingTv?.setTextColor(accentColor)
                }
            } else if (focusRunning) {
                val pad = (4 * resources.displayMetrics.density).toInt()
                iconIv?.setPadding(pad, pad, pad, pad)
                iconIv?.setImageResource(R.drawable.ic_music)
                iconIv?.imageTintList = ColorStateList.valueOf(Color.parseColor("#062E6F"))

                val m = focusRemainingSec / 60
                val s = focusRemainingSec % 60
                titleTv?.text = "Focus %02d:%02d".format(m, s)
                visView?.visibility = View.GONE
                trailingTv?.visibility = View.VISIBLE
                trailingTv?.text = "⏱"
                trailingTv?.setTextColor(Color.parseColor("#E7B9F5"))
            } else if (timerRunning || timerRemainingMs > 0L) {
                titleTv?.text = if (timerRunning) "Timer" else "Timer paused"
                visView?.setPlaying(false)
                visView?.visibility = View.GONE
                trailingTv?.visibility = View.VISIBLE
                trailingTv?.text = formatCountdown(if (timerRunning) timerFinishAtMs - System.currentTimeMillis() else timerRemainingMs)
                trailingTv?.setTextColor(accentColor)
            } else {
                // An idle island is hidden below; this branch only resets the recycled view state.
                val pad = (4 * resources.displayMetrics.density).toInt()
                iconIv?.setPadding(pad, pad, pad, pad)
                val iconRes = when {
                    isTorchOn -> R.drawable.ic_bolt
                    isHeadsetPlugged -> R.drawable.ic_headphones
                    else -> R.drawable.ic_music
                }
                iconIv?.setImageResource(iconRes)
                iconIv?.imageTintList = ColorStateList.valueOf(Color.parseColor("#062E6F"))

                visView?.setPlaying(false)
                visView?.visibility = View.GONE
                trailingTv?.visibility = View.VISIBLE

                titleTv?.text = ""
                trailingTv?.text = ""
                trailingTv?.setTextColor(accentColor)
            }
        }

        // 2. Update Expanded Card
        expandedView?.let { v ->
            (v as? IslandContainerView)?.accentColor = accentColor
            val hasMediaSession = currentTitle.isNotBlank() && currentTitle != "Nothing playing"
            v.findViewById<View>(R.id.page_media)?.visibility = if (hasMediaSession) View.VISIBLE else View.GONE

            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val batteryLevel = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val batteryScale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val batteryPercent = if (batteryLevel >= 0 && batteryScale > 0) batteryLevel * 100 / batteryScale else 100
            val batteryStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val batteryPlug = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val batteryTemp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val isCharging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING || batteryStatus == BatteryManager.BATTERY_STATUS_FULL
            val fullyCharged = batteryStatus == BatteryManager.BATTERY_STATUS_FULL
            val actuallyCharging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING || (batteryPlug != 0 && !fullyCharged)
            val batteryState = if (fullyCharged) "Fully charged" else batteryChargeDescription(batteryIntent, batteryPercent, actuallyCharging)
            val temperature = if (batteryTemp >= 0) " · ${batteryTemp / 10}°C" else ""
            v.findViewById<TextView>(R.id.battery_percent)?.apply {
                text = "$batteryPercent%"
                setTextColor(when {
                    actuallyCharging -> Color.parseColor("#7FD88F")
                    batteryPercent <= 15 -> Color.parseColor("#FFB4AB")
                    else -> Color.parseColor("#E3E2E6")
                })
            }
            v.findViewById<TextView>(R.id.battery_state)?.text = batteryState + temperature
            if (batteryPercent > 20 || actuallyCharging) {
                lowBatteryAlertShown = false
            } else if (batteryPercent <= 15 && !lowBatteryAlertShown) {
                lowBatteryAlertShown = true
                handler.post {
                    if (isRunning) showToastPill("Low battery · $batteryPercent%", R.drawable.ic_bolt, Color.parseColor("#FFB4AB"))
                }
            }

            val timerText = if (timerRunning) formatCountdown(timerFinishAtMs - System.currentTimeMillis()) else formatCountdown(timerRemainingMs)
            v.findViewById<TextView>(R.id.timer_remaining)?.text = if (timerRunning || timerRemainingMs > 0L) timerText else "Choose a time"
            v.findViewById<TextView>(R.id.timer_status)?.text = when {
                timerRunning -> "Counting down"
                timerRemainingMs > 0L -> "Paused · ${timerText} remaining"
                else -> "Quick countdown"
            }
            v.findViewById<TextView>(R.id.timer_toggle)?.text = when {
                timerRunning -> "Pause"
                timerRemainingMs > 0L -> "Resume"
                else -> "Start"
            }
            v.findViewById<View>(R.id.timer_stop)?.visibility =
                if (timerRunning || timerRemainingMs > 0L) View.VISIBLE else View.GONE

            val navCard = v.findViewById<View>(R.id.nav_live_card)
            if (isNavigating && prefs.showMapsNavigation) {
                navCard?.visibility = View.VISIBLE
                v.findViewById<TextView>(R.id.nav_distance_live)?.text = navDistance.ifBlank { "—" }
                v.findViewById<TextView>(R.id.nav_maneuver_live)?.text = navManeuver.ifBlank { "Continue on your route" }
                v.findViewById<TextView>(R.id.nav_eta_live)?.text = navEta.ifBlank { "Live route" }
            } else {
                navCard?.visibility = View.GONE
            }

            // Update PC-Style Header Clock & Date
            v.findViewById<TextView>(R.id.tv_header_time)?.text =
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(nowDate)
            v.findViewById<TextView>(R.id.tv_header_date)?.text =
                SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(nowDate)

            val titleTv = v.findViewById<TextView>(R.id.expanded_title)
            val artistTv = v.findViewById<TextView>(R.id.expanded_artist)
            val badgeTv = v.findViewById<TextView>(R.id.expanded_badge)
            val artView = v.findViewById<AlbumArtView>(R.id.expanded_art_view)
            val playBtn = v.findViewById<ImageButton>(R.id.btn_play_pause)
            val wavyBar = v.findViewById<WavyProgressBar>(R.id.expanded_wavy_progress)
            val expVis = v.findViewById<WaveformVisualizerView>(R.id.expanded_visualizer)

            playBtn?.backgroundTintList = ColorStateList.valueOf(accentColor)
            wavyBar?.setAccentColor(accentColor)
            expVis?.setColors(accentColor)
            expVis?.setPlaying(isPlaying)

            titleTv?.text = currentTitle
            artistTv?.text = currentArtist
            badgeTv?.text = if (currentApp.isNotEmpty()) {
                "● ${currentApp.uppercase()}"
            } else {
                if (isPlaying) "● PLAYING" else "● IDLE"
            }
            badgeTv?.setTextColor(if (isPlaying) accentColor else Color.parseColor("#8E9099"))

            artView?.setState(currentAlbumArt, isPlaying, accentColor)

            playBtn?.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            wavyBar?.setPlaybackState(currentPos, currentDur, isPlaying)

            // Update Real-Time App Notification Banner
            val banner = v.findViewById<View>(R.id.banner_notification)
            if (activeNotification != null && !activeNotification!!.isExpired && (prefs.showAppNotifications || activeNotification!!.isCall)) {
                banner?.visibility = View.VISIBLE
                val notif = activeNotification!!
                val showCallActions = notif.isCall && notif.answerIntent != null && notif.declineIntent != null
                val showMarkRead = !showCallActions && notif.markReadIntent != null
                v.findViewById<View>(R.id.banner_call_answer)?.visibility = if (showCallActions) View.VISIBLE else View.GONE
                v.findViewById<View>(R.id.banner_call_decline)?.visibility = if (showCallActions) View.VISIBLE else View.GONE
                v.findViewById<View>(R.id.banner_notif_mark_read)?.visibility = if (showMarkRead) View.VISIBLE else View.GONE
                v.findViewById<View>(R.id.banner_notif_btn_open)?.visibility = if (showCallActions) View.GONE else View.VISIBLE
                v.findViewById<View>(R.id.banner_notif_btn_dismiss)?.visibility = if (showCallActions) View.GONE else View.VISIBLE
                val iconView = v.findViewById<ImageView>(R.id.banner_notif_icon)
                if (notif.icon != null && !notif.icon.isRecycled) {
                    iconView?.clearColorFilter()
                    iconView?.imageTintList = null
                    iconView?.setImageBitmap(notif.icon)
                } else {
                    iconView?.setImageResource(R.drawable.ic_music)
                    iconView?.imageTintList = ColorStateList.valueOf(parseAccentColor())
                }
                v.findViewById<TextView>(R.id.banner_notif_app)?.text = notif.appName
                v.findViewById<TextView>(R.id.banner_notif_app)?.setTextColor(getNotificationAccentColor(notif.packageName))
                v.findViewById<TextView>(R.id.banner_notif_title)?.text = notif.title
                v.findViewById<TextView>(R.id.banner_notif_text)?.text = notif.text
            } else {
                banner?.visibility = View.GONE
            }

            if (activeTab == 5) {
                updateNavUI()
            }
        }
    }

    fun showToastPill(text: String, iconRes: Int, auraColor: Int = parseAccentColor()) {
        toastMessage = text
        toastActiveUntil = SystemClock.elapsedRealtime() + 3000L
        val iconIv = compactView?.findViewById<ImageView>(R.id.compact_icon)
        val pad = (4 * resources.displayMetrics.density).toInt()
        iconIv?.setPadding(pad, pad, pad, pad)
        iconIv?.setImageResource(iconRes)
        iconIv?.imageTintList = ColorStateList.valueOf(Color.parseColor("#062E6F"))
        (compactView as? IslandContainerView)?.triggerAura(auraColor, 2200L)
        updateViews()
        handler.postDelayed({ updateViews() }, 3100L)
    }

    private fun vibrateTick() {
        compactView?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun vibrateAlert() {
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(250L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(250L)
            }
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        bgExecutor.shutdownNow()
        MediaStateBridge.removeListener(bridgeListener)
        NavigationStateBridge.removeListener(navListener)
        NotificationStateBridge.removeListener(notifListener)
        try {
            cameraManager?.unregisterTorchCallback(torchCallback)
        } catch (e: Exception) {}
        try {
            unregisterReceiver(mediaUpdateReceiver)
        } catch (e: Exception) {}

        try {
            if (compactView?.parent != null) windowManager.removeView(compactView)
        } catch (e: Exception) {}
        try {
            if (expandedView?.parent != null) windowManager.removeView(expandedView)
        } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
