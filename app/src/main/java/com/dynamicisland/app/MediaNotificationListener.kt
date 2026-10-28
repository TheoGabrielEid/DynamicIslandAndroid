package com.dynamicisland.app

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Locale

class MediaNotificationListener : NotificationListenerService() {

    companion object {
        const val ACTION_COMMAND = "com.dynamicisland.app.MEDIA_COMMAND"
        const val ACTION_SEEK = "com.dynamicisland.app.MEDIA_SEEK"

        @Volatile
        var instance: MediaNotificationListener? = null
            private set
    }

    private var activeController: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isConnected = false

    private val syncRunnable = object : Runnable {
        override fun run() {
            findActiveMediaController()
            dispatchMediaUpdate()
            handler.postDelayed(this, 1500L)
        }
    }

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        pickControllerFromList(controllers)
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                ACTION_COMMAND -> {
                    val cmd = intent.getStringExtra("command") ?: return
                    executeTransportCommand(cmd)
                }
                ACTION_SEEK -> {
                    val posMs = intent.getLongExtra("position_ms", 0L)
                    executeSeek(posMs)
                }
            }
        }
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            dispatchMediaUpdate()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            dispatchMediaUpdate()
        }

        override fun onSessionDestroyed() {
            findActiveMediaController()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val filter = IntentFilter().apply {
            addAction(ACTION_COMMAND)
            addAction(ACTION_SEEK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
        handler.post(syncRunnable)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        isConnected = true
        registerSessionListener()
        findActiveMediaController()
        dispatchMediaUpdate()
        try {
            activeNotifications?.filter { it.packageName == "com.google.android.apps.maps" }
                ?.forEach { processNavigationNotification(it) }
        } catch (e: Exception) {}
    }

    override fun onListenerDisconnected() {
        isConnected = false
        super.onListenerDisconnected()
    }

    private fun registerSessionListener() {
        val sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        val componentName = ComponentName(this, MediaNotificationListener::class.java)
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
            sessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, componentName, handler)
        } catch (e: SecurityException) {
            // Permission not granted yet
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        findActiveMediaController()
        dispatchMediaUpdate()
        processNavigationNotification(sbn)
        processAppNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        findActiveMediaController()
        dispatchMediaUpdate()
        if (sbn?.packageName == "com.google.android.apps.maps") {
            NavigationStateBridge.clear()
        }
        sbn?.key?.let {
            NotificationStateBridge.dismissCurrent(it)
        }
    }

    private fun processNavigationNotification(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != "com.google.android.apps.maps") return
        try {
            val notification = sbn.notification ?: return
            val extras = notification.extras ?: return

            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""

            // Google Maps navigation notifications typically have ongoing=true and contain distance/eta info
            val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0

            if (isOngoing && (title.isNotEmpty() || text.isNotEmpty())) {
                val distancePattern = Regex("""(?i)(\d+(?:[.,]\d+)?)\s*(km|kilometers?|kilometres?|m|meters?|metres?|ft|feet|mi|miles)\b""")
                val sources = listOf(title, text, subText)
                val distanceSource = sources.firstOrNull { distancePattern.containsMatchIn(it) }
                val distanceMatch = distanceSource?.let { distancePattern.find(it) }
                val meters = distanceMatch?.let { match ->
                    val amount = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let null
                    val unit = match.groupValues[2].lowercase(Locale.ROOT)
                    val multiplier = when {
                        unit.startsWith("km") || unit.startsWith("kilo") -> 1000.0
                        unit == "ft" || unit == "feet" -> 0.3048
                        unit == "mi" || unit.startsWith("mile") -> 1609.344
                        else -> 1.0
                    }
                    (amount * multiplier).toLong()
                }
                val distance = meters?.let { String.format(Locale.getDefault(), "%,d m", it) } ?: "—"

                val etaPattern = Regex("""(?i)^\s*\d+\s*(?:min(?:ute)?s?|hr|hours?)(?:\b.*)?$""")
                val maneuver = listOf(text, title).asSequence()
                    .map { source ->
                        distancePattern.replace(source, "")
                            .replace(Regex("""(?i)^\s*(?:in|within|after)\s+"""), "")
                            .replace(Regex("""(?i)\b(?:in|within|after)\s*$"""), "")
                            .trim(' ', '·', '•', ',', '-', '–', ':')
                    }
                    .firstOrNull { it.isNotBlank() && !etaPattern.matches(it) }
                    ?: "Continue on your route"
                val etaSource = if (subText.isNotBlank()) subText else listOf(text, title).firstOrNull { etaPattern.matches(it) }.orEmpty()
                val eta = distancePattern.replace(etaSource, "").trim(' ', '·', '•', ',', '-', '–', ':')

                var navIcon: Bitmap? = null
                val largeIcon = notification.getLargeIcon()
                if (largeIcon != null) {
                    try {
                        val d = largeIcon.loadDrawable(this)
                        if (d is BitmapDrawable && d.bitmap != null) {
                            navIcon = scaleBitmap(d.bitmap, 120)
                        }
                    } catch (e: Exception) {}
                }

                NavigationStateBridge.update(
                    isNavigating = true,
                    maneuver = maneuver,
                    distance = distance,
                    eta = eta,
                    icon = navIcon
                )
            }
        } catch (e: Exception) {}
    }

    private fun processAppNotification(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return

        // Skip internal/overlay, system decor, or maps
        if (pkg == packageName) return
        if (pkg == "android" || pkg == "com.android.systemui") return
        if (pkg == "com.google.android.apps.maps") return

        val notification = sbn.notification ?: return

        // Skip media style notifications (handled by MediaStateBridge)
        val extras = notification.extras ?: return
        val hasMediaSession = extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        val template = extras.getString(Notification.EXTRA_TEMPLATE) ?: ""
        if (hasMediaSession || template.contains("MediaStyle", ignoreCase = true)) return

        val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()?.trim() ?: ""

        val content = when {
            text.isNotEmpty() -> text
            bigText.isNotEmpty() -> bigText
            subText.isNotEmpty() -> subText
            infoText.isNotEmpty() -> infoText
            else -> ""
        }

        val actions = notification.actions.orEmpty()
        fun actionPendingIntent(vararg words: String) = actions.firstOrNull { action ->
            val actionTitle = action.title?.toString()?.lowercase().orEmpty()
            words.any { actionTitle.contains(it) }
        }?.actionIntent
        val answerIntent = actionPendingIntent("answer", "accept", "responder", "répondre", "contestar", "aceptar", "رد")
        val declineIntent = actionPendingIntent("decline", "reject", "hang up", "end call", "refuser", "rechazar", "رفض")
        val markReadIntent = actionPendingIntent("mark as read", "mark read", "mark as seen", "mark seen")
        val isCall = notification.category == Notification.CATEGORY_CALL ||
            (answerIntent != null && declineIntent != null)
        if (isOngoing && !isCall) return
        if (title.isEmpty() && content.isEmpty() && !isCall) return

        // Resolve friendly application name (e.g. WhatsApp, Instagram, Messages)
        val appName = try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            formatAppName(pkg)
        }

        // Extract icon: large icon first (contact photo or chat avatar), then app icon
        var notifIcon: Bitmap? = null
        val largeIcon = notification.getLargeIcon()
        if (largeIcon != null) {
            try {
                val d = largeIcon.loadDrawable(this)
                if (d != null) {
                    notifIcon = drawableToBitmap(d, 120)
                }
            } catch (e: Exception) {}
        }
        if (notifIcon == null) {
            try {
                val appDrawable = packageManager.getApplicationIcon(pkg)
                notifIcon = drawableToBitmap(appDrawable, 120)
            } catch (e: Exception) {}
        }

        val key = sbn.key ?: "${pkg}_${sbn.id}"
        val contentIntent = notification.contentIntent

        NotificationStateBridge.postNotification(
            NotificationStateBridge.AppNotification(
                key = key,
                packageName = pkg,
                appName = appName,
                title = title.ifEmpty { appName },
                text = content,
                icon = notifIcon,
                contentIntent = contentIntent,
                markReadIntent = markReadIntent,
                answerIntent = answerIntent,
                declineIntent = declineIntent,
                isCall = isCall
            )
        )
    }

    private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable, maxDim: Int = 120): Bitmap? {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return scaleBitmap(drawable.bitmap, maxDim)
        }
        return try {
            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth.coerceAtMost(maxDim) else maxDim
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight.coerceAtMost(maxDim) else maxDim
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    fun executeTransportCommand(cmd: String): Boolean {
        if (activeController == null) {
            findActiveMediaController()
        }
        val controller = activeController ?: return false
        return try {
            when (cmd) {
                "play" -> controller.transportControls.play()
                "pause" -> controller.transportControls.pause()
                "next" -> controller.transportControls.skipToNext()
                "prev" -> controller.transportControls.skipToPrevious()
                else -> return false
            }
            handler.postDelayed({ dispatchMediaUpdate() }, 150L)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun executeSeek(posMs: Long): Boolean {
        val controller = activeController ?: return false
        return try {
            controller.transportControls.seekTo(posMs)
            handler.postDelayed({ dispatchMediaUpdate() }, 150L)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun findActiveMediaController() {
        val sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager
        val componentName = ComponentName(this, MediaNotificationListener::class.java)

        val candidates = mutableListOf<MediaController>()

        // 1. Query MediaSessionManager
        if (sessionManager != null) {
            try {
                val active = sessionManager.getActiveSessions(componentName)
                if (active != null) {
                    candidates.addAll(active)
                }
            } catch (e: SecurityException) {
                // Notification listener not enabled yet
            } catch (e: Exception) {
                // Ignore
            }
        }

        // 2. Also scan active notifications for EXTRA_MEDIA_SESSION tokens
        try {
            val notifs = activeNotifications
            if (notifs != null) {
                for (sbn in notifs) {
                    val extras = sbn.notification?.extras ?: continue
                    @Suppress("DEPRECATION")
                    val token = extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
                    if (token != null) {
                        val ctrl = MediaController(this, token)
                        if (candidates.none { it.sessionToken == token }) {
                            candidates.add(ctrl)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        pickControllerFromList(candidates)
    }

    private fun pickControllerFromList(controllers: List<MediaController>?) {
        if (controllers.isNullOrEmpty()) return

        var chosen: MediaController? = null
        for (c in controllers) {
            val st = c.playbackState?.state
            if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) {
                chosen = c
                break
            }
        }
        if (chosen == null) {
            // Prefer one with valid non-empty title metadata
            chosen = controllers.firstOrNull {
                !it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()
            } ?: controllers[0]
        }

        if (chosen?.sessionToken != activeController?.sessionToken) {
            try {
                activeController?.unregisterCallback(controllerCallback)
            } catch (e: Exception) {}
            activeController = chosen
            try {
                activeController?.registerCallback(controllerCallback, handler)
            } catch (e: Exception) {}
            dispatchMediaUpdate()
        }
    }

    private fun computeLivePosition(state: PlaybackState?, duration: Long): Long {
        if (state == null) return 0L
        var pos = state.position
        if (pos < 0L) pos = 0L
        if (state.state == PlaybackState.STATE_PLAYING) {
            val lastUpdate = state.lastPositionUpdateTime
            if (lastUpdate > 0L) {
                val elapsed = (SystemClock.elapsedRealtime() - lastUpdate).coerceAtLeast(0L)
                val speed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1f
                pos += (elapsed * speed).toLong()
            }
        }
        return if (duration > 0L) pos.coerceIn(0L, duration) else pos
    }

    private fun extractAlbumArt(metadata: MediaMetadata?, packageName: String): Bitmap? {
        try {
            val raw = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            if (raw != null && !raw.isRecycled) {
                return scaleBitmap(raw, 160)
            }

            // Fallback: check notification largeIcon for matching package
            val notifs = activeNotifications
            if (notifs != null) {
                for (sbn in notifs) {
                    if (packageName.isEmpty() || sbn.packageName == packageName) {
                        val icon = sbn.notification?.getLargeIcon()
                        if (icon != null) {
                            val drawable = icon.loadDrawable(this)
                            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                                return scaleBitmap(drawable.bitmap, 160)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore bitmap extraction errors
        }
        return null
    }

    private fun scaleBitmap(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0 || (w <= maxDim && h <= maxDim)) return src
        val ratio = w.toFloat() / h.toFloat()
        val nw = if (w >= h) maxDim else (maxDim * ratio).toInt().coerceAtLeast(1)
        val nh = if (h > w) maxDim else (maxDim / ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    private fun dispatchMediaUpdate() {
        val controller = activeController
        val metadata = controller?.metadata
        val state = controller?.playbackState

        var title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        var artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val duration = (metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        val position = computeLivePosition(state, duration)
        var appPkg = controller?.packageName ?: ""

        // Fallback: if title is still blank, inspect active media notifications directly
        if (title.isBlank()) {
            try {
                val notifs = activeNotifications
                if (notifs != null) {
                    for (sbn in notifs) {
                        val extras = sbn.notification?.extras ?: continue
                        val template = extras.getString(Notification.EXTRA_TEMPLATE) ?: ""
                        val hasToken = extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
                        if (hasToken || template.contains("MediaStyle", ignoreCase = true)) {
                            val nTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                            val nText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                            if (nTitle.isNotBlank()) {
                                title = nTitle
                                artist = nText
                                appPkg = sbn.packageName ?: appPkg
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        if (title.isBlank() && !isPlaying) return

        val friendlyApp = formatAppName(appPkg)
        val art = extractAlbumArt(metadata, appPkg)

        // 1. Update in-process bridge immediately (0ms latency + album art Bitmap)
        MediaStateBridge.update(
            title = title.ifBlank { "Now Playing" },
            artist = artist,
            appName = friendlyApp,
            isPlaying = isPlaying,
            positionMs = position,
            durationMs = duration,
            albumArt = art,
            packageName = appPkg
        )

        // 2. Also send explicit package-scoped broadcast for Android 14+ compatibility
        val intent = Intent(IslandOverlayService.ACTION_UPDATE_MEDIA).apply {
            setPackage(packageName)
            putExtra(IslandOverlayService.EXTRA_TITLE, title.ifBlank { "Now Playing" })
            putExtra(IslandOverlayService.EXTRA_ARTIST, artist)
            putExtra(IslandOverlayService.EXTRA_PLAYING, isPlaying)
            putExtra(IslandOverlayService.EXTRA_POS, position)
            putExtra(IslandOverlayService.EXTRA_DUR, duration)
            putExtra(IslandOverlayService.EXTRA_APP, friendlyApp)
        }
        sendBroadcast(intent)
    }

    private fun formatAppName(pkg: String): String {
        val lower = pkg.lowercase()
        return when {
            lower.contains("spotify") -> "Spotify"
            lower.contains("youtube.music") || lower.contains("vanced") -> "YT Music"
            lower.contains("youtube") -> "YouTube"
            lower.contains("chrome") -> "Chrome"
            lower.contains("apple") -> "Apple Music"
            lower.contains("deezer") -> "Deezer"
            lower.contains("soundcloud") -> "SoundCloud"
            lower.contains("tidal") -> "Tidal"
            lower.contains("amazon") -> "Amazon Music"
            lower.contains("samsung.android.app.music") || lower.contains("sec.android.app.music") -> "Samsung Music"
            lower.contains("miui.player") -> "Mi Music"
            lower.contains("tiktok") || lower.contains("musically") -> "TikTok"
            lower.contains("instagram") -> "Instagram"
            lower.isNotBlank() -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
            else -> "Media"
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (instance == this) instance = null
        try {
            val sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (e: Exception) {}
        try {
            unregisterReceiver(commandReceiver)
            activeController?.unregisterCallback(controllerCallback)
        } catch (e: Exception) {}
        super.onDestroy()
    }
}
