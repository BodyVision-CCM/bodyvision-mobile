package fr.upjv.bodyvision.metrics.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NANOS_PER_MILLI = 1_000_000L
private const val ONE_SECOND_NANOS = 1_000_000_000L

/**
 * Une fenêtre d'une seconde avec autant d'inférences que le débit voulu : le débit en sort exact,
 * donc les oracles de ce fichier sont des nombres entiers et non des arrondis.
 */
private fun windowStats(
    framesPerSecond: Int,
    coverage: RecordingCoverage = RecordingCoverage.NONE,
    isWarmup: Boolean = false,
) = WindowStats(
    startNanos = 0L,
    durationNanos = ONE_SECOND_NANOS,
    sensorFrameCount = framesPerSecond,
    receivedFrameCount = framesPerSecond,
    gateDroppedCount = 0,
    otherDroppedCount = 0,
    inferenceCount = framesPerSecond,
    inferenceFailedCount = 0,
    latencySampleCount = framesPerSecond,
    latencyMedianMs = 26.0,
    latencyP95Ms = 32.0,
    minReceivedGapMs = 33.0,
    coverage = coverage,
    isWarmup = isWarmup,
)

/** Le verdict d'un run : quelles fenêtres comptent, et ce qu'on en tire. */
class ThroughputRunAggregateTest {
    @Test
    fun `tant qu'aucune inference n'a reussi tout est de la chauffe`() {
        // Dix minutes après le démarrage sans une seule inférence : on ne sait pas quand la chauffe
        // a commencé, donc rien n'est une mesure.
        assertTrue(isWarmupWindow(windowStartNanos = 600 * ONE_SECOND_NANOS, firstInferenceNanos = null))
    }

    @Test
    fun `la chauffe dure trente secondes apres la premiere inference reussie`() {
        val firstInference = 90 * ONE_SECOND_NANOS

        // L'origine est la première inférence et non le démarrage : le temps du dialogue de
        // permission, ou celui de se placer dans le cadre, ne mange pas la chauffe ML Kit.
        assertTrue(
            isWarmupWindow(
                windowStartNanos = firstInference + (WARMUP_DURATION_MS - 1) * NANOS_PER_MILLI,
                firstInferenceNanos = firstInference,
            ),
        )
        assertFalse(
            isWarmupWindow(
                windowStartNanos = firstInference + WARMUP_DURATION_MS * NANOS_PER_MILLI,
                firstInferenceNanos = firstInference,
            ),
        )
    }

    @Test
    fun `une fenetre de chauffe n'entre pas dans l'agregat`() {
        val aggregate = ThroughputRunAggregate()
        aggregate.add(windowStats(framesPerSecond = 12, isWarmup = true))
        aggregate.add(windowStats(framesPerSecond = 29))

        val stats = aggregate.withoutRecording()

        assertEquals(1, stats.windowCount)
        assertEquals(29.0, stats.minFps, 0.0)
    }

    @Test
    fun `la mediane et le minimum portent sur les fenetres retenues`() {
        val aggregate = ThroughputRunAggregate()
        listOf(29, 24, 30, 28, 27).forEach { aggregate.add(windowStats(framesPerSecond = it)) }

        val stats = aggregate.withoutRecording()

        // Triées : 24, 27, 28, 29, 30. Index de la médiane (5 − 1) × 0,5 = 2, donc 28.
        assertEquals(5, stats.windowCount)
        assertEquals(28.0, stats.medianFps, 0.0)
        assertEquals(24.0, stats.minFps, 0.0)
    }

    @Test
    fun `la capture seule et l'enregistrement sont agreges separement`() {
        val aggregate = ThroughputRunAggregate()
        aggregate.add(windowStats(framesPerSecond = 30))
        aggregate.add(windowStats(framesPerSecond = 29))
        aggregate.add(windowStats(framesPerSecond = 25, coverage = RecordingCoverage.FULL))

        // C'est tout l'intérêt des deux agrégats : une série s'arrêtant seule à 900 images, un run
        // avec enregistrement contient forcément des fenêtres des deux sortes.
        assertEquals(2, aggregate.withoutRecording().windowCount)
        assertEquals(29.0, aggregate.withoutRecording().minFps, 0.0)
        assertEquals(1, aggregate.whileRecording().windowCount)
        assertEquals(25.0, aggregate.whileRecording().minFps, 0.0)
    }

    @Test
    fun `une fenetre a cheval sur un enregistrement n'entre dans aucun agregat`() {
        val aggregate = ThroughputRunAggregate()
        aggregate.add(windowStats(framesPerSecond = 20, coverage = RecordingCoverage.INDETERMINATE))

        assertEquals(0, aggregate.withoutRecording().windowCount)
        assertEquals(0, aggregate.whileRecording().windowCount)
    }

    @Test
    fun `aucune fenetre retenue ne rend ni compte faux ni valeur non finie`() {
        val stats = ThroughputRunAggregate().whileRecording()

        assertEquals(0, stats.windowCount)
        assertEquals(0.0, stats.medianFps, 0.0)
        assertEquals(0.0, stats.minFps, 0.0)
        assertFalse(stats.medianFps.isNaN())
        assertFalse(stats.minFps.isNaN())
    }
}
