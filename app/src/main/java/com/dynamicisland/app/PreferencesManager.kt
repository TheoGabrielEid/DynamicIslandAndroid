package com.dynamicisland.app

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("dynamic_island_prefs", Context.MODE_PRIVATE)

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean("service_enabled", false)
        set(value) = prefs.edit().putBoolean("service_enabled", value).apply()

    var notchXOffset: Int
        get() = prefs.getInt("notch_x", 0) // 0 = centered, positive = right, negative = left
        set(value) = prefs.edit().putInt("notch_x", value).apply()

    var notchYOffset: Int
        get() = prefs.getInt("notch_y", 12) // Top margin in dp
        set(value) = prefs.edit().putInt("notch_y", value).apply()

    var compactWidth: Int
        get() = prefs.getInt("compact_w", 130) // in dp
        set(value) = prefs.edit().putInt("compact_w", value).apply()

    var compactHeight: Int
        get() = prefs.getInt("compact_h", 38) // in dp
        set(value) = prefs.edit().putInt("compact_h", value).apply()
}
