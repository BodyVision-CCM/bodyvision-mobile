package fr.upjv.bodyvision.camera.data

import androidx.camera.core.ImageAnalysis
import java.util.concurrent.Executor

/**
 * Un analyseur d'image et l'exécuteur sur lequel il tourne.
 *
 * Les lier dans un type rend leur **appariement** structurel, là où deux paramètres nullables
 * ne feraient que l'espérer : il devient impossible de fournir un analyseur sans exécuteur.
 *
 * Ce qu'il ne garantit pas, et qui reste à la charge de l'appelant : que cet [executor] ne soit
 * pas celui du thread principal. L'analyse d'image ne doit jamais y tourner.
 *
 * L'[executor] appartient au propriétaire de l'[analyzer], jamais à la liaison caméra : la
 * liaison est refaite à chaque rotation, l'analyseur lui survit.
 */
class FrameAnalysis(
    val analyzer: ImageAnalysis.Analyzer,
    val executor: Executor,
)
