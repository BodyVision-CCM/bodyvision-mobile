package fr.upjv.bodyvision.metrics.domain

/**
 * La chauffe ML Kit, exclue de toute statistique. C'est une règle du projet : l'accélération
 * matérielle est choisie de façon non bloquante, et les premières exécutions tournent sur le
 * processeur.
 */
const val WARMUP_DURATION_MS = 30_000L

private const val WARMUP_DURATION_NANOS = WARMUP_DURATION_MS * 1_000_000L

/**
 * Cette fenêtre est-elle de la chauffe ?
 *
 * **La chauffe part de la première inférence réussie** et non du démarrage de la sonde : le temps
 * du dialogue de permission, ou celui de se placer dans le cadre, ne doit pas manger les 30
 * secondes pendant lesquelles ML Kit choisit son accélération. Sans ça, une mise en route lente
 * ferait entrer de vraies images de chauffe dans les statistiques, et le minimum de débit du run
 * serait celui d'une inférence encore sur le processeur.
 *
 * Tant qu'aucune inférence n'a réussi, **tout** est de la chauffe : on ne sait pas encore quand elle
 * a commencé.
 *
 * C'est le **début** de la fenêtre qui décide et non sa fin : une fenêtre à cheval sur la trentième
 * seconde contient des images de chauffe, donc elle est écartée en entier.
 */
fun isWarmupWindow(
    windowStartNanos: Long,
    firstInferenceNanos: Long?,
): Boolean = firstInferenceNanos == null || windowStartNanos - firstInferenceNanos < WARMUP_DURATION_NANOS

/**
 * Le verdict d'un run sur les fenêtres retenues : combien, leur débit médian, et celui de la pire.
 *
 * Les deux critères d'acceptation de SCRUM-41 sont exactement ces deux nombres — médiane ≥ 28,
 * aucune fenêtre sous 24 — d'où leur présence sur chaque ligne de journal : la dernière ligne d'un
 * run de 10 minutes est alors son verdict, sans 120 lignes à recopier dans un tableur.
 */
data class AggregateStats(
    val windowCount: Int,
    val medianFps: Double,
    val minFps: Double,
)

/**
 * Cumule les fenêtres d'un run, **séparément selon l'enregistrement**.
 *
 * Deux agrégats et non un, parce qu'une série s'arrête d'elle-même à 900 images, soit environ 90
 * secondes : un run de 10 minutes avec enregistrement contient forcément des fenêtres sans
 * enregistrement entre deux séries, et les mélanger rendrait illisible l'écart qu'on cherche
 * justement à expliquer.
 *
 * Une fenêtre de chauffe, et une fenêtre que l'enregistrement n'a couverte qu'en partie, n'entrent
 * dans **aucun** des deux : la seconde décrirait un mélange.
 *
 * **Pas sûr entre fils d'exécution**, comme [ThroughputWindow], et pour la même raison.
 */
class ThroughputRunAggregate {
    private val withoutRecordingFps = mutableListOf<Double>()
    private val whileRecordingFps = mutableListOf<Double>()

    fun add(stats: WindowStats) {
        if (stats.isWarmup) return
        when (stats.coverage) {
            RecordingCoverage.NONE -> withoutRecordingFps += stats.framesPerSecond
            RecordingCoverage.FULL -> whileRecordingFps += stats.framesPerSecond
            RecordingCoverage.INDETERMINATE -> Unit
        }
    }

    /**
     * Les fenêtres de capture seule. Une **fonction** et non une propriété : elle trie, donc elle
     * travaille, et un nom de fonction l'annonce.
     */
    fun withoutRecording(): AggregateStats = aggregateOf(withoutRecordingFps)

    /** Les fenêtres entièrement couvertes par un enregistrement. */
    fun whileRecording(): AggregateStats = aggregateOf(whileRecordingFps)
}

/** Aucune fenêtre retenue rend des zéros et non des `NaN` : voir [percentileOf]. */
private fun aggregateOf(values: List<Double>): AggregateStats {
    if (values.isEmpty()) return AggregateStats(windowCount = 0, medianFps = 0.0, minFps = 0.0)
    val sorted = values.sorted()
    return AggregateStats(
        windowCount = sorted.size,
        medianFps = percentileOf(sorted, MEDIAN_FRACTION),
        // Trié par ordre croissant, donc le premier est le minimum.
        minFps = sorted.first(),
    )
}
