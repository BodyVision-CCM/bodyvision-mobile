package fr.upjv.bodyvision.session.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val HAS_BEEN_ASKED_ONCE_KEY = "hasBeenAskedOnce"
private const val AMBIGUOUS_DENIAL_COUNT_KEY = "ambiguousDenialCount"

/**
 * Décide de l'état de l'écran de session. Ne connaît ni `Context` ni `LifecycleOwner` : c'est
 * la fuite classique, puisqu'il survit à la destruction de l'activité.
 *
 * Construit par la fabrique par défaut de `viewModel()`, qui sait fournir un
 * [SavedStateHandle] — aucune fabrique à écrire.
 */
class LiveSessionViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(LiveSessionUiState())
    val uiState: StateFlow<LiveSessionUiState> = mutableUiState.asStateFlow()

    /**
     * Dans le [SavedStateHandle] et non dans un `remember` : il doit survivre à la rotation,
     * qui détruit l'activité, et à la mort du processus en arrière-plan. Son seul rôle est de
     * séparer [CameraPermissionStatus.UNKNOWN] de
     * [CameraPermissionStatus.PERMANENTLY_DENIED].
     */
    private var hasBeenAskedOnce: Boolean
        get() = savedStateHandle[HAS_BEEN_ASKED_ONCE_KEY] ?: false
        set(value) {
            savedStateHandle[HAS_BEEN_ASKED_ONCE_KEY] = value
        }

    /**
     * Au même endroit que [hasBeenAskedOnce], et pour la même raison : il doit survivre à la
     * rotation comme à la mort du processus. Sans ça, une application tuée en arrière-plan
     * entre deux demandes reviendrait avec un compteur à zéro, et le blocage réel ne serait
     * jamais atteint.
     *
     * Rien à voir avec [hasRequestBeenSent], dont la portée est l'écran : celui-ci compte des
     * réponses déjà reçues, pas une demande en vol.
     */
    private var ambiguousDenialCount: Int
        get() = savedStateHandle[AMBIGUOUS_DENIAL_COUNT_KEY] ?: 0
        set(value) {
            savedStateHandle[AMBIGUOUS_DENIAL_COUNT_KEY] = value
        }

    /**
     * Vrai dès qu'une demande a été envoyée depuis cet écran.
     *
     * Champ ordinaire et non [SavedStateHandle], parce que sa portée n'est pas celle de
     * [hasBeenAskedOnce] : il doit survivre à la rotation, qui recrée l'activité pendant que le
     * dialogue système est affiché, mais surtout pas à la mort du processus, où plus aucune
     * demande n'est en vol. Deux rôles, deux durées de vie.
     */
    private var hasRequestBeenSent = false

    /**
     * Rend vrai une seule fois, et seulement si la permission n'a jamais été demandée.
     *
     * C'est ici, et non dans la clé de l'effet appelant, que tient la garantie « une seule
     * demande » : un `LaunchedEffect(Unit)` rejoue à chaque recréation de l'activité, donc à
     * chaque rotation. Une seconde demande lancée pendant que le dialogue système est encore
     * affiché revient annulée, donc refusée, avec `shouldShowRequestPermissionRationale` faux —
     * et l'écran annoncerait un refus définitif à quelqu'un qui n'a rien refusé.
     */
    fun consumePendingPermissionRequest(): Boolean {
        if (hasRequestBeenSent || uiState.value.permission != CameraPermissionStatus.UNKNOWN) {
            return false
        }
        hasRequestBeenSent = true
        return true
    }

    /**
     * À l'entrée dans l'écran et à chaque retour au premier plan — donc aussi au retour depuis
     * les réglages système, sans redémarrer l'application. Idempotente : elle recalcule le
     * statut à partir de ce que le système dit maintenant.
     *
     * Les deux booléens sont calculés par l'écran, parce que
     * `shouldShowRequestPermissionRationale` exige une `Activity`.
     */
    fun onPermissionStateObserved(
        isGranted: Boolean,
        canShowRationale: Boolean,
    ) {
        val status =
            resolveCameraPermissionStatus(
                isGranted,
                canShowRationale,
                hasBeenAskedOnce,
                ambiguousDenialCount,
            )
        mutableUiState.update { it.copy(permission = status) }
    }

    /**
     * Retour d'une demande, et le seul endroit où le compteur avance : c'est la réponse à une
     * demande qui est ambiguë, pas un retour au premier plan.
     */
    fun onPermissionRequestResult(
        isGranted: Boolean,
        canShowRationale: Boolean,
    ) {
        hasBeenAskedOnce = true
        ambiguousDenialCount = nextAmbiguousDenialCount(ambiguousDenialCount, isGranted, canShowRationale)
        onPermissionStateObserved(isGranted, canShowRationale)
    }

    fun onCameraUnavailable() {
        // Le bandeau d'attente n'a plus de sens : on ne montre plus l'aperçu derrière.
        mutableUiState.update { it.copy(isCameraUnavailable = true, isCameraRecovering = false) }
    }

    fun onRetryCamera() {
        mutableUiState.update { it.copy(isCameraUnavailable = false, isCameraRecovering = false) }
    }

    /**
     * Une erreur récupérable est en cours, ou vient de se terminer. Appelée par la liaison
     * caméra à chaque changement d'état, donc potentiellement plusieurs fois de suite avec la
     * même valeur : `MutableStateFlow` ne republie pas un état égal, donc rien ne recompose
     * pour rien.
     */
    fun onCameraRecoveringChanged(isRecovering: Boolean) {
        mutableUiState.update { it.copy(isCameraRecovering = isRecovering) }
    }
}
