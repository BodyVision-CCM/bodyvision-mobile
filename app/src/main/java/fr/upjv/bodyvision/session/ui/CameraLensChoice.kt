package fr.upjv.bodyvision.session.ui

import fr.upjv.bodyvision.camera.data.CameraLens

/**
 * L'objectif à lier, déduit du choix enregistré et de ce que l'appareil offre réellement.
 *
 * Rend `null` tant que [availableLenses] est vide, c'est-à-dire **tant qu'on ne sait pas**. C'est
 * un état à part entière et non un défaut d'initialisation : lier l'arrière « en attendant »
 * ferait un battement de caméra arrière à chaque lancement pour qui a choisi la frontale, et un
 * écran « caméra indisponible » sur un appareil sans caméra arrière — pour une absence
 * d'objectif, pas pour une panne.
 *
 * L'ordre des branches compte :
 * - le choix enregistré, s'il existe sur cet appareil ;
 * - sinon l'arrière, qui est la caméra de tous les appareils visés ;
 * - sinon le seul objectif qui reste, parce qu'un appareil sans caméra arrière en garde une
 *   autre, et qu'insister sur un objectif absent n'afficherait rien du tout.
 *
 * Fonction pure, et c'est volontaire : c'est la vraie logique à risque de la bascule, donc elle
 * se teste sur la JVM, sans `SavedStateHandle`, sans CameraX et sans appareil.
 */
fun resolveCameraLens(
    chosenLens: CameraLens,
    availableLenses: Set<CameraLens>,
): CameraLens? =
    when {
        availableLenses.isEmpty() -> null
        chosenLens in availableLenses -> chosenLens
        CameraLens.BACK in availableLenses -> CameraLens.BACK
        else -> CameraLens.FRONT
    }
