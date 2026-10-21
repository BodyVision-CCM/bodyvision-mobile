package fr.upjv.bodyvision.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Un seul jeu de couleurs, sombre : tous les écrans sont posés sur une image de caméra,
 * un thème clair n'y serait pas lisible. Pas de couleur dynamique non plus, pour que la
 * démonstration ait les mêmes teintes sur n'importe quel appareil.
 */
private val BodyVisionColorScheme =
    darkColorScheme(
        primary = BodyVisionAccent,
        background = BodyVisionBackground,
        surface = BodyVisionBackground,
        onBackground = BodyVisionOnBackground,
        onSurface = BodyVisionOnBackground,
    )

@Composable
fun BodyVisionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BodyVisionColorScheme,
        content = content,
    )
}
