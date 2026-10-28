package fr.upjv.bodyvision.camera.data

import android.view.OrientationEventListener
import android.view.Surface

private const val LANDSCAPE_START_DEGREES = 45
private const val REVERSE_PORTRAIT_START_DEGREES = 135
private const val REVERSE_LANDSCAPE_START_DEGREES = 225
private const val PORTRAIT_START_DEGREES = 315

/**
 * Traduit l'orientation physique de l'appareil, telle que la rend un
 * [OrientationEventListener], en une des quatre constantes `Surface.ROTATION_*` attendues par
 * `ImageAnalysis.targetRotation`.
 *
 * Rend `null` pour [OrientationEventListener.ORIENTATION_UNKNOWN] : l'appareil est à plat, son
 * orientation est indéterminée, et la seule conduite juste est de **ne pas toucher** à la
 * rotation cible. N'importe quelle valeur de repli écrirait une rotation possiblement fausse,
 * et une image tournée de 90° ne se signale par aucun message.
 *
 * [orientationDegrees] vaut `ORIENTATION_UNKNOWN` ou 0..359, c'est le contrat de
 * [OrientationEventListener].
 *
 * Le sens de la rotation est inversé, et c'est voulu : un appareil tourné d'un quart de tour
 * horaire donne `ROTATION_270`, parce que la constante décrit la rotation **de l'image** qu'il
 * faut appliquer pour la remettre droite, pas celle de l'appareil.
 *
 * Fonction pure, sans `Context` ni `ImageAnalysis` : c'est ce qui la rend testable sur la JVM,
 * alors que le reste de la chaîne de rotation demande un appareil réel.
 */
fun surfaceRotationFor(orientationDegrees: Int): Int? =
    when {
        orientationDegrees == OrientationEventListener.ORIENTATION_UNKNOWN -> null
        orientationDegrees >= PORTRAIT_START_DEGREES -> Surface.ROTATION_0
        orientationDegrees < LANDSCAPE_START_DEGREES -> Surface.ROTATION_0
        orientationDegrees < REVERSE_PORTRAIT_START_DEGREES -> Surface.ROTATION_270
        orientationDegrees < REVERSE_LANDSCAPE_START_DEGREES -> Surface.ROTATION_180
        else -> Surface.ROTATION_90
    }
