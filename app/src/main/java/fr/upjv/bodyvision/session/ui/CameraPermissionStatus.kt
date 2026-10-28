package fr.upjv.bodyvision.session.ui

/**
 * Nombre d'occurrences consécutives de la combinaison ambiguë avant de déclarer le blocage.
 *
 * Android renvoie « non accordé + aucune explication à montrer » dans deux cas opposés : le
 * dialogue fermé sans réponse d'un geste retour, et le blocage réel, où le dialogue ne s'affiche
 * plus du tout et la demande revient refusée aussitôt. Une occurrence ne départage pas les deux ;
 * deux occurrences consécutives, oui, et sans aucune heuristique de temps.
 */
private const val AMBIGUOUS_DENIALS_BEFORE_BLOCKED = 2

/**
 * État de la permission caméra, du point de vue de l'écran.
 *
 * [UNKNOWN] et [PERMANENTLY_DENIED] sont indistinguables pour Android :
 * `shouldShowRequestPermissionRationale` est faux avant la première demande comme après un
 * refus définitif. C'est le drapeau `hasBeenAskedOnce` du `ViewModel` qui les sépare.
 */
enum class CameraPermissionStatus {
    /** Jamais demandée. */
    UNKNOWN,
    GRANTED,

    /** Refusée, mais une explication peut encore être montrée avant de redemander. */
    DENIED,

    /** Refusée au point qu'Android ne réaffichera plus la demande : seuls les réglages restent. */
    PERMANENTLY_DENIED,
}

/**
 * Détermine le statut à afficher à partir de ce que le système répond.
 *
 * [hasBeenAskedOnce] est ce qui sépare les deux cas qu'Android rend indistinguables, et l'ordre
 * des branches compte : une permission accordée court-circuite tout le reste.
 *
 * [ambiguousDenialCount] vient de [nextAmbiguousDenialCount] : une seule occurrence de la
 * combinaison ambiguë se traite comme un refus simple, parce que c'est très probablement un
 * dialogue fermé sans réponse, et qu'annoncer un blocage à quelqu'un qui n'a rien refusé l'envoie
 * changer un réglage qu'il n'a jamais changé.
 *
 * Fonction pure, et c'est volontaire : cette table est la vraie logique à risque du ticket, donc
 * elle se teste sur la JVM sans `SavedStateHandle` ni appareil.
 */
fun resolveCameraPermissionStatus(
    isGranted: Boolean,
    canShowRationale: Boolean,
    hasBeenAskedOnce: Boolean,
    ambiguousDenialCount: Int,
): CameraPermissionStatus =
    when {
        isGranted -> CameraPermissionStatus.GRANTED
        !hasBeenAskedOnce -> CameraPermissionStatus.UNKNOWN
        canShowRationale -> CameraPermissionStatus.DENIED
        ambiguousDenialCount >= AMBIGUOUS_DENIALS_BEFORE_BLOCKED -> CameraPermissionStatus.PERMANENTLY_DENIED
        else -> CameraPermissionStatus.DENIED
    }

/**
 * Compteur d'occurrences consécutives de la combinaison ambiguë, après une demande de permission.
 *
 * Remis à zéro par tout ce qui lève l'ambiguïté : une permission accordée, et un refus explicite —
 * `canShowRationale` vrai veut dire qu'Android a bien montré le dialogue et que l'utilisateur a
 * répondu non.
 *
 * À n'appeler que sur le retour d'une demande, jamais à la reprise de l'écran : un retour au
 * premier plan ne refuse rien.
 */
fun nextAmbiguousDenialCount(
    current: Int,
    isGranted: Boolean,
    canShowRationale: Boolean,
): Int = if (isGranted || canShowRationale) 0 else current + 1
