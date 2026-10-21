package fr.upjv.bodyvision.pose.domain

/**
 * Un point du squelette, exprimé relativement à la taille de l'image analysée et non en
 * pixels : l'interface n'a plus qu'à multiplier par la taille de la vue, et c'est déjà le
 * format attendu par le contrat de télémétrie.
 *
 * **Invariant attendu : [x], [y] et [z] sont finis.** Rien ne le vérifie ici, et c'est
 * volontaire : une exception dans une boucle d'analyse à 30 images par seconde arrêterait
 * la détection pour une image abîmée. C'est au mappeur de rejeter et de journaliser une
 * image dont une dimension est nulle ; en dernier recours, [angleDegrees] rend `null`
 * plutôt qu'un angle si une valeur non finie passe quand même.
 *
 * [x] et [y] valent nominalement 0..1 mais peuvent déborder : ML Kit continue d'estimer un
 * point qui sort du cadre, et c'est un cas normal. Les borner déformerait silencieusement
 * les angles.
 *
 * [z] est une profondeur relative au bassin, dans la même échelle que [x], et peut être
 * négative.
 *
 * [visibility] est la confiance rendue par ML Kit. **Aucun code ne la lit aujourd'hui** :
 * le seuil au-dessous duquel un point est écarté sera fixé en S5, avec de vraies mesures.
 */
data class NormalizedLandmark(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float,
)
