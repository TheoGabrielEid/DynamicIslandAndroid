package com.dynamicisland.app

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.widget.Button
import android.widget.TextView
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager

    private lateinit var switchService: MaterialSwitch
    private lateinit var tvStatus: TextView
    private lateinit var btnOverlay: Button
    private lateinit var btnNotification: Button

    private lateinit var switchStartOnBoot: MaterialSwitch
    private lateinit var switchIdleClock: MaterialSwitch
    private lateinit var switchAutoExpand: MaterialSwitch
    private lateinit var switchDoubleTap: MaterialSwitch
    private lateinit var switchLongPress: MaterialSwitch
    private lateinit var switchMapsNav: MaterialSwitch
    private lateinit var switchHeadset: MaterialSwitch
    private lateinit var switchAppNotifs: MaterialSwitch

    private lateinit var sliderX: Slider
    private lateinit var sliderY: Slider
    private lateinit var sliderW: Slider
    private lateinit var sliderH: Slider
    private lateinit var lblX: TextView
    private lateinit var lblY: TextView
    private lateinit var lblW: TextView
    private lateinit var lblH: TextView
    private lateinit var btnResetPos: Button
    private lateinit var btnTest: Button
    private lateinit var btnTestCharging: Button
    private lateinit var advancedSettings: View
    private lateinit var advancedSettingsToggle: View
    private lateinit var advancedSettingsTitle: TextView
    private lateinit var advancedSettingsArrow: TextView

    private lateinit var themeChips: List<Pair<TextView, String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = PreferencesManager(this)

        initViews()
        setupListeners()
        updateUIState()
    }

    override fun onResume() {
        super.onResume()
        if (isNotificationServiceEnabled()) {
            try {
                NotificationListenerService.requestRebind(
                    ComponentName(this, MediaNotificationListener::class.java)
                )
            } catch (e: Exception) {}
        }
        updateUIState()
    }

    private fun initViews() {
        switchService = findViewById(R.id.switch_service)
        tvStatus = findViewById(R.id.tv_service_status)
        btnOverlay = findViewById(R.id.btn_perm_overlay)
        btnNotification = findViewById(R.id.btn_perm_notification)

        switchStartOnBoot = findViewById(R.id.switch_start_on_boot)
        switchIdleClock = findViewById(R.id.switch_idle_clock)
        switchAutoExpand = findViewById(R.id.switch_auto_expand)
        switchDoubleTap = findViewById(R.id.switch_double_tap)
        switchLongPress = findViewById(R.id.switch_long_press)
        switchMapsNav = findViewById(R.id.switch_maps_nav)
        switchHeadset = findViewById(R.id.switch_headset)
        switchAppNotifs = findViewById(R.id.switch_app_notifications)

        sliderX = findViewById(R.id.slider_notch_x)
        sliderY = findViewById(R.id.slider_notch_y)
        sliderW = findViewById(R.id.slider_island_w)
        sliderH = findViewById(R.id.slider_island_h)

        lblX = findViewById(R.id.lbl_notch_x)
        lblY = findViewById(R.id.lbl_notch_y)
        lblW = findViewById(R.id.lbl_island_w)
        lblH = findViewById(R.id.lbl_island_h)

        btnResetPos = findViewById(R.id.btn_reset_pos)
        btnTest = findViewById(R.id.btn_test_island)
        btnTestCharging = findViewById(R.id.btn_test_charging)
        advancedSettings = findViewById(R.id.advanced_settings)
        advancedSettingsToggle = findViewById(R.id.advanced_settings_header)
        advancedSettingsTitle = findViewById(R.id.advanced_settings_title)
        advancedSettingsArrow = findViewById(R.id.advanced_settings_arrow)

        themeChips = listOf(
            findViewById<TextView>(R.id.theme_periwinkle) to "#A8C7FA",
            findViewById<TextView>(R.id.theme_emerald) to "#7FD88F",
            findViewById<TextView>(R.id.theme_violet) to "#D0BCFF",
            findViewById<TextView>(R.id.theme_sunset) to "#FFB86C",
            findViewById<TextView>(R.id.theme_crimson) to "#FFB4AB",
            findViewById<TextView>(R.id.theme_cyan) to "#70F0FF",
            findViewById<TextView>(R.id.theme_lavender) to "#E7B9F5",
            findViewById<TextView>(R.id.theme_lime) to "#9BD84A",
            findViewById<TextView>(R.id.theme_magenta) to "#FF79C6",
            findViewById<TextView>(R.id.theme_indigo) to "#818CF8",
            findViewById<TextView>(R.id.theme_aqua) to "#2DD4BF",
            findViewById<TextView>(R.id.theme_silver) to "#E2E8F0"
        )

        // Load saved preferences
        switchStartOnBoot.isChecked = prefs.startOnBoot
        switchIdleClock.isChecked = prefs.showIdleClockBattery
        switchAutoExpand.isChecked = prefs.autoExpandOnSong
        switchDoubleTap.isChecked = prefs.doubleTapPlayPause
        switchLongPress.isChecked = prefs.longPressFlashlight
        switchMapsNav.isChecked = prefs.showMapsNavigation
        switchHeadset.isChecked = prefs.showHeadsetConnected
        switchAppNotifs.isChecked = prefs.showAppNotifications

        syncSlidersFromPrefs()
        updateThemeChipSelection()
    }

    private fun syncSlidersFromPrefs() {
        sliderX.value = prefs.notchXOffset.toFloat().coerceIn(-140f, 140f)
        sliderY.value = prefs.notchYOffset.toFloat().coerceIn(0f, 60f)
        val wVal = (prefs.compactWidth / 2) * 2
        sliderW.value = wVal.toFloat().coerceIn(110f, 260f)
        sliderH.value = prefs.compactHeight.toFloat().coerceIn(28f, 52f)
        updateSliderLabels()
    }

    private fun updateThemeChipSelection() {
        val currentHex = prefs.accentColorHex
        for ((tv, hex) in themeChips) {
            val selected = hex.equals(currentHex, ignoreCase = true)
            tv.setBackgroundResource(if (selected) R.drawable.bg_tab_selected else R.drawable.bg_tab_unselected)
        }
    }

    private fun setupListeners() {
        advancedSettingsToggle.setOnClickListener {
            val shouldExpand = advancedSettings.visibility != View.VISIBLE
            advancedSettingsToggle.isEnabled = false
            advancedSettingsTitle.text = if (shouldExpand) "Hide appearance & behavior" else "Customize appearance & behavior"
            advancedSettingsArrow.text = if (shouldExpand) "⌃" else "⌄"
            if (shouldExpand) {
                advancedSettings.visibility = View.VISIBLE
                advancedSettings.alpha = 0f
                advancedSettings.animate().alpha(1f).setDuration(180L).withEndAction {
                    advancedSettingsToggle.isEnabled = true
                }.start()
            } else {
                advancedSettings.animate().alpha(0f).setDuration(140L).withEndAction {
                    advancedSettings.visibility = View.GONE
                    advancedSettings.alpha = 1f
                    advancedSettingsToggle.isEnabled = true
                }.start()
            }
        }

        btnOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        btnNotification.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        switchService.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!checkPermissions()) {
                    switchService.isChecked = false
                    Toast.makeText(this, "Please grant Overlay and Notification Access first!", Toast.LENGTH_LONG).show()
                    return@setOnCheckedChangeListener
                }
                startIslandService()
            } else {
                stopIslandService()
            }
            updateUIState()
        }

        for ((tv, hex) in themeChips) {
            tv.setOnClickListener {
                prefs.accentColorHex = hex
                updateThemeChipSelection()
                notifyServicePrefsChanged()
            }
        }

        switchStartOnBoot.setOnCheckedChangeListener { _, checked ->
            prefs.startOnBoot = checked
        }

        switchIdleClock.setOnCheckedChangeListener { _, checked ->
            prefs.showIdleClockBattery = checked
            notifyServicePrefsChanged()
        }

        switchAutoExpand.setOnCheckedChangeListener { _, checked ->
            prefs.autoExpandOnSong = checked
            notifyServicePrefsChanged()
        }

        switchDoubleTap.setOnCheckedChangeListener { _, checked ->
            prefs.doubleTapPlayPause = checked
            notifyServicePrefsChanged()
        }

        switchLongPress.setOnCheckedChangeListener { _, checked ->
            prefs.longPressFlashlight = checked
            notifyServicePrefsChanged()
        }

        switchMapsNav.setOnCheckedChangeListener { _, checked ->
            prefs.showMapsNavigation = checked
            notifyServicePrefsChanged()
        }

        switchHeadset.setOnCheckedChangeListener { _, checked ->
            prefs.showHeadsetConnected = checked
            notifyServicePrefsChanged()
        }

        switchAppNotifs.setOnCheckedChangeListener { _, checked ->
            prefs.showAppNotifications = checked
            notifyServicePrefsChanged()
        }

        sliderX.addOnChangeListener { _, value, _ ->
            prefs.notchXOffset = value.toInt()
            updateSliderLabels()
            notifyServicePrefsChanged()
        }

        sliderY.addOnChangeListener { _, value, _ ->
            prefs.notchYOffset = value.toInt()
            updateSliderLabels()
            notifyServicePrefsChanged()
        }

        sliderW.addOnChangeListener { _, value, _ ->
            prefs.compactWidth = value.toInt()
            updateSliderLabels()
            notifyServicePrefsChanged()
        }

        sliderH.addOnChangeListener { _, value, _ ->
            prefs.compactHeight = value.toInt()
            updateSliderLabels()
            notifyServicePrefsChanged()
        }

        btnResetPos.setOnClickListener {
            prefs.resetPositionDefaults()
            syncSlidersFromPrefs()
            notifyServicePrefsChanged()
            Toast.makeText(this, "Position reset to default!", Toast.LENGTH_SHORT).show()
        }

        btnTest.setOnClickListener {
            if (!IslandOverlayService.isRunning) {
                if (checkPermissions()) {
                    startIslandService()
                    updateUIState()
                } else {
                    Toast.makeText(this, "Enable Dynamic Island first to test!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }
            MediaStateBridge.update(
                title = "Starboy",
                artist = "The Weeknd · Daft Punk",
                appName = "Spotify",
                isPlaying = true,
                positionMs = 64000L,
                durationMs = 230000L,
                albumArt = null
            )
            Toast.makeText(this, "Simulating playing track on Dynamic Island!", Toast.LENGTH_SHORT).show()
        }

        btnTestCharging.setOnClickListener {
            if (!IslandOverlayService.isRunning) {
                Toast.makeText(this, "Enable Dynamic Island first to test!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val i = Intent(IslandOverlayService.ACTION_BATTERY).apply {
                setPackage(packageName)
                putExtra(IslandOverlayService.EXTRA_CHARGING, true)
                putExtra("EXTRA_BATTERY_PCT", 88)
            }
            sendBroadcast(i)
        }
    }

    private fun updateSliderLabels() {
        lblX.text = "Horizontal Offset (X): ${prefs.notchXOffset} dp"
        lblY.text = "Top Margin (Y): ${prefs.notchYOffset} dp"
        lblW.text = "Compact Pill Width: ${prefs.compactWidth} dp"
        lblH.text = "Compact Pill Height: ${prefs.compactHeight} dp"
    }

    private fun checkPermissions(): Boolean {
        val overlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

        return overlayGranted && isNotificationServiceEnabled()
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(packageName)
    }

    private fun updateUIState() {
        val overlayOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

        val notifOk = isNotificationServiceEnabled()

        btnOverlay.text = if (overlayOk) "✓ Granted" else getString(R.string.grant_permission)
        btnOverlay.isEnabled = !overlayOk

        btnNotification.text = if (notifOk) "✓ Granted" else getString(R.string.grant_permission)
        btnNotification.isEnabled = !notifOk

        val running = IslandOverlayService.isRunning
        switchService.isChecked = running
        tvStatus.text = if (running) "● Active — Tap the top pill anytime to expand" else getString(R.string.service_stopped)
    }

    private fun startIslandService() {
        prefs.isServiceEnabled = true
        if (isNotificationServiceEnabled()) {
            try {
                NotificationListenerService.requestRebind(
                    ComponentName(this, MediaNotificationListener::class.java)
                )
            } catch (e: Exception) {}
        }
        val intent = Intent(this, IslandOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopIslandService() {
        prefs.isServiceEnabled = false
        val intent = Intent(this, IslandOverlayService::class.java)
        stopService(intent)
    }

    private fun notifyServicePrefsChanged() {
        if (IslandOverlayService.isRunning) {
            val intent = Intent(IslandOverlayService.ACTION_REFRESH_PREFS).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }
    }
}
