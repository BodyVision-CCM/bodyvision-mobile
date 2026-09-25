package fr.upjv.bodyvision

import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Suit l'état thermique du téléphone via PowerManager (API 29+). En dessous, on n'a pas
 * d'API système équivalente : on retombe sur NONE en permanence (non bloquant pour le spike,
 * l'appareil de test cible est en Android 16).
 */
class ThermalMonitor(context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Volatile
    var currentStatus: Int = PowerManager.THERMAL_STATUS_NONE
        private set

    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    fun start(onChanged: (Int) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            currentStatus = powerManager.currentThermalStatus
            val l = PowerManager.OnThermalStatusChangedListener { status ->
                currentStatus = status
                onChanged(status)
            }
            listener = l
            powerManager.addThermalStatusListener(l)
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listener?.let { powerManager.removeThermalStatusListener(it) }
        }
        listener = null
    }

    companion object {
        fun statusLabel(status: Int): String = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            7 -> "SHUTDOWN" // THERMAL_STATUS_SHUTDOWN
            6 -> "EMERGENCY" // THERMAL_STATUS_EMERGENCY
            else -> "UNKNOWN($status)"
        }
    }
}
