package fr.upjv.bodyvision.telemetry.domain

import fr.upjv.bodyvision.pose.domain.NormalizedLandmark

/**
 * Une image **retenue** dans une série, telle que le contrat la décrit : un instant et 33 points.
 *
 * [offsetMs] est le `t` du contrat : l'écart en millisecondes avec la **première image gardée** de
 * la série, donc 0 pour celle-là. Strictement croissant d'une image à la suivante, ce qui est une
 * exigence du contrat et non une conséquence : c'est l'accumulateur qui l'assure en n'acceptant
 * pas deux images au même horodatage.
 *
 * [landmarks] est une **liste** et non la map de `PoseFrame`, parce que le contrat dit que « l'ordre
 * du tableau fait foi » : l'ordre est ici porté par la structure, pas par une convention qu'il
 * faudrait se rappeler. Elle a exactement [LANDMARK_COUNT] éléments, dans l'ordre des indices ML
 * Kit, et aucun n'est inventé — une image à laquelle il manquait un point n'arrive jamais jusqu'ici.
 */
data class TelemetryFrame(
    val offsetMs: Long,
    val landmarks: List<NormalizedLandmark>,
)
