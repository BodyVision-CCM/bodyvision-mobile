package fr.upjv.bodyvision.camera.data

import android.hardware.camera2.CameraCaptureSession
import androidx.camera.core.ImageAnalysis
import java.util.concurrent.Executor

/**
 * Ce que le cas d'usage d'analyse demande à l'application : un analyseur d'image, l'exécuteur sur
 * lequel il tourne, et l'écoute de capture posée sur le même flux.
 *
 * Les lier dans un type rend leur **appariement** structurel, là où des paramètres nullables ne
 * feraient que l'espérer : il devient impossible de fournir un analyseur sans exécuteur.
 *
 * Ce qu'il ne garantit pas, et qui reste à la charge de l'appelant : que cet [executor] ne soit
 * pas celui du thread principal. L'analyse d'image ne doit jamais y tourner.
 *
 * Les trois appartiennent au propriétaire de l'[analyzer], jamais à la liaison caméra : la
 * liaison est refaite à chaque rotation, l'analyseur lui survit.
 *
 * [sessionCaptureCallback] est appelé par CameraX **une fois par image produite par le capteur**
 * pour la session, sur son exécuteur caméra. C'est le seul moyen de compter ces images : avec
 * `STRATEGY_KEEP_ONLY_LATEST`, celles que CameraX jette avant de les livrer sont invisibles pour
 * l'[analyzer]. Il n'écoute que — aucune option de requête n'est posée par ce chemin, donc aucun
 * réglage de la caméra n'en dépend.
 */
class FrameAnalysis(
    val analyzer: ImageAnalysis.Analyzer,
    val executor: Executor,
    val sessionCaptureCallback: CameraCaptureSession.CaptureCallback,
)
