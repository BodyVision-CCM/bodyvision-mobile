package fr.upjv.bodyvision.metrics.domain

/** Les deux seules fractions dont ce ticket a besoin, nommées pour qu'un 0,95 nu ne traîne pas. */
internal const val MEDIAN_FRACTION = 0.5
internal const val P95_FRACTION = 0.95

/**
 * L'index d'un percentile dans une collection **triée et non vide** de [size] valeurs.
 *
 * **Convention reprise à l'identique du spike SCRUM-22** (`RunStatistics.percentile`) : index
 * `(size − 1) × fraction` **tronqué**, sans interpolation. Sur un nombre pair de valeurs, la
 * médiane est donc l'élément inférieur du milieu et non la moyenne des deux.
 *
 * Ce n'est pas la convention la plus répandue, et c'est délibéré : les 26,4 ms de latence médiane
 * de SCRUM-22 ont été produits par celle-ci, et deux conventions différentes rendraient les
 * chiffres de l'application non comparables à ceux du spike. L'écart entre les deux vaut au pire un
 * intervalle d'échantillon, soit quelques dixièmes de milliseconde sur 150 valeurs.
 *
 * Un seul endroit la porte, pour les latences comme pour les débits de fenêtre : deux
 * implémentations de la même règle finiraient par diverger.
 *
 * La collection vide est traitée par les deux appelants, qui rendent 0 : il n'y a donc **pas** de
 * garde ici. Une borne de repli y serait du code mort, et pire — sur une taille nulle, elle
 * recevrait un intervalle `0..-1` et lèverait une exception sans rapport avec la cause.
 */
internal fun percentileIndexOf(
    size: Int,
    fraction: Double,
): Int = ((size - 1) * fraction).toInt()

/**
 * Le percentile d'un tableau de nanosecondes **déjà trié**, rendu en millisecondes.
 *
 * Un tableau vide rend **0 et non `NaN`** : une fenêtre sans inférence n'a pas de latence, et un
 * `NaN` se propagerait jusqu'à la ligne de journal, où il rendrait tout le relevé inutilisable.
 */
internal fun percentileMsOf(
    sortedNanos: LongArray,
    fraction: Double,
): Double {
    if (sortedNanos.isEmpty()) return 0.0
    return sortedNanos[percentileIndexOf(sortedNanos.size, fraction)] / NANOS_PER_MILLI
}

/**
 * Le percentile d'une liste de valeurs **déjà triée**, rendu tel quel. Même convention, même motif
 * qu'au-dessus, et 0 pour une liste vide.
 */
internal fun percentileOf(
    sortedValues: List<Double>,
    fraction: Double,
): Double {
    if (sortedValues.isEmpty()) return 0.0
    return sortedValues[percentileIndexOf(sortedValues.size, fraction)]
}
