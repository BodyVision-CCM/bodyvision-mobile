package fr.upjv.bodyvision.camera.ui

import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import fr.upjv.bodyvision.camera.data.CameraLens
import fr.upjv.bodyvision.camera.data.CameraSessionBinder
import fr.upjv.bodyvision.camera.data.FrameAnalysis

/**
 * Aperçu de la caméra, pour l'objectif [lens].
 *
 * C'est aussi d'ici que vient l'affichage sur lequel la liaison aligne la rotation de l'analyse :
 * la `PreviewView` est le seul objet qui sache sur quel écran elle est posée.
 *
 * `PreviewView` est une `View` de l'ancien monde : [AndroidView] est le pont qui la pose dans
 * un arbre Compose. Le [DisposableEffect] est ce qui donne à la liaison la même durée de vie
 * que le composable — elle est faite quand il entre dans la composition et défaite quand il en
 * sort, y compris quand il en sort parce que l'écran affiche autre chose.
 *
 * [lens] fait partie des clés de cet effet : changer d'objectif défait la liaison et la refait,
 * ce qui est exactement ce que CameraX demande. Rien d'autre n'est recréé pour autant — ni le
 * détecteur ML Kit, ni son exécuteur, qui appartiennent au `ViewModel`.
 *
 * [analysis] vient du `ViewModel` et non d'un `remember` : l'analyseur et son exécuteur
 * survivent à la rotation, alors que cette liaison est refaite à chaque quart de tour.
 *
 * [onCameraUnbound] est appelé juste après que la liaison a été défaite. C'est le seul moment où
 * l'on sait qu'aucune image de la liaison précédente ne peut plus être livrée ; à qui ça sert et
 * pourquoi, c'est au `ViewModel` de le savoir — cette couche n'a pas à connaître la source de
 * poses.
 */
@Composable
fun CameraPreview(
    lens: CameraLens,
    analysis: FrameAnalysis,
    onCameraUnavailable: () -> Unit,
    onCameraRecoveringChanged: (Boolean) -> Unit,
    onCameraUnbound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView =
        remember {
            PreviewView(context).also {
                // Explicite, alors que c'est déjà le défaut de `PreviewView` : la projection de
                // l'overlay reproduit ce mode d'échelle à la main, et les deux ne doivent pas
                // pouvoir diverger au gré d'un défaut de bibliothèque.
                it.scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        }
    val binder = remember { CameraSessionBinder(context) }

    AndroidView(factory = { previewView }, modifier = modifier)

    DisposableEffect(binder, lifecycleOwner, previewView, analysis, lens) {
        binder.bind(
            lifecycleOwner = lifecycleOwner,
            surfaceProvider = previewView.surfaceProvider,
            lens = lens,
            // L'affichage de la vue, relu à chaque fois : il est nul tant que la vue n'est pas
            // attachée à une fenêtre, et c'est celui-là que `PreviewView` suit pour orienter
            // l'aperçu.
            displayProvider = { previewView.display },
            analysis = analysis,
            onUnavailable = onCameraUnavailable,
            onRecoveringChanged = onCameraRecoveringChanged,
        )
        onDispose {
            binder.unbind()
            onCameraUnbound()
        }
    }
}
