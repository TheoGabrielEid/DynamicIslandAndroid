package com.dynamicisland.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import java.util.concurrent.CopyOnWriteArraySet

object MediaStateBridge {

    data class MediaSnapshot(
        val title: String = "Nothing playing",
        val artist: String = "Play music on Spotify, YouTube or Chrome",
        val appName: String = "",
        val isPlaying: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val albumArt: Bitmap? = null,
        val timestampElapsed: Long = SystemClock.elapsedRealtime()
    ) {
        fun livePositionMs(): Long {
            if (durationMs <= 0L) return 0L
            if (!isPlaying) return positionMs.coerceIn(0L, durationMs)
            val delta = (SystemClock.elapsedRealtime() - timestampElapsed).coerceAtLeast(0L)
            return (positionMs + delta).coerceIn(0L, durationMs)
        }
    }

    var current: MediaSnapshot = MediaSnapshot()
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(MediaSnapshot) -> Unit>()

    fun addListener(listener: (MediaSnapshot) -> Unit) {
        listeners.add(listener)
        listener(current)
    }

    fun removeListener(listener: (MediaSnapshot) -> Unit) {
        listeners.remove(listener)
    }

    fun update(
        title: String,
        artist: String,
        appName: String,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
        albumArt: Bitmap? = current.albumArt
    ) {
        val cleanTitle = title.ifBlank { "Nothing playing" }
        val cleanArtist = artist.ifBlank {
            if (appName.isNotBlank()) "Playing on $appName" else "Play music on Spotify, YouTube or Chrome"
        }
        val snap = MediaSnapshot(
            title = cleanTitle,
            artist = cleanArtist,
            appName = appName,
            isPlaying = isPlaying,
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            albumArt = albumArt,
            timestampElapsed = SystemClock.elapsedRealtime()
        )
        current = snap
        mainHandler.post {
            for (l in listeners) {
                try {
                    l(snap)
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    fun playOrPause(context: Context) {
        val handled = MediaNotificationListener.instance?.executeTransportCommand(
            if (current.isPlaying) "pause" else "play"
        ) ?: false

        if (!handled) {
            dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }

        // Optimistically toggle state for responsive UI
        update(
            title = current.title,
            artist = current.artist,
            appName = current.appName,
            isPlaying = !current.isPlaying,
            positionMs = current.livePositionMs(),
            durationMs = current.durationMs,
            albumArt = current.albumArt
        )
    }

    fun next(context: Context) {
        val handled = MediaNotificationListener.instance?.executeTransportCommand("next") ?: false
        if (!handled) {
            dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
        }
    }

    fun previous(context: Context) {
        val handled = MediaNotificationListener.instance?.executeTransportCommand("prev") ?: false
        if (!handled) {
            dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
    }

    fun seekTo(context: Context, posMs: Long) {
        MediaNotificationListener.instance?.executeSeek(posMs)
        val seekIntent = Intent(MediaNotificationListener.ACTION_SEEK).apply {
            setPackage(context.packageName)
            putExtra("position_ms", posMs)
        }
        context.sendBroadcast(seekIntent)

        update(
            title = current.title,
            artist = current.artist,
            appName = current.appName,
            isPlaying = current.isPlaying,
            positionMs = posMs,
            durationMs = current.durationMs,
            albumArt = current.albumArt
        )
    }

    private fun dispatchMediaKey(context: Context, keyCode: Int) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
            val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
            am.dispatchMediaKeyEvent(down)
            am.dispatchMediaKeyEvent(up)
        } catch (e: Exception) {
            // ignore
        }
    }
}
