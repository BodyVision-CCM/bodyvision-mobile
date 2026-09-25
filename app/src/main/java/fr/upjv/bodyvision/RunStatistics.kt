package fr.upjv.bodyvision

import android.os.PowerManager
import android.os.SystemClock
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/** Une ligne du tableau CSV, une par seconde écoulée depuis le début du run. */
data class SecondRecord(
    val tSeconds: Long,
    val fps: Double,
    val latencyMedianMs: Double,
    val latencyP95Ms: Double,
    val framesDropped: Long,
    val batteryTempC: Double,
    val thermalStatus: String,
    val batteryPct: Int,
    val likelihoodAvg: Double,
    val kneeAngleDeg: Double,
    val angleStdDevDeg: Double
)

/**
 * Agrège toutes les mesures d'un run. Alimentée depuis le thread d'analyse ML Kit
 * (onFrameReceived/onFrameAnalyzed) et lue depuis le thread principal (tick HUD à 1 Hz).
 * Les 30 premières secondes de chaque run sont exclues des statistiques (échauffement ML Kit).
 */
class RunStatistics(private val warmupMillis: Long = 30_000L) {

    private val runStartRealtime = SystemClock.elapsedRealtime()

    @Volatile
    var warmupActive = true
        private set

    private val framesReceived = AtomicLong(0)
    private val framesAnalyzed = AtomicLong(0)
    private var lastTickReceived = 0L
    private var lastTickAnalyzed = 0L

    // Timestamps (elapsedRealtime ms) des callbacks réussis, fenêtre glissante 1 s -> FPS instantané.
    private val successTimestamps = ArrayDeque<Long>()
    private var analyzedPostWarmupCount = 0L
    private var statsStartRealtime = -1L

    private val latencyWindow = ArrayDeque<Pair<Long, Long>>() // (t ms, latence ns)
    private val likelihoodWindow = ArrayDeque<Pair<Long, Double>>() // (t ms, likelihood moyen frame)
    private val angleWindow = ArrayDeque<Pair<Long, Double>>() // (t ms, angle genou deg), fenêtre 10 s

    @Volatile
    private var lastKneeAngleDeg: Double = Double.NaN

    @Volatile
    var maxThermalStatus: Int = PowerManager.THERMAL_STATUS_NONE
        private set

    val secondRecords = mutableListOf<SecondRecord>()

    // Historique post-échauffement, pour le résumé de fin de run.
    private val fpsHistoryPostWarmup = mutableListOf<Double>()
    private val angleStdDevHistoryPostWarmup = mutableListOf<Double>()

    private fun now() = SystemClock.elapsedRealtime()

    /** À appeler régulièrement ; bascule warmupActive à false une fois les 30 s passées. */
    @Synchronized
    fun refreshWarmupState() {
        if (warmupActive && now() - runStartRealtime >= warmupMillis) {
            warmupActive = false
            statsStartRealtime = now()
        }
    }

    fun warmupRemainingMs(): Long = (warmupMillis - (now() - runStartRealtime)).coerceAtLeast(0)

    fun onFrameReceived() {
        framesReceived.incrementAndGet()
    }

    @Synchronized
    fun onFrameAnalyzed(latencyNanos: Long, kneeAngleDeg: Double?, avgLikelihood: Double?) {
        framesAnalyzed.incrementAndGet()
        val t = now()

        successTimestamps.addLast(t)
        while (successTimestamps.isNotEmpty() && t - successTimestamps.first() > 1000) {
            successTimestamps.removeFirst()
        }

        if (kneeAngleDeg != null && !kneeAngleDeg.isNaN()) {
            lastKneeAngleDeg = kneeAngleDeg
        }

        if (!warmupActive) {
            analyzedPostWarmupCount++

            latencyWindow.addLast(t to latencyNanos)
            while (latencyWindow.isNotEmpty() && t - latencyWindow.first().first > 5000) {
                latencyWindow.removeFirst()
            }

            if (avgLikelihood != null && !avgLikelihood.isNaN()) {
                likelihoodWindow.addLast(t to avgLikelihood)
                while (likelihoodWindow.isNotEmpty() && t - likelihoodWindow.first().first > 1000) {
                    likelihoodWindow.removeFirst()
                }
            }

            if (kneeAngleDeg != null && !kneeAngleDeg.isNaN()) {
                angleWindow.addLast(t to kneeAngleDeg)
                while (angleWindow.isNotEmpty() && t - angleWindow.first().first > 10_000) {
                    angleWindow.removeFirst()
                }
            }
        }
    }

    fun onThermalStatusChanged(status: Int) {
        if (status > maxThermalStatus) maxThermalStatus = status
    }

    fun instantFps(): Double = synchronized(this) { successTimestamps.size.toDouble() }

    fun avgFpsRun(): Double = synchronized(this) {
        if (statsStartRealtime < 0) return 0.0
        val elapsedS = (now() - statsStartRealtime) / 1000.0
        if (elapsedS <= 0) 0.0 else analyzedPostWarmupCount / elapsedS
    }

    fun latencyMedianMs(): Double = synchronized(this) { percentile(latencyWindow.map { it.second }, 0.5) }

    fun latencyP95Ms(): Double = synchronized(this) { percentile(latencyWindow.map { it.second }, 0.95) }

    private fun percentile(samplesNanos: List<Long>, p: Double): Double {
        if (samplesNanos.isEmpty()) return 0.0
        val sorted = samplesNanos.sorted()
        val index = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index] / 1_000_000.0
    }

    /** Frames droppées depuis le dernier appel (delta), pour la ligne CSV de la seconde écoulée. */
    fun droppedSinceLastTick(): Long {
        val received = framesReceived.get()
        val analyzed = framesAnalyzed.get()
        val delta = (received - lastTickReceived) - (analyzed - lastTickAnalyzed)
        lastTickReceived = received
        lastTickAnalyzed = analyzed
        return delta.coerceAtLeast(0)
    }

    fun totalDropped(): Long = framesReceived.get() - framesAnalyzed.get()

    fun currentAvgLikelihood(): Double = synchronized(this) {
        val values = likelihoodWindow.map { it.second }
        if (values.isEmpty()) 0.0 else values.average()
    }

    fun lastKneeAngle(): Double = lastKneeAngleDeg

    fun currentAngleStdDev(): Double = synchronized(this) {
        val values = angleWindow.map { it.second }
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        sqrt(variance)
    }

    fun addSecondRecord(record: SecondRecord) {
        secondRecords.add(record)
        if (!warmupActive) {
            fpsHistoryPostWarmup.add(record.fps)
            angleStdDevHistoryPostWarmup.add(record.angleStdDevDeg)
        }
    }

    fun elapsedSeconds(): Long = (now() - runStartRealtime) / 1000

    fun summary(): RunSummary {
        val fpsList = fpsHistoryPostWarmup.ifEmpty { listOf(0.0) }
        val allLatenciesNanos = synchronized(this) { latencyWindow.map { it.second } }
        return RunSummary(
            avgFps = fpsList.average(),
            minFps = fpsList.min(),
            latencyMedianMs = percentile(allLatenciesNanos, 0.5),
            latencyP95Ms = percentile(allLatenciesNanos, 0.95),
            maxThermalStatus = maxThermalStatus,
            avgAngleStdDevDeg = angleStdDevHistoryPostWarmup.ifEmpty { listOf(0.0) }.average()
        )
    }
}

data class RunSummary(
    val avgFps: Double,
    val minFps: Double,
    val latencyMedianMs: Double,
    val latencyP95Ms: Double,
    val maxThermalStatus: Int,
    val avgAngleStdDevDeg: Double
) {
    val meetsThresholds: Boolean
        get() = minFps >= 24.0 &&
            latencyP95Ms < 40.0 &&
            maxThermalStatus <= PowerManager.THERMAL_STATUS_LIGHT &&
            avgAngleStdDevDeg < 3.0
}
