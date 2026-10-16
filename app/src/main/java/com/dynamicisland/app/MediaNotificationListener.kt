package com.dynamicisland.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MediaNotificationListener : NotificationListenerService() {

    companion object {
        const val ACTION_COMMAND = "com.dynamicisland.app.MEDIA_COMMAND"
        const val ACTION_SEEK = "com.dynamicisland.app.MEDIA_SEEK"
    }

    private var activeController: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val controller = activeController ?: return
            when (intent.action) {
                ACTION_COMMAND -> {
                    when (intent.getStringExtra("command")) {
                        "play" -> controller.transportControls.play()
                        "pause" -> controller.transportControls.pause()
                        "next" -> controller.transportControls.skipToNext()
                        "prev" -> controller.transportControls.skipToPrevious()
                    }
                }
                ACTION_SEEK -> {
                    val posMs = intent.getLongExtra("position_ms", 0L)
                    controller.transportControls.seekTo(posMs)
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
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(ACTION_COMMAND)
            addAction(ACTION_SEEK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
        findActiveMediaController()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        findActiveMediaController()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        findActiveMediaController()
    }

    private fun findActiveMediaController() {
        val sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        val componentName = ComponentName(this, MediaNotificationListener::class.java)

        try {
            val controllers = sessionManager.getActiveSessions(componentName)
            var chosen: MediaController? = null

            for (c in controllers) {
                val state = c.playbackState
                if (state != null && state.state == PlaybackState.STATE_PLAYING) {
                    chosen = c
                    break
                }
            }

            if (chosen == null && controllers.isNotEmpty()) {
                chosen = controllers[0]
            }

            if (chosen != activeController) {
                activeController?.unregisterCallback(controllerCallback)
                activeController = chosen
                activeController?.registerCallback(controllerCallback)
                dispatchMediaUpdate()
            }
        } catch (e: SecurityException) {
            // Notification Listener permission not granted yet
        }
    }

    private fun dispatchMediaUpdate() {
        val controller = activeController
        val metadata = controller?.metadata
        val state = controller?.playbackState

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        val position = state?.position ?: 0L
        val app = controller?.packageName ?: ""

        val friendlyApp = when {
            app.contains("spotify") -> "Spotify"
            app.contains("youtube") -> "YouTube"
            app.contains("chrome") -> "Chrome"
            app.contains("apple") -> "Apple Music"
            app.contains("deezer") -> "Deezer"
            app.contains("soundcloud") -> "SoundCloud"
            else -> app.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }

        val intent = Intent(IslandOverlayService.ACTION_UPDATE_MEDIA).apply {
            putExtra(IslandOverlayService.EXTRA_TITLE, title)
            putExtra(IslandOverlayService.EXTRA_ARTIST, artist)
            putExtra(IslandOverlayService.EXTRA_PLAYING, isPlaying)
            putExtra(IslandOverlayService.EXTRA_POS, position)
            putExtra(IslandOverlayService.EXTRA_DUR, duration)
            putExtra(IslandOverlayService.EXTRA_APP, friendlyApp)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(commandReceiver)
            activeController?.unregisterCallback(controllerCallback)
        } catch (e: Exception) {}
        super.onDestroy()
    }
}
