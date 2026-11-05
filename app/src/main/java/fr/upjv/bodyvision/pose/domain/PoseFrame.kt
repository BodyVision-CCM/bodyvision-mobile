package fr.upjv.bodyvision.pose.domain

/**
 * Une pose détectée sur une image.
 *
 * Un point non détecté est une **clé absente** de [landmarks], jamais une valeur nulle :
 * les appelants ne peuvent donc pas déréférencer un point manquant par accident.
 * Une image sans personne donne une pose à [landmarks] vide.
 *
 * [imageSize] est la taille de l'image **redressée** dans laquelle les coordonnées de
 * [landmarks] ont été normalisées. Elle fait partie de la pose et non de son contexte : sans
 * elle, un x de 0,5 ne permet ni de projeter le point sur une vue, ni de savoir ce qu'un écart
 * en x vaut par rapport au même écart en y.
 *
 * [timestampMs] est l'horodatage de l'image, relatif au démarrage de l'appareil.
 */
data class PoseFrame(
    val landmarks: Map<PoseLandmarkType, NormalizedLandmark>,
    val imageSize: UprightImageSize,
    val timestampMs: Long,
)
