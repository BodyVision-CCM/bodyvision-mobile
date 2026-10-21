package fr.upjv.bodyvision.pose.domain

/**
 * Une pose détectée sur une image.
 *
 * Un point non détecté est une **clé absente** de [landmarks], jamais une valeur nulle :
 * les appelants ne peuvent donc pas déréférencer un point manquant par accident.
 * Une image sans personne donne une pose à [landmarks] vide.
 *
 * [timestampMs] est l'horodatage de l'image, relatif au démarrage de l'appareil.
 */
data class PoseFrame(
    val landmarks: Map<PoseLandmarkType, NormalizedLandmark>,
    val timestampMs: Long,
)
