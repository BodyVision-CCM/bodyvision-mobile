package fr.upjv.bodyvision.telemetry.data

import fr.upjv.bodyvision.pose.data.landmarkTypesByMlKitType
import fr.upjv.bodyvision.pose.domain.PoseLandmarkType

/**
 * Les 33 types de points **dans l'ordre des indices ML Kit**, 0 à 32 — l'ordre que le contrat
 * impose au tableau `lm`, et dans lequel l'ordre du tableau fait foi.
 *
 * **Dérivé** de la table du mappeur, jamais réécrit. C'est tout l'intérêt : cette table est la
 * seule chose du dépôt qui dise quel indice ML Kit est quel point, et une seconde liste écrite à la
 * main en serait une seconde source de vérité. Son erreur serait indétectable à l'œil — deux points
 * permutés dans un fichier de 280 Ko de nombres — et ne se verrait qu'au bout de la chaîne, dans un
 * angle de genou calculé sur un poignet.
 *
 * Jamais `PoseLandmarkType.entries` ni `ordinal` non plus : le domaine dit que l'ordre de
 * déclaration de son énumération n'a **aucune** signification. Qu'il coïncide aujourd'hui avec la
 * numérotation de ML Kit est un hasard de lecture, pas un contrat, et s'y fier ferait dépendre le
 * format d'envoi d'un détail qu'on a justement choisi de ne pas fixer.
 *
 * `toSortedMap()` trie par clé, donc par indice ML Kit. Calculé une fois, au premier accès.
 */
internal val mlKitLandmarkOrder: List<PoseLandmarkType> =
    landmarkTypesByMlKitType.toSortedMap().values.toList()
