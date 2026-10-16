package com.dynamicisland.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val prefs = PreferencesManager(context)
            if (prefs.isServiceEnabled && prefs.startOnBoot) {
                try {
                    val srvIntent = Intent(context, IslandOverlayService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(srvIntent)
                    } else {
                        context.startService(srvIntent)
                    }
                } catch (e: Exception) {
                    // Ignore if overlay permission not ready on boot
                }
            }
        }
    }
}
