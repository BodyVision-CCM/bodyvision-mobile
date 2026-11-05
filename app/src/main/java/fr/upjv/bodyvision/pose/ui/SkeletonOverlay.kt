package fr.upjv.bodyvision.pose.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import fr.upjv.bodyvision.pose.domain.PoseFrame
import kotlinx.coroutines.flow.Flow

/** Assez épais pour rester lisible sur une image de caméra, assez fin pour ne pas cacher le corps. */
private val SEGMENT_WIDTH = 4.dp
private val JOINT_RADIUS = 5.dp

/**
 * Le squelette filaire, posé par-dessus l'aperçu et de la même taille que lui.
 *
 * **La pose ne passe pas par l'état de l'écran**, et c'est la raison d'être de ce composable :
 * trente poses par seconde dans un `LiveSessionUiState` recomposeraient tout l'écran — compteurs
 * et bandeau compris — trente fois par seconde. Ici la pose vit dans un état local qui n'est lu
 * que dans la **phase de dessin**, donc une nouvelle pose n'invalide que le dessin.
 *
 * [isMirrored] est la seule chose qu'on dit du matériel : l'overlay ne connaît ni objectif ni
 * CameraX. C'est l'écran qui le déduit de l'objectif réellement lié.
 */
@Composable
fun SkeletonOverlay(
    frames: Flow<PoseFrame>,
    isMirrored: Boolean,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // `neverEqualPolicy` : la politique par défaut comparerait deux `PoseFrame`, soit une map de
    // 33 entrées, trente fois par seconde, pour un résultat toujours faux. C'est exactement ce
    // que `MlKitPoseSource` reproche à un `StateFlow`.
    val pose = remember { mutableStateOf<PoseFrame?>(null, neverEqualPolicy()) }

    // Lu en composition : la couleur du thème ne change pas d'une image à l'autre.
    val color = MaterialTheme.colorScheme.primary

    LaunchedEffect(frames, isMirrored, lifecycleOwner) {
        // `repeatOnLifecycle` et non `flowWithLifecycle` : son bloc est **rejoué** à chaque
        // passage au-dessus de STARTED, donc la remise à zéro ci-dessous a lieu aussi au retour
        // depuis l'arrière-plan. Avec `flowWithLifecycle`, seule la collecte interne repartait, et
        // la pose d'avant la mise en veille était redessinée telle quelle pendant les quelques
        // centaines de millisecondes que CameraX met à réouvrir la caméra. Rien ne tourne pendant
        // ce temps : la collecte est annulée sous STARTED.
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Deux raisons de repartir sans pose : le retour au premier plan, et la bascule
            // d'objectif, qui change [isMirrored] donc relance l'effet entier. Dans les deux cas
            // la pose en main décrit autre chose que ce que l'aperçu montre.
            pose.value = null
            frames.collect { frame -> pose.value = frame }
        }
    }

    Canvas(modifier = modifier) {
        // **La lecture est ici, dans le lambda de dessin, et pas en tête du composable.** Compose
        // suit les trois phases composition → mise en page → dessin : un état lu dans la
        // dernière n'invalide que la dernière.
        val frame = pose.value ?: return@Canvas
        // Avant la première mise en page, la vue n'a pas de taille : l'échelle serait nulle et
        // tous les points se superposeraient en un seul.
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        drawSkeleton(frame, isMirrored, color)
    }
}

/**
 * Les segments puis les points, comme dans le spike : les disques des articulations restent
 * visibles par-dessus les traits qui s'y rejoignent.
 *
 * Les points sont projetés une fois dans une map intermédiaire, et non à chaque segment : la
 * plupart des points appartiennent à deux segments ou plus, et une projection par extrémité
 * referait le même calcul deux fois par point, trente fois par seconde.
 */
private fun DrawScope.drawSkeleton(
    frame: PoseFrame,
    isMirrored: Boolean,
    color: Color,
) {
    val points =
        frame.landmarks.mapValues { (_, landmark) ->
            val point =
                projectOnView(
                    normalizedX = landmark.x,
                    normalizedY = landmark.y,
                    imageSize = frame.imageSize,
                    viewWidth = size.width,
                    viewHeight = size.height,
                    isMirrored = isMirrored,
                )
            Offset(point.x, point.y)
        }

    // Hors des boucles : `toPx()` est une conversion de densité, inutile à refaire 68 fois par
    // image.
    val segmentWidth = SEGMENT_WIDTH.toPx()
    val jointRadius = JOINT_RADIUS.toPx()

    POSE_CONNECTIONS.forEach { (start, end) ->
        // Un point non détecté est une clé absente : le segment n'est pas dessiné, et aucun `!!`
        // n'est même possible.
        val from = points[start] ?: return@forEach
        val to = points[end] ?: return@forEach
        drawLine(
            color = color,
            start = from,
            end = to,
            strokeWidth = segmentWidth,
            cap = StrokeCap.Round,
        )
    }

    points.values.forEach { point ->
        drawCircle(color = color, radius = jointRadius, center = point)
    }
}
