package fr.upjv.bodyvision.metrics.domain

import java.util.Locale

/** Ce qu'on écrit à la place d'un nombre qu'on n'a pas. Jamais un 0, qui serait une mesure. */
private const val UNAVAILABLE = "NA"

/** Les deux phases d'un run, nommées comme dans le spike SCRUM-22. */
private const val PHASE_WARMUP = "chauffe"
private const val PHASE_MEASURE = "mesure"

/**
 * Ce que le capteur dit de lui-même, lu sur la **dernière image complétée**, à une image près : les
 * trois valeurs sont publiées séparément par le thread de la caméra, donc rien ne garantit
 * qu'elles viennent toutes de la même. Sans conséquence sur ce qu'on en tire — une période
 * d'image et une exposition ne changent qu'avec la lumière, pas d'une image à la suivante.
 *
 * Des nombres bruts et non une chaîne déjà formatée : c'est ici, dans le domaine, que la locale est
 * décidée, et la frontière camera2 n'a pas à le savoir.
 *
 * Tout est nullable parce que tout peut manquer : une clé est absente d'un résultat de capture sans
 * que ce soit une anomalie, et aucune image n'est encore arrivée au début d'un run.
 */
data class SensorFrameFacts(
    val frameDurationNanos: Long?,
    val exposureNanos: Long?,
    val aeFpsMin: Int?,
    val aeFpsMax: Int?,
)

/**
 * La ligne de journal d'une fenêtre : **une seule par fenêtre**, et rien par image.
 *
 * **Tous les nombres sont formatés en [Locale.ROOT]**, et c'est un piège vécu, pas une précaution :
 * le téléphone de test est en français, `String.format("%.2f", 29.33)` y rendrait `29,33`, et une
 * virgule décimale rend un relevé impossible à recopier dans un tableur ou un script sans erreur.
 * Un test force `Locale.FRANCE` avant de formater pour le prouver.
 *
 * Le format est fait de paires `clé=valeur` séparées par une espace, parce qu'il est relu par un
 * humain **et** par un tableur. Les colonnes, dans l'ordre :
 *
 * - `phase`, `t`, `win_ms` — où on en est, et la durée **réelle** de la fenêtre ;
 * - `sensor`, `recv`, `drop_gate`, `drop_other`, `inf`, `fail` — le trajet des images, du capteur à
 *   l'inférence. `sensor − recv` est ce que CameraX a jeté en silence, et les quatre dernières
 *   colonnes sont les quatre issues possibles d'une image reçue : voir [WindowStats] ;
 * - `fps`, `lat_n`, `lat_med_ms`, `lat_p95_ms` — débit et latence, les deux grandeurs du ticket ;
 * - `dt_min_ms`, `fdur_ms`, `expo_ms`, `ae` — ce que fait le capteur, et pourquoi ;
 * - `thermal`, `rec` — les conditions de la fenêtre ;
 * - `w0_*` et `w1_*` — le cumul du run hors chauffe, capture seule puis enregistrement.
 */
fun formatThroughputLine(
    elapsedNanos: Long,
    window: WindowStats,
    sensor: SensorFrameFacts,
    thermalLabel: String,
    withoutRecording: AggregateStats,
    whileRecording: AggregateStats,
): String =
    buildString {
        append("phase=").append(if (window.isWarmup) PHASE_WARMUP else PHASE_MEASURE)
        append(" t=").append(oneDecimal(elapsedNanos / NANOS_PER_SECOND))
        append(" win_ms=").append(oneDecimal(window.durationMs))
        append(" sensor=").append(window.sensorFrameCount)
        append(" recv=").append(window.receivedFrameCount)
        append(" drop_gate=").append(window.gateDroppedCount)
        append(" drop_other=").append(window.otherDroppedCount)
        append(" inf=").append(window.inferenceCount)
        append(" fail=").append(window.inferenceFailedCount)
        append(" fps=").append(twoDecimals(window.framesPerSecond))
        append(" lat_n=").append(window.latencySampleCount)
        append(" lat_med_ms=").append(oneDecimal(window.latencyMedianMs))
        append(" lat_p95_ms=").append(oneDecimal(window.latencyP95Ms))
        append(" dt_min_ms=").append(oneDecimalOrUnavailable(window.minReceivedGapMs))
        append(" fdur_ms=").append(millisOrUnavailable(sensor.frameDurationNanos))
        append(" expo_ms=").append(millisOrUnavailable(sensor.exposureNanos))
        append(" ae=").append(aeRange(sensor.aeFpsMin, sensor.aeFpsMax))
        append(" thermal=").append(thermalLabel)
        append(" rec=").append(coverageSymbol(window.coverage))
        appendAggregate("w0", withoutRecording)
        appendAggregate("w1", whileRecording)
    }

private fun StringBuilder.appendAggregate(
    prefix: String,
    stats: AggregateStats,
) {
    append(" ").append(prefix).append("_med_fps=").append(twoDecimals(stats.medianFps))
    append(" ").append(prefix).append("_min_fps=").append(twoDecimals(stats.minFps))
    append(" ").append(prefix).append("_n=").append(stats.windowCount)
}

/**
 * `0` capture seule, `1` fenêtre entièrement enregistrée, `x` le reste — une couverture partielle,
 * ou une fenêtre sans image. Trois symboles d'un caractère pour que la colonne reste lisible.
 */
private fun coverageSymbol(coverage: RecordingCoverage): String =
    when (coverage) {
        RecordingCoverage.NONE -> "0"
        RecordingCoverage.FULL -> "1"
        RecordingCoverage.INDETERMINATE -> "x"
    }

private fun aeRange(
    minimum: Int?,
    maximum: Int?,
): String = if (minimum == null || maximum == null) UNAVAILABLE else "$minimum-$maximum"

private fun millisOrUnavailable(nanos: Long?): String =
    if (nanos == null) UNAVAILABLE else oneDecimal(nanos / NANOS_PER_MILLI)

private fun oneDecimalOrUnavailable(value: Double?): String = if (value == null) UNAVAILABLE else oneDecimal(value)

private fun oneDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

private fun twoDecimals(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
