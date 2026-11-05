package fr.upjv.bodyvision.session.ui

import fr.upjv.bodyvision.camera.data.CameraLens

/**
 * Tout ce que l'écran de session affiche, et rien d'autre.
 *
 * Scalaires uniquement : les 33 landmarks ne passeront jamais par ici. Publier un nouvel état
 * 30 fois par seconde recomposerait l'écran à chaque image, pour des chiffres qu'un œil lit une
 * fois par seconde. Les poses, elles, passent par le flux de `MlKitPoseSource`.
 */
data class LiveSessionUiState(
    val permission: CameraPermissionStatus = CameraPermissionStatus.UNKNOWN,
    /**
     * L'objectif à lier, et celui dont l'aperçu est affiché. `null` tant que les objectifs de
     * l'appareil ne sont pas connus.
     *
     * Ce `null` est un état, pas un oubli d'initialisation : l'écran n'affiche alors rien et ne
     * lie aucune caméra. Partir de [CameraLens.BACK] « en attendant » ferait un battement de
     * caméra arrière à chaque lancement pour qui a choisi la frontale, et un écran « caméra
     * indisponible » sur un appareil sans caméra arrière — pour une absence d'objectif, pas pour
     * une panne. C'est [resolveCameraLens] qui le décide.
     *
     * C'est aussi lui qui décide du miroir de l'overlay, parce que c'est lui qui est lié : les
     * coordonnées des poses, elles, restent dans le repère de l'image.
     */
    val lens: CameraLens? = null,
    /**
     * Vrai seulement quand l'appareil a les deux objectifs. Faux tant qu'on ne le sait pas
     * encore, parce qu'un bouton qui apparaît puis disparaît est pire qu'un bouton qui arrive
     * une fraction de seconde après l'aperçu.
     */
    val isLensSwitchAvailable: Boolean = false,
    /**
     * Vrai quand la caméra est durablement inutilisable : la liaison a échoué, ou CameraX a
     * signalé une erreur critique. C'est bien un état et non un message ponctuel :
     * l'indisponibilité dure tant qu'on ne réessaie pas, et une notification passagère
     * laisserait un écran noir sans explication ni moyen de reprendre.
     *
     * Verrouillé jusqu'à `onRetryCamera()` : seul l'utilisateur le désarme.
     */
    val isCameraUnavailable: Boolean = false,
    /**
     * Vrai pendant une erreur *récupérable*, dont CameraX se relève seul — typiquement une
     * autre application qui tient la caméra.
     *
     * Distinct de [isCameraUnavailable] parce que la conduite à tenir est l'inverse : ici il
     * n'y a rien à faire qu'attendre, et surtout rien à démonter. L'écran garde donc l'aperçu
     * et se contente d'un bandeau par-dessus ; se désarme tout seul au retour de la caméra.
     */
    val isCameraRecovering: Boolean = false,
    /**
     * Images analysées par seconde, publié une fois par seconde par le battement du
     * `ViewModel`. C'est le débit de l'inférence, comparable aux 29,4 FPS mesurés en SCRUM-22,
     * et non ce qui survit au tampon du flux de poses.
     */
    val fps: Int = 0,
    /**
     * Nombre de points du squelette dans la dernière pose analysée : 33 quand quelqu'un est
     * dans le cadre, 0 sinon.
     *
     * Vaut 0 dès qu'un battement ne compte aucune image analysée. Sans cette remise à zéro, un
     * aperçu figé — une autre application tient la caméra — afficherait « 0 FPS, 33 points »,
     * c'est-à-dire un compte périmé présenté comme une mesure.
     */
    val detectedLandmarkCount: Int = 0,
)
