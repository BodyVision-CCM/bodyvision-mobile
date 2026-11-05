package fr.upjv.bodyvision.pose.domain

/**
 * Taille en pixels de l'image **redressée**, c'est-à-dire celle dont les axes sont déjà remis
 * droits : c'est le repère dans lequel les coordonnées de [NormalizedLandmark] sont exprimées.
 *
 * Elle appartient au domaine, et elle voyage avec [PoseFrame], parce que des coordonnées
 * relatives ne veulent rien dire sans la taille qui a servi à les diviser. L'affichage s'en sert
 * pour projeter sur une vue recadrée, et [angleDegrees] pour retrouver un repère isotrope.
 *
 * **Invariant attendu : [width] et [height] sont strictement positifs.** Rien ne le vérifie ici,
 * c'est la couche données qui rejette une image aux dimensions absurdes, à la frontière — un
 * `require` dans une boucle à 30 images par seconde arrêterait la détection pour une seule image
 * abîmée.
 */
data class UprightImageSize(
    val width: Int,
    val height: Int,
)
