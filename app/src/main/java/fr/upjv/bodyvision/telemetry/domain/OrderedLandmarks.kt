package fr.upjv.bodyvision.telemetry.domain

import fr.upjv.bodyvision.pose.domain.NormalizedLandmark
import fr.upjv.bodyvision.pose.domain.PoseLandmarkType

/**
 * Les points d'une pose remis **dans l'ordre que le contrat exige**, ou `null` si cette image n'est
 * pas utilisable telle quelle.
 *
 * [order] est la correspondance indice ML Kit → type, donnée par l'appelant. Elle n'est pas écrite
 * ici, et c'est délibéré : la seule table qui dit quel indice est quel point vit dans
 * `pose/data/PoseLandmarkMapper.kt`, et le domaine ne doit pas connaître la numérotation d'une
 * bibliothèque. En la recevant, cette fonction reste pure et testable sans ML Kit.
 *
 * **Rend `null` au lieu de compléter.** Deux motifs, et aucun des deux n'est une erreur :
 * - **un point manque** — `lm` exige exactement 33 points, et une image sans personne donne une
 *   pose vide. Mettre des zéros ou répéter le point précédent inventerait un mouvement qui n'a pas
 *   eu lieu, et personne en aval ne pourrait faire la différence ;
 * - **une valeur n'est pas finie** — `NaN` et l'infini n'ont pas de syntaxe en JSON, donc une seule
 *   de ces valeurs rendrait le fichier entier illisible. `NormalizedLandmark` documente déjà
 *   « fini » comme un invariant attendu : ce test en est le dernier garde-fou, pas le premier.
 *
 * C'est à l'appelant de compter l'image écartée : lui seul sait de quelle série il s'agit.
 */
fun orderedLandmarksOf(
    landmarks: Map<PoseLandmarkType, NormalizedLandmark>,
    order: List<PoseLandmarkType>,
): List<NormalizedLandmark>? =
    order.map { type ->
        val landmark = landmarks[type] ?: return null
        if (!landmark.isFinite()) return null
        landmark
    }

private fun NormalizedLandmark.isFinite(): Boolean =
    x.isFinite() && y.isFinite() && z.isFinite() && visibility.isFinite()
