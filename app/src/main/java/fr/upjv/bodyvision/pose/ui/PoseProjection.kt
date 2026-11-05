package fr.upjv.bodyvision.pose.ui

import fr.upjv.bodyvision.pose.domain.UprightImageSize
import kotlin.math.max

/**
 * Projette un point normalisé 0..1 de l'image redressée vers les pixels de la vue, en
 * reproduisant le `FILL_CENTER` de `PreviewView`.
 *
 * **L'échelle est le `max` des deux rapports**, et c'est tout le sujet : `FILL_CENTER` remplit la
 * vue et laisse déborder l'axe en trop hors du cadre, là où un `min` laisserait deux bandes
 * vides. Le débord est centré, d'où un décalage négatif sur l'axe rogné. Quand l'image et la vue
 * ont le même rapport d'aspect, les deux rapports sont égaux et il n'y a aucun recadrage.
 *
 * **Le miroir est un retournement d'affichage, jamais de données** : [isMirrored] remplace x par
 * `1 - x` **avant** la mise à l'échelle, et nulle part ailleurs. Les coordonnées de la pose
 * restent dans le repère de l'image, ce que le contrat de télémétrie exige.
 *
 * **Aucun bornage** : un point hors cadre se projette hors de la vue, et c'est voulu. ML Kit
 * continue d'estimer un point sorti du cadre ; le ramener sur le bord poserait un poignet au
 * milieu de l'écran.
 *
 * Suppose que l'aperçu et l'analyse couvrent la même zone du capteur, ce qui tient parce que les
 * deux cas d'usage sont en 4:3, et qu'aucun `ViewPort` n'est posé — avec un `ViewPort`, l'aperçu
 * n'afficherait qu'un recadrage de l'image et il faudrait le projeter lui aussi.
 */
fun projectOnView(
    normalizedX: Float,
    normalizedY: Float,
    imageSize: UprightImageSize,
    viewWidth: Float,
    viewHeight: Float,
    isMirrored: Boolean,
): ViewPoint {
    val imageWidth = imageSize.width.toFloat()
    val imageHeight = imageSize.height.toFloat()

    val scale = max(viewWidth / imageWidth, viewHeight / imageHeight)
    val horizontalOffset = (viewWidth - imageWidth * scale) / 2f
    val verticalOffset = (viewHeight - imageHeight * scale) / 2f

    val displayedX = if (isMirrored) 1f - normalizedX else normalizedX

    return ViewPoint(
        x = displayedX * imageWidth * scale + horizontalOffset,
        y = normalizedY * imageHeight * scale + verticalOffset,
    )
}
