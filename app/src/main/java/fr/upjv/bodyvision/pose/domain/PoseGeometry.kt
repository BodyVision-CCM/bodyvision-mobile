package fr.upjv.bodyvision.pose.domain

import kotlin.math.abs
import kotlin.math.atan2

private const val STRAIGHT_ANGLE_DEGREES = 180.0
private const val FULL_TURN_DEGREES = 360.0

/**
 * Angle en degrés au sommet [vertex], entre les segments [vertex]-[first] et
 * [vertex]-[second]. Résultat dans 0..180. Rend `null` si l'un des trois points n'a pas
 * été détecté : un landmark absent est un cas normal, pas une erreur.
 *
 * Mesuré par deux `atan2` plutôt que par un produit scalaire : `atan2` est défini même
 * quand deux points sont confondus, là où le produit scalaire diviserait par une norme
 * nulle. C'est la formule du spike SCRUM-22, inchangée.
 */
fun angleDegrees(
    frame: PoseFrame,
    first: PoseLandmarkType,
    vertex: PoseLandmarkType,
    second: PoseLandmarkType,
): Double? {
    val firstPoint = frame.landmarks[first] ?: return null
    val vertexPoint = frame.landmarks[vertex] ?: return null
    val secondPoint = frame.landmarks[second] ?: return null

    val angleToFirst =
        atan2(
            (firstPoint.y - vertexPoint.y).toDouble(),
            (firstPoint.x - vertexPoint.x).toDouble(),
        )
    val angleToSecond =
        atan2(
            (secondPoint.y - vertexPoint.y).toDouble(),
            (secondPoint.x - vertexPoint.x).toDouble(),
        )

    val degrees = abs(Math.toDegrees(angleToFirst - angleToSecond))
    return if (degrees > STRAIGHT_ANGLE_DEGREES) FULL_TURN_DEGREES - degrees else degrees
}
