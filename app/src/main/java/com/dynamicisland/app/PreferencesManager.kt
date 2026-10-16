package com.dynamicisland.app

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("dynamic_island_prefs", Context.MODE_PRIVATE)

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean("service_enabled", false)
        set(value) = prefs.edit().putBoolean("service_enabled", value).apply()

    var startOnBoot: Boolean
        get() = prefs.getBoolean("start_on_boot", true)
        set(value) = prefs.edit().putBoolean("start_on_boot", value).apply()

    var notchXOffset: Int
        get() = prefs.getInt("notch_x", 0)
        set(value) = prefs.edit().putInt("notch_x", value).apply()

    var notchYOffset: Int
        get() = prefs.getInt("notch_y", 8)
        set(value) = prefs.edit().putInt("notch_y", value).apply()

    var compactWidth: Int
        get() = prefs.getInt("compact_w", 170)
        set(value) = prefs.edit().putInt("compact_w", value).apply()

    var compactHeight: Int
        get() = prefs.getInt("compact_h", 36)
        set(value) = prefs.edit().putInt("compact_h", value).apply()

    var accentColorHex: String
        get() = prefs.getString("accent_color", "#A8C7FA") ?: "#A8C7FA"
        set(value) = prefs.edit().putString("accent_color", value).apply()

    var autoExpandOnSong: Boolean
        get() = prefs.getBoolean("auto_expand_song", true)
        set(value) = prefs.edit().putBoolean("auto_expand_song", value).apply()

    var showIdleClockBattery: Boolean
        get() = prefs.getBoolean("show_idle_clock", true)
        set(value) = prefs.edit().putBoolean("show_idle_clock", value).apply()

    var doubleTapPlayPause: Boolean
        get() = prefs.getBoolean("double_tap_play", true)
        set(value) = prefs.edit().putBoolean("double_tap_play", value).apply()

    var longPressFlashlight: Boolean
        get() = prefs.getBoolean("long_press_torch", true)
        set(value) = prefs.edit().putBoolean("long_press_torch", value).apply()

    var wanVpnColor: Boolean
        get() = prefs.getBoolean("wan_vpn_color", true)
        set(value) = prefs.edit().putBoolean("wan_vpn_color", value).apply()

    fun resetPositionDefaults() {
        notchXOffset = 0
        notchYOffset = 8
        compactWidth = 170
        compactHeight = 36
    }
}
