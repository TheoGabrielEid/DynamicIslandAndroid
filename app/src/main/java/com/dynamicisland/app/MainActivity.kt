package com.dynamicisland.app

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
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

    private lateinit var sliderX: Slider
    private lateinit var sliderY: Slider
    private lateinit var sliderW: Slider
    private lateinit var lblX: TextView
    private lateinit var lblY: TextView
    private lateinit var lblW: TextView
    private lateinit var btnTest: Button

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
        updateUIState()
    }

    private fun initViews() {
        switchService = findViewById(R.id.switch_service)
        tvStatus = findViewById(R.id.tv_service_status)
        btnOverlay = findViewById(R.id.btn_perm_overlay)
        btnNotification = findViewById(R.id.btn_perm_notification)

        sliderX = findViewById(R.id.slider_notch_x)
        sliderY = findViewById(R.id.slider_notch_y)
        sliderW = findViewById(R.id.slider_island_w)

        lblX = findViewById(R.id.lbl_notch_x)
        lblY = findViewById(R.id.lbl_notch_y)
        lblW = findViewById(R.id.lbl_island_w)
        btnTest = findViewById(R.id.btn_test_island)

        // Load saved positions
        sliderX.value = prefs.notchXOffset.toFloat()
        sliderY.value = prefs.notchYOffset.toFloat()
        sliderW.value = prefs.compactWidth.toFloat()

        updateSliderLabels()
    }

    private fun setupListeners() {
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
                    Toast.makeText(this, "Please grant both permissions first!", Toast.LENGTH_LONG).show()
                    return@setOnCheckedChangeListener
                }
                startIslandService()
            } else {
                stopIslandService()
            }
            updateUIState()
        }

        sliderX.addOnChangeListener { _, value, _ ->
            prefs.notchXOffset = value.toInt()
            updateSliderLabels()
            restartServiceIfRunning()
        }

        sliderY.addOnChangeListener { _, value, _ ->
            prefs.notchYOffset = value.toInt()
            updateSliderLabels()
            restartServiceIfRunning()
        }

        sliderW.addOnChangeListener { _, value, _ ->
            prefs.compactWidth = value.toInt()
            updateSliderLabels()
            restartServiceIfRunning()
        }

        btnTest.setOnClickListener {
            if (!IslandOverlayService.isRunning) {
                Toast.makeText(this, "Enable Dynamic Island first to test!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Send simulation test broadcast
            val testIntent = Intent(IslandOverlayService.ACTION_UPDATE_MEDIA).apply {
                putExtra(IslandOverlayService.EXTRA_TITLE, "Starboy")
                putExtra(IslandOverlayService.EXTRA_ARTIST, "The Weeknd · Daft Punk")
                putExtra(IslandOverlayService.EXTRA_PLAYING, true)
                putExtra(IslandOverlayService.EXTRA_POS, 45000L)
                putExtra(IslandOverlayService.EXTRA_DUR, 230000L)
                putExtra(IslandOverlayService.EXTRA_APP, "Spotify")
            }
            sendBroadcast(testIntent)
            Toast.makeText(this, "Simulating playing track on Dynamic Island!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSliderLabels() {
        lblX.text = "Horizontal Offset: ${prefs.notchXOffset} dp"
        lblY.text = "Top Margin: ${prefs.notchYOffset} dp"
        lblW.text = "Compact Width: ${prefs.compactWidth} dp"
    }

    private fun checkPermissions(): Boolean {
        val overlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

        val notificationGranted = isNotificationServiceEnabled()

        return overlayGranted && notificationGranted
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

        btnOverlay.text = if (overlayOk) getString(R.string.granted) else getString(R.string.grant_permission)
        btnOverlay.isEnabled = !overlayOk

        btnNotification.text = if (notifOk) getString(R.string.granted) else getString(R.string.grant_permission)
        btnNotification.isEnabled = !notifOk

        val running = IslandOverlayService.isRunning
        switchService.isChecked = running
        tvStatus.text = if (running) getString(R.string.service_running) else getString(R.string.service_stopped)
    }

    private fun startIslandService() {
        prefs.isServiceEnabled = true
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

    private fun restartServiceIfRunning() {
        if (IslandOverlayService.isRunning) {
            startIslandService()
        }
    }
}
