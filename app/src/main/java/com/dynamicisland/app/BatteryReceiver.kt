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
                notifyCharging(context, true, -1)
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                notifyCharging(context, false, -1)
            }
            Intent.ACTION_BATTERY_CHANGED -> {
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                notifyCharging(context, isCharging, pct)
            }
        }
    }

    private fun notifyCharging(context: Context, isCharging: Boolean, levelPct: Int) {
        val i = Intent(IslandOverlayService.ACTION_BATTERY).apply {
            setPackage(context.packageName)
            putExtra(IslandOverlayService.EXTRA_CHARGING, isCharging)
            if (levelPct >= 0) {
                putExtra("EXTRA_BATTERY_PCT", levelPct)
            }
        }
        context.sendBroadcast(i)
    }
}
