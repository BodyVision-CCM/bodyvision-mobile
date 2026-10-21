package fr.upjv.bodyvision.pose.domain

/**
 * Un point du squelette, exprimé relativement à la taille de l'image analysée et non en
 * pixels : l'interface n'a plus qu'à multiplier par la taille de la vue, et c'est déjà le
 * format attendu par le contrat de télémétrie.
 *
 * [x] et [y] valent nominalement 0..1, mais peuvent déborder : ML Kit continue d'estimer
 * un point qui sort du cadre, et c'est un cas normal. Les borner ici déformerait
 * silencieusement les angles ; [visibility] est là pour décider si le point est exploitable.
 *
 * [z] est une profondeur relative au bassin, dans la même échelle que [x], et peut être
 * négative.
 */
data class NormalizedLandmark(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float,
)
