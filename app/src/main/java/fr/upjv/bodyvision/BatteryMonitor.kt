package fr.upjv.bodyvision

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Lit la température batterie et le niveau de charge via le sticky intent
 * ACTION_BATTERY_CHANGED. Pas besoin de récepteur enregistré en continu : chaque lecture
 * ré-interroge l'intent sticky, ce qui suffit pour un échantillonnage à 1 Hz.
 */
class BatteryMonitor(private val context: Context) {

    private var lastIntent: Intent? = null
    private var receiver: BroadcastReceiver? = null

    fun start() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                lastIntent = intent
            }
        }
        receiver = r
        lastIntent = context.registerReceiver(r, filter)
    }

    fun stop() {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }

    /** Température batterie en °C (EXTRA_TEMPERATURE est en dixièmes de degré). */
    fun temperatureCelsius(): Double {
        val tenths = lastIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        return if (tenths < 0) Double.NaN else tenths / 10.0
    }

    fun batteryPercent(): Int {
        val level = lastIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = lastIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return -1
        return (level * 100) / scale
    }
}
