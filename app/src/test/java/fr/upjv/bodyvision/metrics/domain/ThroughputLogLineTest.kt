package fr.upjv.bodyvision.metrics.domain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

private const val NANOS_PER_MILLI = 1_000_000L

/** Une fenêtre pleine et connue, pour que la ligne entière puisse servir d'oracle. */
private val MEASURED_WINDOW =
    WindowStats(
        startNanos = 0L,
        durationNanos = 5_012 * NANOS_PER_MILLI,
        sensorFrameCount = 150,
        receivedFrameCount = 147,
        gateDroppedCount = 0,
        otherDroppedCount = 0,
        inferenceCount = 147,
        inferenceFailedCount = 0,
        latencySampleCount = 147,
        latencyMedianMs = 26.1,
        latencyP95Ms = 31.8,
        minReceivedGapMs = 33.3,
        coverage = RecordingCoverage.FULL,
        isWarmup = false,
    )

private val MEASURED_SENSOR =
    SensorFrameFacts(
        frameDurationNanos = 33_300_000L,
        exposureNanos = 16_700_000L,
        aeFpsMin = 15,
        aeFpsMax = 30,
    )

private val EMPTY_WINDOW =
    WindowStats(
        startNanos = 0L,
        durationNanos = 0L,
        sensorFrameCount = 0,
        receivedFrameCount = 0,
        gateDroppedCount = 0,
        otherDroppedCount = 0,
        inferenceCount = 0,
        inferenceFailedCount = 0,
        latencySampleCount = 0,
        latencyMedianMs = 0.0,
        latencyP95Ms = 0.0,
        minReceivedGapMs = null,
        coverage = RecordingCoverage.INDETERMINATE,
        isWarmup = true,
    )

private val UNKNOWN_SENSOR =
    SensorFrameFacts(frameDurationNanos = null, exposureNanos = null, aeFpsMin = null, aeFpsMax = null)

private fun lineOf(
    window: WindowStats,
    sensor: SensorFrameFacts,
    thermalLabel: String = "LIGHT",
    withoutRecording: AggregateStats = AggregateStats(windowCount = 55, medianFps = 29.2, minFps = 27.4),
    whileRecording: AggregateStats = AggregateStats(windowCount = 40, medianFps = 28.1, minFps = 26.0),
) = formatThroughputLine(
    elapsedNanos = 307_400_000_000L,
    window = window,
    sensor = sensor,
    thermalLabel = thermalLabel,
    withoutRecording = withoutRecording,
    whileRecording = whileRecording,
)

/**
 * La ligne de journal : son format, et surtout sa **locale**. Le téléphone de test est en français,
 * et une virgule décimale rendrait tout un relevé de dix minutes impossible à recopier dans un
 * tableur ou un script sans erreur.
 */
class ThroughputLogLineTest {
    private lateinit var defaultLocale: Locale

    @Before
    fun rememberDefaultLocale() {
        defaultLocale = Locale.getDefault()
    }

    @After
    fun restoreDefaultLocale() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `une fenetre connue redonne exactement la ligne attendue, locale francaise forcee`() {
        Locale.setDefault(Locale.FRANCE)

        val line = lineOf(MEASURED_WINDOW, MEASURED_SENSOR)

        assertEquals(
            "phase=mesure t=307.4 win_ms=5012.0 sensor=150 recv=147 drop_gate=0 drop_other=0 " +
                "inf=147 fail=0 fps=29.33 lat_n=147 lat_med_ms=26.1 lat_p95_ms=31.8 dt_min_ms=33.3 " +
                "fdur_ms=33.3 expo_ms=16.7 ae=15-30 thermal=LIGHT rec=1 " +
                "w0_med_fps=29.20 w0_min_fps=27.40 w0_n=55 w1_med_fps=28.10 w1_min_fps=26.00 w1_n=40",
            line,
        )
        // Et la règle elle-même, pour que l'intention survive à un changement de colonnes : aucune
        // virgule nulle part dans la ligne.
        assertFalse(line.contains(","))
    }

    @Test
    fun `une fenetre vide et un capteur muet ne mettent ni NaN ni valeur inventee dans la ligne`() {
        Locale.setDefault(Locale.FRANCE)

        val line =
            lineOf(
                window = EMPTY_WINDOW,
                sensor = UNKNOWN_SENSOR,
                thermalLabel = "NA",
                withoutRecording = AggregateStats(windowCount = 0, medianFps = 0.0, minFps = 0.0),
                whileRecording = AggregateStats(windowCount = 0, medianFps = 0.0, minFps = 0.0),
            )

        assertFalse(line.contains("NaN"))
        assertFalse(line.contains("Infinity"))
        assertFalse(line.contains(","))
        // Ce qui manque est annoncé comme manquant, et jamais remplacé par un zéro, qui aurait
        // l'air d'une mesure.
        assertTrue(line.contains(" dt_min_ms=NA"))
        assertTrue(line.contains(" fdur_ms=NA"))
        assertTrue(line.contains(" expo_ms=NA"))
        assertTrue(line.contains(" ae=NA"))
        assertTrue(line.contains(" thermal=NA"))
        assertTrue(line.contains("phase=chauffe"))
        assertTrue(line.contains(" fps=0.00"))
    }

    @Test
    fun `le symbole d'enregistrement distingue les trois couvertures`() {
        fun symbolFor(coverage: RecordingCoverage): String {
            val line = lineOf(MEASURED_WINDOW.copy(coverage = coverage), MEASURED_SENSOR)
            return line.substringAfter(" rec=").substringBefore(" ")
        }

        assertEquals("0", symbolFor(RecordingCoverage.NONE))
        assertEquals("1", symbolFor(RecordingCoverage.FULL))
        assertEquals("x", symbolFor(RecordingCoverage.INDETERMINATE))
    }
}
