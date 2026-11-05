package fr.upjv.bodyvision.session.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.upjv.bodyvision.camera.data.CameraLens
import fr.upjv.bodyvision.camera.data.FrameAnalysis
import fr.upjv.bodyvision.pose.data.MlKitPoseSource
import fr.upjv.bodyvision.pose.domain.PoseFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

private const val HAS_BEEN_ASKED_ONCE_KEY = "hasBeenAskedOnce"
private const val AMBIGUOUS_DENIAL_COUNT_KEY = "ambiguousDenialCount"
private const val LENS_KEY = "lens"

/** Un battement par seconde : c'est le rythme auquel un œil lit deux nombres. */
private const val COUNTER_PERIOD_MS = 1_000L

/** Pour ramener un nombre d'images mesuré sur une fenêtre en nanosecondes à un débit par seconde. */
private const val NANOS_PER_SECOND = 1_000_000_000L

/**
 * Fenêtre de grâce avant d'arrêter le battement quand plus personne ne collecte : une rotation
 * détruit et recrée l'écran, et redémarrer le battement à chaque fois publierait un premier
 * chiffre faux.
 */
private const val SUBSCRIPTION_GRACE_MS = 5_000L

/** Les deux compteurs, lus ensemble à chaque battement. */
private data class PoseCounters(
    val fps: Int = 0,
    val landmarkCount: Int = 0,
)

/**
 * Décide de l'état de l'écran de session. Ne connaît ni `Context` ni `LifecycleOwner` : c'est
 * la fuite classique, puisqu'il survit à la destruction de l'activité.
 *
 * Construit par la fabrique par défaut de `viewModel()`, qui sait fournir un
 * [SavedStateHandle] — aucune fabrique à écrire, y compris depuis qu'il possède la source de
 * pose, parce que `PoseDetection.getClient` n'a pas besoin de `Context`.
 */
class LiveSessionViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    /**
     * Possédée ici et non dans un composable : un `remember` ne survit pas à la rotation, donc
     * chaque quart de tour recréerait le détecteur et relancerait la chauffe ML Kit.
     */
    private val poseSource = MlKitPoseSource()

    /** Passé à `CameraPreview`, qui le transmet à la liaison caméra. */
    val frameAnalysis: FrameAnalysis = poseSource.frameAnalysis

    /**
     * Les poses, pour l'overlay, et **hors de l'état d'écran** : trente publications d'état par
     * seconde recomposeraient tout l'écran pour des chiffres qu'un œil lit une fois par seconde.
     * Le flux de la source est exposé tel quel ; c'est l'overlay qui le collecte en respectant le
     * cycle de vie.
     */
    val poseFrames: SharedFlow<PoseFrame> = poseSource.frames

    /**
     * Les objectifs que l'appareil offre réellement, tels que l'écran les a demandés à CameraX.
     *
     * Champ ordinaire et non [SavedStateHandle] : c'est une propriété de l'appareil, redemandée à
     * chaque entrée dans l'écran, et l'enregistrer serait garder une réponse qui peut être
     * fausse. Vide tant qu'on ne sait pas, ce qui n'est pas la même chose que « aucun objectif ».
     *
     * Lu et écrit sur le thread principal seulement.
     */
    private var availableLenses: Set<CameraLens> = emptySet()

    /**
     * **L'état source**, et la seule chose que la logique interne lit. [uiState] en est dérivé
     * et peut être en retard d'un battement, ou valoir encore sa valeur initiale avant la
     * première collecte.
     *
     * `fps` et `detectedLandmarkCount` y valent **toujours 0** : leur valeur réelle n'existe que
     * dans [uiState], où le battement l'injecte. Les lire ici ne rendrait que des zéros.
     */
    private val mutableUiState = MutableStateFlow(LiveSessionUiState())

    /**
     * Le battement des compteurs. `onStart` jette ce qui s'est accumulé hors souscription :
     * sans lui, le premier battement après un retour au premier plan publierait d'un coup
     * toutes les images analysées pendant la fenêtre de grâce, soit un FPS de plusieurs
     * centaines.
     *
     * La première émission vient **avant** le premier `delay`, et c'est nécessaire : `combine`
     * ne publie rien tant que ses deux sources n'ont pas émis, donc un premier chiffre différé
     * d'une seconde retarderait d'autant l'affichage de l'aperçu ou du dialogue de permission.
     * Elle est à zéro, faute d'une fenêtre de mesure.
     */
    private val poseCounters: Flow<PoseCounters> =
        flow {
            emit(PoseCounters())
            var previousTickNanos = System.nanoTime()
            while (true) {
                delay(COUNTER_PERIOD_MS)
                val tickNanos = System.nanoTime()
                emit(poseCountersOver(elapsedNanos = tickNanos - previousTickNanos))
                previousTickNanos = tickNanos
            }
        }.onStart { poseSource.consumeAnalyzedFrameCount() }

    val uiState: StateFlow<LiveSessionUiState> =
        combine(mutableUiState, poseCounters) { state, counters ->
            state.copy(fps = counters.fps, detectedLandmarkCount = counters.landmarkCount)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS),
            initialValue = mutableUiState.value,
        )

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
     * L'objectif **choisi**, au même endroit que [hasBeenAskedOnce] et pour la même raison : il
     * doit survivre à la rotation, qui détruit l'activité, et à la mort du processus en
     * arrière-plan. Un `remember` ne ferait ni l'un ni l'autre.
     *
     * Enregistré par son nom et relu par comparaison de nom, sans `valueOf` : un état restauré
     * qui porterait un nom inconnu — un enum renommé entre deux versions — est un cas normal, et
     * il se traite par un repli sur l'arrière, pas par une exception au démarrage.
     *
     * Distinct de l'objectif **effectif** publié dans l'état : voir [resolveCameraLens].
     */
    private var chosenLens: CameraLens
        get() {
            val savedName: String? = savedStateHandle[LENS_KEY]
            return CameraLens.entries.firstOrNull { it.name == savedName } ?: CameraLens.BACK
        }
        set(value) {
            savedStateHandle[LENS_KEY] = value.name
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
     *
     * Lit [mutableUiState] et non [uiState] : le flux dérivé peut valoir encore sa valeur
     * initiale, donc `UNKNOWN`, alors que la permission est déjà accordée — et on relancerait
     * une demande pour rien.
     */
    fun consumePendingPermissionRequest(): Boolean {
        if (hasRequestBeenSent || mutableUiState.value.permission != CameraPermissionStatus.UNKNOWN) {
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

    /**
     * Les objectifs de l'appareil, demandés par l'écran — `hasCamera` exige CameraX, donc un
     * `Context`, que ce `ViewModel` n'a pas et ne doit pas avoir.
     *
     * Un choix enregistré qui n'existe pas sur cet appareil est corrigé **durablement** ici : le
     * prochain lancement n'a pas à refaire le détour. Tant que l'ensemble est vide, en revanche,
     * rien n'est réécrit — on ne sait pas encore, et effacer le choix de quelqu'un sur une
     * ignorance momentanée le détruirait à chaque lancement.
     */
    fun onAvailableLensesObserved(lenses: Set<CameraLens>) {
        availableLenses = lenses
        if (lenses.isEmpty()) {
            // La réponse est arrivée, et elle est vide : aucun objectif à lier. C'est le seul cas
            // où « pas d'objectif » veut bien dire « caméra indisponible », et il faut le dire —
            // sans ça, l'écran attendrait un objectif qui ne viendra jamais, noir et muet. En
            // pratique : fournisseur CameraX qui ne s'initialise pas, ou caméras désactivées par
            // politique d'appareil.
            onCameraUnavailable()
            return
        }
        // Ce qui est réécrit est l'objectif **réellement retenu**, et non la constante `BACK` :
        // sur un appareil sans caméra arrière, enregistrer `BACK` mettrait une valeur fausse dans
        // le `SavedStateHandle`, qu'il faudrait rattraper à chaque lecture. Rien n'est réécrit
        // tant que l'ensemble est vide — on ne sait pas encore, et effacer le choix de quelqu'un
        // sur une ignorance momentanée le détruirait à chaque lancement.
        val resolvedLens = resolveCameraLens(chosenLens, lenses)
        if (resolvedLens != null && resolvedLens != chosenLens) chosenLens = resolvedLens
        publishLens()
    }

    /**
     * Passe à l'autre objectif. Sans effet si l'appareil ne l'a pas — le bouton n'est alors même
     * pas proposé, et cette garde est là pour que la règle tienne sans dépendre de l'écran.
     *
     * Rien n'est oublié ici, et c'est délibéré : au moment du toucher, l'ancienne caméra est
     * encore liée et peut encore livrer une image. C'est [onCameraUnbound] qui s'en charge, une
     * fois la liaison défaite.
     */
    fun onLensToggled() {
        val currentLens = resolveCameraLens(chosenLens, availableLenses) ?: return
        val nextLens = if (currentLens == CameraLens.BACK) CameraLens.FRONT else CameraLens.BACK
        if (nextLens !in availableLenses) return
        chosenLens = nextLens
        publishLens()
    }

    /**
     * Une liaison caméra vient d'être défaite : bascule d'objectif, rotation, ou simple sortie de
     * l'écran.
     *
     * C'est le seul moment où plus aucune image de cette liaison ne peut être livrée, donc le
     * bon moment pour oublier les poses en cours : la dernière décrit un objectif ou une rotation
     * d'image qui ne sont plus à l'écran, et le `replay = 1` du flux la servirait au prochain
     * collecteur.
     */
    fun onCameraUnbound() {
        poseSource.discardPendingFrames()
    }

    // Ordre dont dépend la bascule, et il mérite d'être écrit : au changement d'objectif, cet
    // appel doit avoir lieu **avant** que l'effet de collecte de l'overlay ne redémarre, sinon le
    // nouveau collecteur serait servi par un cache de rejeu encore plein et dessinerait la pose de
    // l'ancien objectif, en miroir. Compose le garantit — il dispatche tous ses `onForgotten`
    // avant tous ses `onRemembered`, et un `LaunchedEffect` lance sa coroutine au lieu de
    // l'exécuter — mais c'est un détail de son runtime, pas un contrat. D'où la vérification sur
    // appareil, qui est le seul vrai garde-fou.

    /**
     * L'écran passe en arrière-plan, ou l'écran s'éteint.
     *
     * C'est la discontinuité la plus fréquente de toutes, et la seule qui ne défait **pas** la
     * liaison : `CameraPreview` reste dans la composition, donc aucun `onDispose` ne vient
     * oublier la dernière pose. CameraX, lui, libère la caméra par son propre observateur de
     * cycle de vie, et il lui faut quelques centaines de millisecondes pour livrer une image au
     * retour — la pose d'avant la veille serait dessinée pendant tout ce temps, et elle peut
     * avoir dix minutes.
     */
    fun onScreenStopped() {
        poseSource.discardPendingFrames()
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
        // Sur la transition seulement, pas à chaque appel : l'aperçu se figeant, la dernière pose
        // décrit une image que la caméra ne voit plus, et le `replay = 1` la servirait à l'overlay
        // dès son retour.
        if (isRecovering && !mutableUiState.value.isCameraRecovering) poseSource.discardPendingFrames()
        mutableUiState.update { it.copy(isCameraRecovering = isRecovering) }
    }

    /** Ferme la chaîne que ce `ViewModel` possède : détecteur ML Kit, puis exécuteur. */
    override fun onCleared() {
        poseSource.close()
    }

    private fun publishLens() {
        mutableUiState.update {
            it.copy(
                lens = resolveCameraLens(chosenLens, availableLenses),
                isLensSwitchAvailable = availableLenses.size > 1,
            )
        }
    }

    /**
     * Les deux chiffres d'un battement, mesurés sur [elapsedNanos] et non sur une seconde
     * supposée : `delay` garantit *au moins* son délai, jamais exactement, et le thread
     * principal saute des trames quand l'écran change. Compter 29 images sur 1 050 ms et
     * afficher 29 FPS ferait rouvrir une décision figée pour un écart qui n'est pas dans
     * l'inférence mais dans la fenêtre de mesure.
     *
     * Un battement sans aucune image analysée ne dit rien du squelette : le dernier compte connu
     * serait périmé, donc on publie 0 point plutôt qu'un chiffre qui ne correspond plus à ce que
     * la caméra voit.
     */
    private fun poseCountersOver(elapsedNanos: Long): PoseCounters {
        val analyzedFrameCount = poseSource.consumeAnalyzedFrameCount()
        return PoseCounters(
            fps = (analyzedFrameCount * NANOS_PER_SECOND.toDouble() / elapsedNanos).roundToInt(),
            landmarkCount = if (analyzedFrameCount == 0) 0 else poseSource.lastLandmarkCount,
        )
    }
}
