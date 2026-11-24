package fr.upjv.bodyvision.metrics.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NANOS_PER_MILLI = 1_000_000L

/** Tolérance des comparaisons de débit : ce qui est éprouvé ici est la règle, pas l'arrondi. */
private const val FPS_TOLERANCE = 0.01

private fun openWindow(startNanos: Long = 0L) = ThroughputWindow().also { it.start(startNanos) }

private fun ThroughputWindow.receiveFrameAtMs(
    timestampMs: Long,
    isRecording: Boolean = false,
) = onFrameReceived(timestampMs * NANOS_PER_MILLI, isRecording)

private fun ThroughputWindow.succeedWithLatencyMs(vararg latenciesMs: Long) =
    latenciesMs.forEach { onInferenceSucceeded(it * NANOS_PER_MILLI) }

private fun ThroughputWindow.statsAfterMs(
    durationMs: Long,
    sensorFrameCount: Int = 0,
) = statsAt(
    nowNanos = durationMs * NANOS_PER_MILLI,
    sensorFrameCount = sensorFrameCount,
    isWarmup = false,
)

/**
 * L'accumulateur d'une fenêtre de mesure : ce qu'il compte, ce qu'il calcule, et ce qu'il refuse
 * d'inventer. Du Kotlin pur, donc éprouvable sans caméra, sans ML Kit et sans ordonnanceur.
 */
class ThroughputWindowTest {
    @Test
    fun `la mediane et le p95 sur un nombre impair de latences`() {
        val window = openWindow()
        window.succeedWithLatencyMs(30, 10, 50, 20, 40)

        val stats = window.statsAfterMs(5_000)

        // Cinq valeurs triées : l'index de la médiane est (5 − 1) × 0,5 = 2, celui du p95
        // (5 − 1) × 0,95 = 3,8 tronqué à 3.
        assertEquals(30.0, stats.latencyMedianMs, 0.0)
        assertEquals(40.0, stats.latencyP95Ms, 0.0)
    }

    @Test
    fun `la mediane sur un nombre pair prend l'element inferieur du milieu`() {
        val window = openWindow()
        window.succeedWithLatencyMs(10, 20, 30, 40)

        val stats = window.statsAfterMs(5_000)

        // La convention du spike SCRUM-22, et non la moyenne des deux valeurs du milieu, qui
        // donnerait 25. C'est ce choix qui garde les chiffres comparables aux 26,4 ms.
        assertEquals(20.0, stats.latencyMedianMs, 0.0)
        assertEquals(30.0, stats.latencyP95Ms, 0.0)
    }

    @Test
    fun `une seule latence est a la fois la mediane et le p95`() {
        val window = openWindow()
        window.succeedWithLatencyMs(26)

        val stats = window.statsAfterMs(5_000)

        assertEquals(26.0, stats.latencyMedianMs, 0.0)
        assertEquals(26.0, stats.latencyP95Ms, 0.0)
    }

    @Test
    fun `une fenetre vide ne divise par zero ni ne rend de valeur non finie`() {
        val stats = openWindow().statsAfterMs(0)

        assertEquals(0, stats.inferenceCount)
        assertEquals(0, stats.inferenceFailedCount)
        assertEquals(0, stats.latencySampleCount)
        assertEquals(0.0, stats.latencyMedianMs, 0.0)
        assertEquals(0.0, stats.latencyP95Ms, 0.0)
        assertEquals(0.0, stats.framesPerSecond, 0.0)
        assertFalse(stats.framesPerSecond.isNaN())
        assertFalse(stats.framesPerSecond.isInfinite())
        assertNull(stats.minReceivedGapMs)
    }

    @Test
    fun `le debit se calcule sur la duree reelle de la fenetre`() {
        val window = openWindow()
        repeat(29) { window.succeedWithLatencyMs(26) }

        val stats = window.statsAfterMs(1_050)

        // 29 images en 1 050 ms font 27,62 par seconde, et surtout pas 29 : une fenêtre dure
        // toujours un peu plus que sa durée nominale, puisqu'elle se clôt à l'arrivée d'une image.
        assertEquals(27.62, stats.framesPerSecond, FPS_TOLERANCE)
    }

    @Test
    fun `les compteurs d'images sont rendus tels quels`() {
        val window = openWindow()
        repeat(5) { window.receiveFrameAtMs(it * 33L) }
        window.onFrameDroppedByGate()
        window.onFrameDroppedByGate()
        window.onFrameDroppedOtherwise()
        window.succeedWithLatencyMs(26, 27)

        val stats = window.statsAfterMs(5_000, sensorFrameCount = 150)

        assertEquals(150, stats.sensorFrameCount)
        assertEquals(5, stats.receivedFrameCount)
        assertEquals(2, stats.gateDroppedCount)
        assertEquals(1, stats.otherDroppedCount)
        assertEquals(2, stats.inferenceCount)
    }

    @Test
    fun `une inference qui echoue est comptee a part et sans latence`() {
        val window = openWindow()
        repeat(3) { window.receiveFrameAtMs(it * 33L) }
        window.succeedWithLatencyMs(26)
        window.onInferenceFailed()
        window.onInferenceFailed()

        val stats = window.statsAfterMs(1_000)

        // Sans cette colonne, deux images sur trois disparaîtraient du relevé : comptées dans
        // `recv`, absentes partout ailleurs, pour un débit effondré sans explication.
        assertEquals(2, stats.inferenceFailedCount)
        assertEquals(1, stats.inferenceCount)
        // Un échec ne porte pas de latence : la médiane ne tient qu'au seul succès.
        assertEquals(1, stats.latencySampleCount)
        assertEquals(26.0, stats.latencyMedianMs, 0.0)
        // Et le débit ne compte que les succès : c'est la définition du compteur de SCRUM-22.
        assertEquals(1.0, stats.framesPerSecond, FPS_TOLERANCE)
        // L'invariant qui rend la ligne lisible : les quatre issues couvrent les images reçues.
        assertEquals(
            stats.receivedFrameCount,
            stats.gateDroppedCount + stats.otherDroppedCount + stats.inferenceCount + stats.inferenceFailedCount,
        )
    }

    @Test
    fun `le plus petit ecart entre deux images recues est retenu`() {
        val window = openWindow()
        window.receiveFrameAtMs(0)
        window.receiveFrameAtMs(33)
        window.receiveFrameAtMs(100)
        window.receiveFrameAtMs(133)

        val stats = window.statsAfterMs(5_000)

        // Les écarts sont 33, 67 et 33 : le minimum est la période du capteur, les autres sont des
        // images que CameraX a jetées entre deux livraisons.
        assertEquals(33.0, stats.minReceivedGapMs ?: Double.NaN, 0.0)
    }

    @Test
    fun `l'ecart qui enjambe deux fenetres est pris en compte`() {
        val window = openWindow()
        window.receiveFrameAtMs(100)

        window.start(1L)
        window.receiveFrameAtMs(133)
        val stats = window.statsAfterMs(5_000)

        // La dernière image de la fenêtre précédente est gardée pour ça, et pour ça seulement :
        // sans elle, un écart sur 150 serait perdu à chaque frontière.
        assertEquals(33.0, stats.minReceivedGapMs ?: Double.NaN, 0.0)
    }

    @Test
    fun `aucune image enregistree donne une fenetre de capture seule`() {
        val window = openWindow()
        repeat(3) { window.receiveFrameAtMs(it * 33L, isRecording = false) }

        assertEquals(RecordingCoverage.NONE, window.statsAfterMs(5_000).coverage)
    }

    @Test
    fun `toutes les images enregistrees donnent une fenetre entierement couverte`() {
        val window = openWindow()
        repeat(3) { window.receiveFrameAtMs(it * 33L, isRecording = true) }

        assertEquals(RecordingCoverage.FULL, window.statsAfterMs(5_000).coverage)
    }

    @Test
    fun `un enregistrement a cheval rend la fenetre indeterminee`() {
        val window = openWindow()
        window.receiveFrameAtMs(0, isRecording = false)
        window.receiveFrameAtMs(33, isRecording = true)
        window.receiveFrameAtMs(66, isRecording = true)

        // Une série démarrée au milieu de la fenêtre : elle ne décrit ni la capture seule ni
        // l'enregistrement, donc elle n'entrera dans aucun agrégat.
        assertEquals(RecordingCoverage.INDETERMINATE, window.statsAfterMs(5_000).coverage)
    }

    @Test
    fun `une fenetre sans aucune image est indeterminee et non une capture seule`() {
        // Sans cette distinction, un débit de 0 entrerait dans l'agrégat de capture seule et ferait
        // tomber son minimum alors qu'aucune image n'a été perdue.
        assertEquals(RecordingCoverage.INDETERMINATE, openWindow().statsAfterMs(5_000).coverage)
    }

    @Test
    fun `une fenetre n'est echue qu'une fois sa duree nominale atteinte`() {
        val window = openWindow(startNanos = 1_000L)

        assertFalse(window.isElapsedAt(1_000L + (WINDOW_DURATION_MS - 1) * NANOS_PER_MILLI))
        assertTrue(window.isElapsedAt(1_000L + WINDOW_DURATION_MS * NANOS_PER_MILLI))
    }
}
