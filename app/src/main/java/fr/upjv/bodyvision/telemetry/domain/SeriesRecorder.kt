package fr.upjv.bodyvision.telemetry.domain

import fr.upjv.bodyvision.pose.domain.PoseFrame
import fr.upjv.bodyvision.pose.domain.PoseLandmarkType
import fr.upjv.bodyvision.pose.domain.UprightImageSize

/** Ce que l'accumulateur répond à une image : la série reste ouverte, ou elle vient de se fermer. */
enum class SeriesStatus {
    OPEN,
    CLOSED,
}

/**
 * Faut-il garder cette image ? **Oui si c'est la première, ou si l'échéance d'échantillonnage est
 * passée.**
 *
 * Sous-échantillonner **par le temps** et non « une image sur trois » : le débit réel va de 15 à
 * 30 images par seconde selon la lumière et la chauffe, donc un pas fixe donnerait tantôt 5, tantôt
 * 10 images par seconde — et le `nominalFps` annoncé au serveur serait faux.
 *
 * **Une échéance, et non un écart à la dernière image gardée** — c'est la différence qui décide du
 * débit, et elle n'est pas intuitive. « Au moins 100 ms depuis la dernière gardée » est un *seuil*,
 * pas une cadence : sur une grille de 33 ms, trois images ne font que 99 ms, il en faut donc quatre,
 * et le débit tombe à 7,6 par seconde alors que le document annonce 10 — un `nominalFps` faux de
 * 24 %, dont le serveur tirerait des vitesses erronées. L'échéance, elle, avance de
 * [SAMPLE_PERIOD_MS] exactement à chaque image gardée, donc elle ne dérive pas : on garde la
 * première image passé chaque multiple de 100 ms, et le débit vaut 10 par seconde quelle que soit
 * la période d'entrée, à une image près.
 */
internal fun isFrameDue(
    timestampMs: Long,
    nextSampleMs: Long?,
): Boolean = nextSampleMs == null || timestampMs >= nextSampleMs

/**
 * L'échéance suivante, une fois [keptTimestampMs] gardée : **la précédente plus une période**, et
 * surtout pas « maintenant plus une période », qui serait le seuil dérivant ci-dessus.
 *
 * La seconde branche est un **recalage**, et elle n'a que deux cas d'emploi, tous deux réels :
 * - l'échéance a pris du retard pendant un trou du flux — personne dans le cadre pendant cinq
 *   secondes, donc aucune image gardée pendant que le temps avançait. Sans recalage, elle resterait
 *   derrière et garderait **toutes** les images au retour de la personne, le temps de rattraper :
 *   une rafale à 30 images par seconde au milieu du fichier ;
 * - l'échéance tombe **exactement** sur l'horodatage gardé. C'est ce qui garantit `t` strictement
 *   croissant, exigé par le contrat : l'échéance rendue est toujours **supérieure** à
 *   [keptTimestampMs], donc une seconde image au même horodatage n'est jamais due. Aucun test de
 *   doublon à écrire ailleurs.
 */
internal fun nextSampleAfter(
    nextSampleMs: Long?,
    keptTimestampMs: Long,
): Long {
    val scheduled = (nextSampleMs ?: keptTimestampMs) + SAMPLE_PERIOD_MS
    return if (scheduled > keptTimestampMs) scheduled else keptTimestampMs + SAMPLE_PERIOD_MS
}

/**
 * Accumule les images d'**une** série, décide de ce qui est gardé, et dit quand la série est
 * terminée.
 *
 * Tout ce qui décide est ici, et rien d'Android : c'est la pièce qu'on peut éprouver sur la JVM,
 * sans caméra, sans ML Kit et sans ordonnanceur.
 *
 * **À usage unique.** Une fois la série close — par [complete], par un changement de taille d'image
 * ou par la [MAX_SERIES_FRAMES]e image — [accept] rend `CLOSED` et ne garde plus rien. Un
 * accumulateur recyclé par erreur pour un second enregistrement ne produirait donc pas une série
 * mélangée : il ne produirait rien du tout, ce qui se voit tout de suite.
 *
 * **Pas sûr entre fils d'exécution**, et il n'a pas à l'être : un seul collecteur l'alimente, et
 * celui qui l'arrête attend la fin de ce collecteur avant de le lire. Un verrou ici ne protégerait
 * de rien d'autre que d'un mauvais appel.
 *
 * @param batchId l'UUID de la série, tiré par l'appelant à la création de l'enregistrement.
 * @param landmarkOrder les 33 types dans l'ordre des indices ML Kit — voir [orderedLandmarksOf].
 * @param wallClock l'horloge murale, en millisecondes depuis l'epoch. Un paramètre et non un appel
 *   direct à `System.currentTimeMillis()` : c'est une frontière, et une frontière se remplace dans
 *   un test. Sans ça, aucun test ne pourrait comparer le JSON produit à une chaîne attendue.
 */
class SeriesRecorder(
    private val batchId: String,
    private val landmarkOrder: List<PoseLandmarkType>,
    private val wallClock: () -> Long,
) {
    private val frames = mutableListOf<TelemetryFrame>()

    private var imageSize: UprightImageSize? = null
    private var firstKeptTimestampMs: Long? = null

    /** L'horodatage de la prochaine image attendue. `null` tant qu'aucune image n'a été gardée. */
    private var nextSampleMs: Long? = null
    private var startedAtEpochMs: Long? = null
    private var isClosed = false

    /** Images retenues dans la série, donc celles qui seront dans le fichier. */
    val keptFrameCount: Int get() = frames.size

    /**
     * Images **écartées** : pose incomplète, ou valeur non finie. C'est le chiffre qui signale une
     * anomalie, et c'est pour ça qu'il est séparé de [sampledOutFrameCount].
     */
    var discardedFrameCount: Int = 0
        private set

    /**
     * Images que le sous-échantillonnage n'a pas retenues. À 30 images par seconde, deux sur trois
     * tombent ici : c'est le fonctionnement normal, pas un défaut. Les compter avec les écartées
     * noierait le seul nombre qui compte.
     */
    var sampledOutFrameCount: Int = 0
        private set

    /**
     * Soumet une image à la série.
     *
     * L'ordre des règles décide de ce qui est compté, donc il est écrit :
     * 1. une taille d'image différente de celle de la série **termine** la série — sur l'image
     *    précédente, celle-ci n'étant même pas examinée ;
     * 2. une image qui arrive avant l'échéance d'échantillonnage est hors échantillon ;
     * 3. une image incomplète ou non finie est écartée et comptée ;
     * 4. sinon elle est gardée, et la série se termine si elle était la dernière que le contrat
     *    autorise.
     */
    fun accept(frame: PoseFrame): SeriesStatus {
        if (isClosed) return SeriesStatus.CLOSED

        // La taille est figée par la première image **vue** et non par la première gardée : elle
        // vient de la caméra, pas de la personne dans le cadre. Tout le fichier porte donc bien la
        // taille annoncée une seule fois dans `capture`.
        val seriesImageSize = imageSize
        if (seriesImageSize == null) {
            imageSize = frame.imageSize
        } else if (frame.imageSize != seriesImageSize) {
            // Rotation de l'appareil : les coordonnées suivantes sont dans un autre repère, et le
            // contrat ne porte qu'une taille par série. On ne mélange jamais les deux.
            return close()
        }

        if (!isFrameDue(frame.timestampMs, nextSampleMs)) {
            sampledOutFrameCount++
            return SeriesStatus.OPEN
        }

        val landmarks = orderedLandmarksOf(frame.landmarks, landmarkOrder)
        if (landmarks == null) {
            discardedFrameCount++
            return SeriesStatus.OPEN
        }

        // L'horloge murale est lue ici, à la **première image gardée**, et nulle part ailleurs :
        // c'est ce qui fait que le `t` de cette image vaut 0 et que `startedAt` désigne le même
        // instant qu'elle, comme le contrat le demande.
        val firstTimestampMs =
            firstKeptTimestampMs ?: frame.timestampMs.also {
                firstKeptTimestampMs = it
                startedAtEpochMs = wallClock()
            }

        frames +=
            TelemetryFrame(
                offsetMs = frame.timestampMs - firstTimestampMs,
                landmarks = landmarks,
            )
        nextSampleMs = nextSampleAfter(nextSampleMs, frame.timestampMs)

        return if (frames.size >= MAX_SERIES_FRAMES) close() else SeriesStatus.OPEN
    }

    /**
     * Ferme la série et rend ce qu'elle contient, ou `null` s'il n'y a rien à écrire.
     *
     * `null` dans deux cas, et ce sont des résultats, pas des erreurs : aucune image n'est arrivée,
     * ou aucune n'a été gardée. Dans les deux, il n'y a ni taille d'image ni `startedAt` à
     * annoncer — deux champs que le contrat exige — donc il n'y a pas de série valide à écrire.
     * C'est à l'appelant de le journaliser avec [discardedFrameCount].
     */
    fun complete(): TelemetrySeries? {
        close()
        val seriesImageSize = imageSize ?: return null
        val startedAt = startedAtEpochMs ?: return null
        return TelemetrySeries(
            batchId = batchId,
            startedAtEpochMs = startedAt,
            imageSize = seriesImageSize,
            // Une copie : la série est immuable, et l'accumulateur garde sa liste.
            frames = frames.toList(),
        )
    }

    private fun close(): SeriesStatus {
        isClosed = true
        return SeriesStatus.CLOSED
    }
}
