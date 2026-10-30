package fr.upjv.bodyvision.camera.ui

import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import fr.upjv.bodyvision.camera.data.CameraSessionBinder
import fr.upjv.bodyvision.camera.data.FrameAnalysis

/**
 * Aperçu de la caméra arrière.
 *
 * `PreviewView` est une `View` de l'ancien monde : [AndroidView] est le pont qui la pose dans
 * un arbre Compose. Le [DisposableEffect] est ce qui donne à la liaison la même durée de vie
 * que le composable — elle est faite quand il entre dans la composition et défaite quand il en
 * sort, y compris quand il en sort parce que l'écran affiche autre chose.
 *
 * [analysis] vient du `ViewModel` et non d'un `remember` : l'analyseur et son exécuteur
 * survivent à la rotation, alors que cette liaison est refaite à chaque quart de tour.
 */
@Composable
fun CameraPreview(
    analysis: FrameAnalysis,
    onCameraUnavailable: () -> Unit,
    onCameraRecoveringChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val binder = remember { CameraSessionBinder(context) }

    AndroidView(factory = { previewView }, modifier = modifier)

    DisposableEffect(binder, lifecycleOwner, previewView, analysis) {
        binder.bind(
            lifecycleOwner = lifecycleOwner,
            surfaceProvider = previewView.surfaceProvider,
            analysis = analysis,
            onUnavailable = onCameraUnavailable,
            onRecoveringChanged = onCameraRecoveringChanged,
        )
        onDispose { binder.unbind() }
    }
}
