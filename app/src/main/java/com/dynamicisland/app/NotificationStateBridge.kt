package com.dynamicisland.app

import android.app.PendingIntent
import android.graphics.Bitmap

/**
 * Singleton state bridge holding real-time app notifications (WhatsApp, Telegram, SMS, Instagram, etc.)
 */
object NotificationStateBridge {

    data class AppNotification(
        val key: String,
        val packageName: String,
        val appName: String,
        val title: String,
        val text: String,
        val icon: Bitmap? = null,
        val contentIntent: PendingIntent? = null,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        val isExpired: Boolean
            get() = System.currentTimeMillis() - timestamp > 45_000L
    }

    @Volatile
    var activeNotification: AppNotification? = null
        private set

    val recentNotifications = mutableListOf<AppNotification>()

    private val listeners = mutableListOf<(AppNotification?) -> Unit>()

    @Synchronized
    fun postNotification(notification: AppNotification) {
        activeNotification = notification
        recentNotifications.removeAll { it.key == notification.key }
        recentNotifications.add(0, notification)
        if (recentNotifications.size > 10) {
            recentNotifications.removeAt(recentNotifications.lastIndex)
        }
        notifyListeners(notification)
    }

    @Synchronized
    fun dismissCurrent(key: String? = null) {
        if (key == null || activeNotification?.key == key) {
            activeNotification = null
            notifyListeners(null)
        }
    }

    private fun notifyListeners(notif: AppNotification?) {
        for (l in listeners) {
            try {
                l(notif)
            } catch (e: Exception) {}
        }
    }

    @Synchronized
    fun addListener(l: (AppNotification?) -> Unit) {
        if (!listeners.contains(l)) {
            listeners.add(l)
            l(activeNotification)
        }
    }

    @Synchronized
    fun removeListener(l: (AppNotification?) -> Unit) {
        listeners.remove(l)
    }
}
