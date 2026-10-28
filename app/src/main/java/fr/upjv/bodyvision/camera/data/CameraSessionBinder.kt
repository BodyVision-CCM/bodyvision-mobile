package fr.upjv.bodyvision.camera.data

import android.content.Context
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

private const val TAG = "CameraSessionBinder"

/** Résolution d'analyse mesurée et figée en SCRUM-22 : 29,4 FPS, latence médiane 26,4 ms. */
private val ANALYSIS_RESOLUTION = Size(640, 480)

/**
 * Lie et délie les cas d'usage CameraX. Reprend la configuration mesurée du spike SCRUM-22.
 *
 * Ne garde aucune référence au [LifecycleOwner] : c'est lui qui est passé à chaque [bind], et
 * CameraX libère la caméra tout seul quand il part en arrière-plan.
 */
class CameraSessionBinder(
    private val context: Context,
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var orientationListener: OrientationEventListener? = null

    /**
     * Identifie la demande de liaison courante. Le fournisseur de caméra arrive de façon
     * asynchrone, donc l'écouteur doit pouvoir dire si c'est encore *sa* demande qui est en
     * cours : un booléen « une demande est en cours » ne le dirait pas, et l'écouteur d'une
     * demande annulée relierait un écran déjà disparu — ou, pire, écraserait une liaison plus
     * récente avec l'ancien `surfaceProvider`.
     *
     * Lu et écrit uniquement sur le thread principal — [bind], [unbind] et l'écouteur y tournent
     * tous — d'où l'absence de `@Volatile`.
     */
    private var currentBindToken = 0

    /**
     * Lie l'aperçu, et l'analyse d'image seulement si [analysis] est fourni — lier un flux
     * d'analyse que personne ne consomme ne coûterait que de la batterie.
     *
     * [onUnavailable] est appelé sur le thread principal dans les deux cas où la caméra est
     * durablement inutilisable : l'échec de la liaison elle-même, et une erreur **critique**
     * signalée ensuite par l'état de la caméra. Le second n'est pas un détail : après une
     * liaison réussie, c'est CameraX qui réouvre la caméra à chaque retour au premier plan,
     * dans son propre observateur de cycle de vie — donc hors de toute exception que ce code
     * pourrait rattraper.
     *
     * Une erreur *récupérable* passe par [onRecoveringChanged] et jamais par [onUnavailable],
     * CameraX s'en relevant seul : voir [watchCameraState].
     *
     * Le détail de la panne va dans le logcat, que l'écran ne peut pas montrer ; l'écran n'a
     * besoin de savoir que « indisponible », d'où un rappel sans paramètre.
     */
    fun bind(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        analysis: FrameAnalysis? = null,
        onUnavailable: () -> Unit,
        onRecoveringChanged: (Boolean) -> Unit,
    ) {
        val bindToken = ++currentBindToken
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (bindToken != currentBindToken) return@addListener
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                bindUseCases(
                    provider,
                    lifecycleOwner,
                    surfaceProvider,
                    analysis,
                    onUnavailable,
                    onRecoveringChanged,
                )
            } catch (e: Exception) {
                // Rattrapée parce qu'on sait quoi en faire : l'écran propose de réessayer.
                val requested = if (analysis == null) "aperçu seul" else "aperçu et analyse"
                Log.e(TAG, "Liaison CameraX impossible (caméra arrière, $requested)", e)
                onUnavailable()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun unbind() {
        // Invalide la demande en vol, s'il y en a une.
        currentBindToken++
        orientationListener?.disable()
        orientationListener = null
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        cameraProvider?.unbindAll()
        cameraProvider = null
    }

    private fun bindUseCases(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        analysis: FrameAnalysis?,
        onUnavailable: () -> Unit,
        onRecoveringChanged: (Boolean) -> Unit,
    ) {
        provider.unbindAll()

        val preview = Preview.Builder().build().also { it.surfaceProvider = surfaceProvider }
        val newAnalysis = analysis?.let(::newImageAnalysis)
        imageAnalysis = newAnalysis

        val useCases = listOfNotNull<UseCase>(preview, newAnalysis)
        val camera =
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                *useCases.toTypedArray(),
            )

        watchCameraState(camera, lifecycleOwner, onUnavailable, onRecoveringChanged)
        newAnalysis?.let(::trackDeviceRotation)
    }

    /**
     * Seule source qui réponde à « la caméra est-elle réellement ouverte ». Observée avec le
     * [lifecycleOwner] et non manuellement : LiveData se désabonne alors tout seul quand
     * l'écran est détruit, donc rien à retirer dans [unbind].
     *
     * **Deux conduites opposées, et c'est CameraX qui tranche.** Il classe lui-même ses codes
     * en `RECOVERABLE` (caméra occupée, trop de caméras ouvertes, autre panne temporaire) et
     * `CRITICAL` (configuration de flux refusée, caméra désactivée par politique d'appareil,
     * panne fatale, mode Ne pas déranger). On s'appuie sur ce classement plutôt que sur une
     * liste de codes recopiée ici, qui se périmerait au prochain code ajouté à la
     * bibliothèque.
     *
     * - `CRITICAL` → [onUnavailable]. La caméra ne reviendra pas d'elle-même.
     * - `RECOVERABLE` → [onRecoveringChanged] à `true`. CameraX reste en `PENDING_OPEN` et
     *   réouvre seul dès que la caméra se libère. Surtout **ne pas** passer par
     *   [onUnavailable] ici : l'écran basculerait sur son panneau, retirerait `CameraPreview`
     *   de la composition, donc appellerait `unbind()` — on démonterait la caméra en pleine
     *   reprise, pour un défaut intermittent donc irreproductible sur commande.
     * - Plus d'erreur, et caméra ouverte ou en attente d'ouverture → [onRecoveringChanged] à
     *   `false`, ce qui fait disparaître le bandeau tout seul.
     *
     * Les deux cas d'erreur sont journalisés, y compris le récupérable qu'on n'affiche qu'en
     * bandeau : un message qui apparaît sans raison visible sur l'appareil doit laisser
     * quelque chose à lire.
     */
    private fun watchCameraState(
        camera: Camera,
        lifecycleOwner: LifecycleOwner,
        onUnavailable: () -> Unit,
        onRecoveringChanged: (Boolean) -> Unit,
    ) {
        camera.cameraInfo.cameraState.observe(lifecycleOwner) { state ->
            val error = state.error
            if (error == null) {
                val isBackOnTrack =
                    state.type == CameraState.Type.OPEN || state.type == CameraState.Type.PENDING_OPEN
                if (isBackOnTrack) onRecoveringChanged(false)
                return@observe
            }

            val details = "code ${error.code}, type ${error.type}, phase ${state.type}"
            if (error.type == CameraState.ErrorType.CRITICAL) {
                Log.e(TAG, "Caméra fermée sur erreur critique ($details)", error.cause)
                onUnavailable()
            } else {
                Log.w(TAG, "Caméra en erreur récupérable, CameraX réouvrira ($details)", error.cause)
                onRecoveringChanged(true)
            }
        }
    }

    private fun newImageAnalysis(analysis: FrameAnalysis): ImageAnalysis {
        val resolutionSelector =
            ResolutionSelector
                .Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        ANALYSIS_RESOLUTION,
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ),
                ).build()

        return ImageAnalysis
            .Builder()
            .setResolutionSelector(resolutionSelector)
            // Décision figée : toute autre valeur fait s'accumuler les frames et fausse la latence.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysis.executor, analysis.analyzer) }
    }

    /**
     * `ImageAnalysis` prend la rotation de l'affichage au moment de la liaison, et une rotation
     * portrait↔paysage recrée l'activité, donc relie tout à neuf. Sauf pour le demi-tour :
     * paysage vers paysage inversé ne change pas la configuration, donc rien n'est relié, et
     * `rotationDegrees` resterait périmé de 180° — les landmarks arriveraient à l'envers sans
     * aucun message d'erreur.
     */
    private fun trackDeviceRotation(analysis: ImageAnalysis) {
        // Celui d'une liaison précédente écrirait sur un ImageAnalysis démonté, et continuerait
        // de consommer le capteur.
        orientationListener?.disable()
        orientationListener =
            object : OrientationEventListener(context) {
                override fun onOrientationChanged(orientation: Int) {
                    val rotation = surfaceRotationFor(orientation) ?: return
                    analysis.targetRotation = rotation
                }
            }.also { it.enable() }
    }
}
