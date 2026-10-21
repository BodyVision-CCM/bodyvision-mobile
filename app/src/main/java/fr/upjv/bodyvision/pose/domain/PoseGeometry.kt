package fr.upjv.bodyvision.pose.domain

import kotlin.math.abs
import kotlin.math.atan2

private const val STRAIGHT_ANGLE_DEGREES = 180.0
private const val FULL_TURN_DEGREES = 360.0

/**
 * Angle en degrés au sommet [vertex], entre les segments [vertex]-[first] et
 * [vertex]-[second]. Résultat dans 0..180.
 *
 * Rend `null` dans trois cas, tous normaux et aucun exceptionnel :
 * - un des trois points n'a pas été détecté ;
 * - un des deux segments est de longueur nulle, donc sans direction : l'angle est alors
 *   indéfini, et surtout il ne vaut pas 0 degré. Rendre 0 annoncerait « complètement plié »
 *   à une règle d'alerte, sur un membre que la caméra ne voit pas ;
 * - le calcul ne donne pas un nombre fini, ce qui trahit une coordonnée non finie en
 *   entrée (voir l'invariant de [NormalizedLandmark]).
 *
 * Deux `atan2`, formule du spike SCRUM-22 inchangée. Un produit scalaire normalisé ne
 * planterait pas davantage : il rendrait `NaN` sur un segment de longueur nulle. Les deux
 * formules se valent sur ce point, et le cas dégénéré est désormais traité en amont.
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

    val firstDx = (firstPoint.x - vertexPoint.x).toDouble()
    val firstDy = (firstPoint.y - vertexPoint.y).toDouble()
    val secondDx = (secondPoint.x - vertexPoint.x).toDouble()
    val secondDy = (secondPoint.y - vertexPoint.y).toDouble()

    // Longueur nulle au sens strict. Aucun seuil de longueur minimale n'est inventé ici :
    // il sera fixé en S5, avec de vraies mesures.
    if (firstDx == 0.0 && firstDy == 0.0) return null
    if (secondDx == 0.0 && secondDy == 0.0) return null

    val degrees = abs(Math.toDegrees(atan2(firstDy, firstDx) - atan2(secondDy, secondDx)))
    if (!degrees.isFinite()) return null

    return if (degrees > STRAIGHT_ANGLE_DEGREES) FULL_TURN_DEGREES - degrees else degrees
}
