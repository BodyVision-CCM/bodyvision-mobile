package fr.upjv.bodyvision.session.ui

/**
 * Tout ce que l'écran de session affiche, et rien d'autre.
 *
 * Scalaires uniquement : les 33 landmarks ne passeront jamais par ici. Publier un nouvel état
 * 30 fois par seconde recomposerait l'écran à chaque image, pour des chiffres qu'un œil lit une
 * fois par seconde. Les compteurs arrivent en SCRUM-32.
 */
data class LiveSessionUiState(
    val permission: CameraPermissionStatus = CameraPermissionStatus.UNKNOWN,
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
)
