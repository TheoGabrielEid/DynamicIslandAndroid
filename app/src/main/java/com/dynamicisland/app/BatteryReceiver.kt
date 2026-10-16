package com.dynamicisland.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager

class BatteryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                notifyCharging(context, true)
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                notifyCharging(context, false)
            }
            Intent.ACTION_BATTERY_CHANGED -> {
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                notifyCharging(context, isCharging)
            }
        }
    }

    private fun notifyCharging(context: Context, isCharging: Boolean) {
        val i = Intent(IslandOverlayService.ACTION_BATTERY).apply {
            putExtra(IslandOverlayService.EXTRA_CHARGING, isCharging)
        }
        context.sendBroadcast(i)
    }
}
