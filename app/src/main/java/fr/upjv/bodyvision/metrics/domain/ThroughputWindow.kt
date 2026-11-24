package fr.upjv.bodyvision.metrics.domain

/**
 * Durée **nominale** d'une fenêtre de mesure. Nominale et non réelle : la fenêtre se clôt à
 * l'arrivée d'une image, donc elle dure toujours un peu plus, et c'est sa durée réelle qui sert à
 * calculer le débit.
 */
const val WINDOW_DURATION_MS = 5_000L

/**
 * Nombre de latences qu'une fenêtre peut retenir, et un tableau alloué une seule fois : une fenêtre
 * de 5 secondes à 30 images par seconde en contient 150, et il en faudrait plus de 100 par seconde
 * pour atteindre cette borne — ce que l'appareil cible ne fait pas. Au-delà, les latences en trop
 * sont ignorées et [WindowStats.latencySampleCount] dit sur combien de valeurs les percentiles
 * reposent.
 *
 * Une fenêtre **allongée** par une interruption du flux ne la dépasse pas non plus : tant qu'aucune
 * image n'arrive, aucune latence n'est ajoutée.
 */
private const val LATENCY_CAPACITY = 512

private const val WINDOW_DURATION_NANOS = WINDOW_DURATION_MS * 1_000_000L

/** Ce qu'un enregistrement a couvert d'une fenêtre. */
enum class RecordingCoverage {
    /** Aucune image de la fenêtre n'a été enregistrée. */
    NONE,

    /** Toutes les images de la fenêtre l'ont été. */
    FULL,

    /**
     * Une partie seulement, ou aucune image du tout. Dans les deux cas la fenêtre ne décrit ni la
     * capture seule ni l'enregistrement, donc elle n'entre dans aucun agrégat.
     */
    INDETERMINATE,
}

/**
 * Ce qu'une fenêtre a observé, une fois close. Immuable : c'est une mesure, pas un état.
 *
 * [sensorFrameCount] vient d'ailleurs que les autres — du rappel de capture camera2 — et c'est tout
 * l'intérêt : lui seul dit combien d'images ont été **produites**, là où [receivedFrameCount] dit
 * combien nous en avons **reçues**. L'écart entre les deux est ce que CameraX a jeté en silence.
 *
 * Les quatre issues d'une image reçue sont comptées séparément, et elles couvrent tous les chemins
 * du code : portillon fermé, abandon avant inférence, inférence réussie, inférence échouée. Donc
 * `recv = gate + other + inf + fail`, **à deux réserves près qui ne sont pas des trous** :
 * - une image reçue à la fin d'une fenêtre dont l'inférence réussit dans la suivante compte dans
 *   les deux ; l'écart est d'au plus une image par frontière, et il se compense d'une fenêtre à
 *   l'autre ;
 * - à la fermeture du détecteur, les `Task` en vol sont **annulées** : ni succès ni échec. Cela ne
 *   concerne que la dernière fenêtre d'une session, celle qu'on ne relève pas.
 */
data class WindowStats(
    val startNanos: Long,
    val durationNanos: Long,
    val sensorFrameCount: Int,
    val receivedFrameCount: Int,
    val gateDroppedCount: Int,
    val otherDroppedCount: Int,
    val inferenceCount: Int,
    val inferenceFailedCount: Int,
    val latencySampleCount: Int,
    val latencyMedianMs: Double,
    val latencyP95Ms: Double,
    val minReceivedGapMs: Double?,
    val coverage: RecordingCoverage,
    val isWarmup: Boolean,
) {
    /**
     * Le débit d'inférence, **sur la durée réelle de la fenêtre** et jamais sur les 5 secondes
     * supposées : 29 images en 1 050 ms font 27,62 par seconde et non 29, et afficher 29 ferait
     * rouvrir une décision figée pour un écart qui n'est pas dans l'inférence mais dans la fenêtre
     * de mesure.
     *
     * Une durée nulle ou négative rend 0 et non `NaN` : il n'y a pas de débit à annoncer.
     */
    val framesPerSecond: Double
        get() = if (durationNanos <= 0L) 0.0 else inferenceCount * NANOS_PER_SECOND / durationNanos

    val durationMs: Double
        get() = durationNanos / NANOS_PER_MILLI
}

/**
 * Accumule ce qu'une fenêtre de mesure observe, et rend ses statistiques quand elle se clôt.
 *
 * Tout le calcul est ici, et rien d'Android : c'est la pièce qu'on peut éprouver sur la JVM, sans
 * caméra, sans ML Kit et sans ordonnanceur.
 *
 * **Pas sûr entre fils d'exécution, et il n'a pas à l'être** : un seul thread l'alimente — celui de
 * l'analyse — et les compteurs venus d'un autre thread, comme les images du capteur, lui sont
 * **passés** à la clôture plutôt que poussés au fil de l'eau.
 *
 * Réutilisé d'une fenêtre à la suivante par [start] : le tableau des latences est alloué une fois
 * pour toute la durée de vie de la sonde, donc le chemin chaud n'alloue rien.
 */
class ThroughputWindow {
    /** Lu par l'appelant, qui en a besoin pour décider si la fenêtre est de la chauffe. */
    var startNanos: Long = 0L
        private set

    private val latenciesNanos = LongArray(LATENCY_CAPACITY)
    private var latencySampleCount = 0
    private var receivedFrameCount = 0
    private var recordingFrameCount = 0
    private var gateDroppedCount = 0
    private var otherDroppedCount = 0
    private var inferenceCount = 0
    private var inferenceFailedCount = 0
    private var minReceivedGapNanos: Long? = null

    /**
     * L'horodatage de la dernière image reçue, **gardé d'une fenêtre à l'autre** par [start] : sans
     * ça, l'écart qui enjambe une frontière de fenêtre serait perdu, soit un écart sur 150. Un
     * écart énorme, laissé par une interruption du flux, ne gêne pas : seul le **minimum** compte.
     */
    private var previousReceivedNanos: Long? = null

    /**
     * Ouvre une fenêtre à [nowNanos] et oublie la précédente. Tout est remis à zéro **sauf**
     * [previousReceivedNanos] : voir son commentaire.
     */
    fun start(nowNanos: Long) {
        startNanos = nowNanos
        latencySampleCount = 0
        receivedFrameCount = 0
        recordingFrameCount = 0
        gateDroppedCount = 0
        otherDroppedCount = 0
        inferenceCount = 0
        inferenceFailedCount = 0
        minReceivedGapNanos = null
    }

    fun isElapsedAt(nowNanos: Long): Boolean = nowNanos - startNanos >= WINDOW_DURATION_NANOS

    /**
     * Une image livrée à l'analyseur, qu'elle finisse en inférence ou non.
     *
     * [imageTimestampNanos] est l'horodatage **du capteur** porté par l'image, et non l'instant
     * présent : c'est de lui que sort [WindowStats.minReceivedGapMs], donc la période réelle du
     * capteur.
     *
     * [isRecording] est échantillonné ici, une fois par image : c'est ce qui permet de dire à la
     * clôture si l'enregistrement a couvert toute la fenêtre ou seulement une part.
     */
    fun onFrameReceived(
        imageTimestampNanos: Long,
        isRecording: Boolean,
    ) {
        receivedFrameCount++
        if (isRecording) recordingFrameCount++

        val previous = previousReceivedNanos
        if (previous != null) {
            val gap = imageTimestampNanos - previous
            val smallest = minReceivedGapNanos
            // Un horodatage qui n'avance pas ne décrit aucune période : seuls les écarts positifs
            // comptent. Un horodatage qui recule est un cas normal après une discontinuité.
            if (gap > 0L && (smallest == null || gap < smallest)) minReceivedGapNanos = gap
        }
        previousReceivedNanos = imageTimestampNanos
    }

    /** Une image jetée parce que le portillon était fermé : une inférence était déjà en vol. */
    fun onFrameDroppedByGate() {
        gateDroppedCount++
    }

    /**
     * Une image abandonnée avant l'inférence pour **toute autre raison** que le portillon : pas de
     * tampon `Image`, source en cours de fermeture, dimension refusée, ou inférence non lancée.
     *
     * Séparé du portillon à dessein : les deux ne disent pas la même chose. Le portillon est une
     * contre-pression attendue, le reste est une anomalie.
     */
    fun onFrameDroppedOtherwise() {
        otherDroppedCount++
    }

    /** Une inférence qui a réussi, et sa latence du lancement de `process()` au succès. */
    fun onInferenceSucceeded(latencyNanos: Long) {
        inferenceCount++
        if (latencySampleCount < LATENCY_CAPACITY) {
            latenciesNanos[latencySampleCount] = latencyNanos
            latencySampleCount++
        }
    }

    /**
     * Une inférence **lancée qui a échoué**. Sa propre colonne, et c'est la relecture qui l'a
     * exigé : sans elle, une image dont ML Kit refuse l'inférence est comptée dans les images
     * reçues et dans rien d'autre. On lirait alors un débit effondré avec une latence normale, un
     * capteur normal et aucune perte — soit un tableau d'hypothèses auquel rien ne correspond,
     * pour un relevé de dix minutes à refaire.
     *
     * Sans latence : une inférence qui échoue n'en a pas une qui veuille dire quelque chose.
     */
    fun onInferenceFailed() {
        inferenceFailedCount++
    }

    /**
     * Clôt la fenêtre à [nowNanos] et rend ce qu'elle a vu.
     *
     * [sensorFrameCount] et [isWarmup] sont **donnés** et non calculés ici : le premier est compté
     * sur le thread de la caméra, le second dépend de la première inférence du run, et cette classe
     * ne connaît ni l'un ni l'autre. Elle ne fait aucun effet de bord : [start] seul rouvre.
     */
    fun statsAt(
        nowNanos: Long,
        sensorFrameCount: Int,
        isWarmup: Boolean,
    ): WindowStats {
        val sortedLatencies = latenciesNanos.copyOf(latencySampleCount).also(LongArray::sort)
        return WindowStats(
            startNanos = startNanos,
            durationNanos = nowNanos - startNanos,
            sensorFrameCount = sensorFrameCount,
            receivedFrameCount = receivedFrameCount,
            gateDroppedCount = gateDroppedCount,
            otherDroppedCount = otherDroppedCount,
            inferenceCount = inferenceCount,
            inferenceFailedCount = inferenceFailedCount,
            latencySampleCount = latencySampleCount,
            latencyMedianMs = percentileMsOf(sortedLatencies, MEDIAN_FRACTION),
            latencyP95Ms = percentileMsOf(sortedLatencies, P95_FRACTION),
            minReceivedGapMs = minReceivedGapNanos?.let { it / NANOS_PER_MILLI },
            coverage = recordingCoverageOf(receivedFrameCount, recordingFrameCount),
            isWarmup = isWarmup,
        )
    }
}

/**
 * Ce que l'enregistrement a couvert, d'après le nombre d'images **reçues** et le nombre d'entre
 * elles qui étaient enregistrées.
 *
 * Une fenêtre **sans aucune image** est indéterminée et non « sans enregistrement » : la compter
 * comme une fenêtre de capture seule mettrait un débit de 0 dans l'agrégat et ferait tomber le
 * minimum sans qu'aucune image ait été perdue.
 */
internal fun recordingCoverageOf(
    receivedFrameCount: Int,
    recordingFrameCount: Int,
): RecordingCoverage =
    when {
        receivedFrameCount == 0 -> RecordingCoverage.INDETERMINATE
        recordingFrameCount == 0 -> RecordingCoverage.NONE
        recordingFrameCount == receivedFrameCount -> RecordingCoverage.FULL
        else -> RecordingCoverage.INDETERMINATE
    }
