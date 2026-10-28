package com.dynamicisland.app

import android.graphics.Bitmap

/**
 * Singleton state bridge holding real-time Google Maps turn-by-turn navigation data.
 */
object NavigationStateBridge {

    data class NavSnapshot(
        val isNavigating: Boolean = false,
        val maneuverText: String = "",
        val distanceText: String = "",
        val etaText: String = "",
        val icon: Bitmap? = null,
        val lastUpdateMs: Long = 0L
    ) {
        val isStale: Boolean
            get() = !isNavigating || (System.currentTimeMillis() - lastUpdateMs > 60_000L)
    }

    @Volatile
    var current: NavSnapshot = NavSnapshot()
        private set

    private val listeners = mutableListOf<(NavSnapshot) -> Unit>()

    @Synchronized
    fun update(
        isNavigating: Boolean,
        maneuver: String,
        distance: String,
        eta: String,
        icon: Bitmap?
    ) {
        current = NavSnapshot(
            isNavigating = isNavigating,
            maneuverText = maneuver,
            distanceText = distance,
            etaText = eta,
            icon = icon,
            lastUpdateMs = System.currentTimeMillis()
        )
        for (l in listeners) {
            try {
                l(current)
            } catch (e: Exception) {}
        }
    }

    @Synchronized
    fun clear() {
        if (current.isNavigating) {
            current = NavSnapshot(isNavigating = false, lastUpdateMs = System.currentTimeMillis())
            for (l in listeners) {
                try {
                    l(current)
                } catch (e: Exception) {}
            }
        }
    }

    @Synchronized
    fun addListener(l: (NavSnapshot) -> Unit) {
        if (!listeners.contains(l)) {
            listeners.add(l)
            l(current)
        }
    }

    @Synchronized
    fun removeListener(l: (NavSnapshot) -> Unit) {
        listeners.remove(l)
    }
}
