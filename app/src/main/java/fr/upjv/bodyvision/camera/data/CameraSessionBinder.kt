package fr.upjv.bodyvision.camera.data

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.util.Size
import android.view.Display
import androidx.camera.core.Camera
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val TAG = "CameraSessionBinder"

/** Résolution d'analyse mesurée et figée en SCRUM-22 : 29,4 FPS, latence médiane 26,4 ms. */
private val ANALYSIS_RESOLUTION = Size(640, 480)

/**
 * De quoi distinguer deux rapports d'aspect sans se faire piéger par l'arrondi : 4:3 vaut 1,333
 * et 16:9 vaut 1,778, donc tout écart réel est cent fois plus grand que ce seuil.
 */
private const val ASPECT_RATIO_TOLERANCE = 0.01f

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
    private var displayListener: DisplayManager.DisplayListener? = null

    /**
     * L'état de la caméra **de la liaison courante**, et son observateur.
     *
     * Gardés tous les deux parce qu'il faut l'un pour retirer l'autre. Le cycle de vie ne suffit
     * pas : voir [watchCameraState].
     */
    private var cameraState: LiveData<CameraState>? = null
    private var cameraStateObserver: Observer<CameraState>? = null

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
     *
     * [lens] est l'objectif à lier. C'est un paramètre et non une constante parce que la bascule
     * arrière/frontale refait la liaison : l'appelant délie, puis relie avec l'autre objectif.
     *
     * [displayProvider] rend l'affichage **de la vue d'aperçu**, et non un affichage par défaut :
     * c'est celui que `PreviewView` suit, donc le seul qui aligne l'analyse sur ce qui est affiché.
     * Une fonction et non une valeur, parce que la réponse change : elle est nulle tant que la vue
     * n'est pas attachée à une fenêtre, et elle change si la fenêtre passe sur un autre écran.
     */
    fun bind(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        lens: CameraLens,
        displayProvider: () -> Display?,
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
                    lens,
                    displayProvider,
                    analysis,
                    onUnavailable,
                    onRecoveringChanged,
                )
            } catch (e: Exception) {
                // Rattrapée parce qu'on sait quoi en faire : l'écran propose de réessayer.
                val requested = if (analysis == null) "aperçu seul" else "aperçu et analyse"
                Log.e(TAG, "Liaison CameraX impossible (objectif $lens, $requested)", e)
                onUnavailable()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun unbind() {
        // Invalide la demande en vol, s'il y en a une.
        currentBindToken++
        unregisterDisplayListener()
        unobserveCameraState()
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        cameraProvider?.unbindAll()
        cameraProvider = null
    }

    private fun bindUseCases(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        lens: CameraLens,
        displayProvider: () -> Display?,
        analysis: FrameAnalysis?,
        onUnavailable: () -> Unit,
        onRecoveringChanged: (Boolean) -> Unit,
    ) {
        provider.unbindAll()

        val preview = newPreview(surfaceProvider)
        val newAnalysis = analysis?.let(::newImageAnalysis)
        imageAnalysis = newAnalysis

        val useCases = listOfNotNull<UseCase>(preview, newAnalysis)
        val camera =
            provider.bindToLifecycle(
                lifecycleOwner,
                lens.toCameraSelector(),
                *useCases.toTypedArray(),
            )

        watchCameraState(camera, lifecycleOwner, onUnavailable, onRecoveringChanged)
        newAnalysis?.let { trackDisplayRotation(it, displayProvider) }
        logBoundUseCases(lens, preview, newAnalysis)
    }

    /**
     * L'aperçu, avec son format **écrit explicitement**.
     *
     * Les deux stratégies reproduisent à l'identique `Preview.Defaults` de CameraX 1.4.2 — 4:3
     * avec repli automatique, et la plus haute résolution disponible — donc le comportement ne
     * change pas, il devient seulement lisible. Ne poser que l'`AspectRatioStrategy` ne serait
     * pas équivalent : le sélecteur est une **option unique** de la configuration, celui de
     * l'application remplace celui du défaut, et `HIGHEST_AVAILABLE_STRATEGY` disparaîtrait en
     * silence.
     *
     * Pourquoi l'écrire du tout : la projection de l'overlay n'est juste que si l'aperçu et
     * l'analyse couvrent la même zone du capteur, donc partagent le même rapport d'aspect. C'est
     * une hypothèse de l'affichage, elle doit se lire ici et pas seulement dans les sources de
     * CameraX.
     */
    private fun newPreview(surfaceProvider: Preview.SurfaceProvider): Preview {
        val resolutionSelector =
            ResolutionSelector
                .Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .build()

        return Preview
            .Builder()
            .setResolutionSelector(resolutionSelector)
            .build()
            .also { it.surfaceProvider = surfaceProvider }
    }

    /**
     * Une ligne par liaison — à l'ouverture, à chaque rotation, à chaque bascule — et jamais par
     * image.
     *
     * Elle existe pour une raison précise : `RATIO_4_3_FALLBACK_AUTO_STRATEGY` autorise un autre
     * rapport d'aspect quand le 4:3 n'est pas disponible, et deux rapports différents veulent dire
     * deux zones du capteur différentes. Ce cas passe donc en avertissement et s'annonce lui-même,
     * au lieu de laisser deux résolutions à comparer de tête.
     *
     * Attention à ce qu'elle prouve, et à ce qu'elle ne prouve pas : un rapport identique est une
     * condition **nécessaire** pour que les deux cas d'usage couvrent la même zone, pas une
     * condition suffisante. La ligne rapporte donc ce qu'elle a lu, et laisse le diagnostic à qui
     * la lit.
     *
     * `resolutionInfo` est nullable — la liaison vient tout juste d'être faite — et il est lu
     * sans `!!` : une résolution manquante se journalise telle quelle, sans comparaison.
     */
    private fun logBoundUseCases(
        lens: CameraLens,
        preview: Preview,
        analysis: ImageAnalysis?,
    ) {
        val previewResolution = preview.resolutionInfo?.resolution
        val analysisResolution = analysis?.resolutionInfo?.resolution
        val details = "objectif $lens, aperçu $previewResolution, analyse $analysisResolution"

        val previewRatio = previewResolution?.sideRatio()
        val analysisRatio = analysisResolution?.sideRatio()
        val isAspectRatioMismatch =
            previewRatio != null &&
                analysisRatio != null &&
                abs(previewRatio - analysisRatio) > ASPECT_RATIO_TOLERANCE

        if (isAspectRatioMismatch) {
            Log.w(
                TAG,
                "Rapports d'aspect différents entre l'aperçu et l'analyse ($details) : " +
                    "premier suspect si le squelette est décalé",
            )
        } else {
            Log.i(TAG, "Liaison faite ($details)")
        }
    }

    /**
     * Seule source qui réponde à « la caméra est-elle réellement ouverte ».
     *
     * Observée avec le [lifecycleOwner], qui couvre la destruction de l'écran, **et retirée
     * explicitement dans [unbind]**. Les deux sont nécessaires, et c'est la bascule d'objectif
     * qui l'a rendu vrai : jusqu'ici, relier sans détruire l'activité était rare. Chaque
     * bascule laisserait sinon un observateur définitif sur l'état de l'objectif qu'on vient de
     * quitter — et cet état continue de vivre. L'objectif arrière libre repasse en
     * `PENDING_OPEN`, sans erreur ; l'observateur périmé y lirait « tout va bien » et ferait
     * disparaître le bandeau de reprise de la **frontale**, qui serait toujours indisponible.
     * Un observateur d'un objectif ne doit pas pouvoir parler de l'état d'un autre.
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
        // Celui d'une liaison précédente parlerait de l'état d'un objectif qu'on ne regarde plus.
        unobserveCameraState()

        val observer =
            Observer<CameraState> { state ->
                val error = state.error
                if (error == null) {
                    val isBackOnTrack =
                        state.type == CameraState.Type.OPEN || state.type == CameraState.Type.PENDING_OPEN
                    if (isBackOnTrack) onRecoveringChanged(false)
                    return@Observer
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

        cameraState = camera.cameraInfo.cameraState
        cameraStateObserver = observer
        camera.cameraInfo.cameraState.observe(lifecycleOwner, observer)
    }

    /** Idempotente : sans observateur enregistré, elle ne fait rien. */
    private fun unobserveCameraState() {
        val observer = cameraStateObserver ?: return
        cameraState?.removeObserver(observer)
        cameraStateObserver = null
        cameraState = null
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
     * Aligne la rotation de l'analyse sur celle de **l'affichage**, et pas sur l'orientation
     * physique de l'appareil.
     *
     * C'est le point le plus délicat de tout l'overlay, et il ne se voit pas à la lecture d'un
     * seul fichier. `PreviewView` aligne l'aperçu sur la rotation d'affichage dès que l'aperçu
     * n'a pas de rotation cible explicite **ou** que son implémentation est une `SurfaceView` —
     * ce qui est le cas en mode `PERFORMANCE`, le défaut. L'aperçu suit donc l'affichage, sans
     * recours. Or la projection de l'overlay suppose que l'image analysée est orientée comme
     * l'aperçu : il faut donc que l'analyse suive la même référence.
     *
     * Avec l'orientation physique — un `OrientationEventListener`, ce que faisait SCRUM-31 — les
     * deux divergent dès que la **rotation automatique est désactivée** : l'affichage reste en
     * portrait, l'accéléromètre signale un quart de tour, `rotationDegrees` change, et le
     * squelette se retrouve tourné de 90° par rapport au corps visible. Pendant tout ce temps,
     * sans aucun message.
     *
     * Le demi-tour que SCRUM-31 voulait couvrir le reste : paysage vers paysage inversé ne change
     * pas la configuration, donc rien n'est relié, mais `ROTATION_90 → ROTATION_270` est bien un
     * changement de rotation d'affichage, et `onDisplayChanged` le signale.
     *
     * **L'affichage suivi est celui de la vue**, pas l'affichage par défaut, et les événements sont
     * filtrés sur son identifiant — exactement ce que fait le `DisplayRotationListener` de
     * `PreviewView`. Prendre `DEFAULT_DISPLAY` suffirait sur un téléphone, et divergerait dès que
     * la fenêtre vit sur un écran externe, en mode bureau : l'aperçu suivrait un écran et l'analyse
     * un autre.
     *
     * **Limite connue** : `preview.targetRotation` n'est pas posé. C'est sans effet sur le chemin
     * `SurfaceView` — celui de l'appareil cible — où `PreviewView` ignore de toute façon la
     * rotation cible de l'aperçu et suit l'affichage. Sur un appareil que les quirks de CameraX
     * forcent en `TextureView`, les pliables, l'aperçu resterait figé sur la rotation lue à la
     * construction pendant que l'analyse suit l'affichage. Noté dans `docs/plan-s4.md`.
     *
     * La rotation est posée une fois tout de suite : l'affichage peut avoir tourné avant cette
     * liaison, et aucun événement ne viendrait le rappeler.
     *
     * **Ce choix va sciemment contre un conseil écrit de CameraX**, et il faut le savoir avant de
     * le « corriger » : la javadoc d'`ImageAnalysis.setTargetRotation` recommande un
     * `OrientationEventListener`, justement parce que l'orientation d'affichage peut être
     * verrouillée. Le conseil vaut pour qui ne fait que de l'analyse ; ici un overlay se superpose
     * à l'aperçu, et deux repères différents donneraient un squelette faux dans tous les cas, y
     * compris les plus courants. La contrepartie est assumée et écrite dans `docs/plan-s4.md` :
     * rotation automatique désactivée et appareil tourné, ML Kit reçoit un sujet couché et ne
     * détecte plus — une détection qui tombe se voit, un squelette décalé se présente comme une
     * mesure.
     */
    private fun trackDisplayRotation(
        analysis: ImageAnalysis,
        displayProvider: () -> Display?,
    ) {
        // Celui d'une liaison précédente écrirait sur un `ImageAnalysis` démonté.
        unregisterDisplayListener()

        val displayManager = ContextCompat.getSystemService(context, DisplayManager::class.java)
        if (displayManager == null) {
            // Rien à rattraper et rien à inventer : sans gestionnaire d'affichage, écrire une
            // rotation de repli orienterait l'image au hasard. On garde celle que CameraX a prise
            // à la liaison.
            Log.w(TAG, "Rotation d'affichage non suivie : gestionnaire d'affichage introuvable")
            return
        }

        // Posée tout de suite si la vue est déjà attachée : l'affichage peut avoir tourné avant
        // cette liaison, et aucun événement ne viendrait le rappeler. Si elle ne l'est pas encore,
        // le défaut de CameraX tient — la rotation de l'affichage par défaut au moment de la
        // création du cas d'usage — et le premier événement d'affichage corrigera le cas, rare, où
        // la fenêtre vit sur un autre écran.
        displayProvider()?.let { analysis.targetRotation = it.rotation }

        displayListener =
            object : DisplayManager.DisplayListener {
                override fun onDisplayChanged(displayId: Int) {
                    // L'affichage est relu à chaque événement, et c'est voulu : la vue peut
                    // n'avoir été attachée qu'après la liaison, ou avoir changé d'écran.
                    val display = displayProvider() ?: return
                    if (displayId != display.displayId) return
                    analysis.targetRotation = display.rotation
                }

                override fun onDisplayAdded(displayId: Int) = Unit

                override fun onDisplayRemoved(displayId: Int) = Unit
                // Un `Handler` nul veut dire « sur le looper du thread appelant », c'est-à-dire
                // ici le thread principal, puisque c'est de là que la liaison est faite.
            }.also { displayManager.registerDisplayListener(it, null) }
    }

    /** Idempotente : sans écouteur enregistré, elle ne fait rien. */
    private fun unregisterDisplayListener() {
        val listener = displayListener ?: return
        val displayManager = ContextCompat.getSystemService(context, DisplayManager::class.java)
        displayManager?.unregisterDisplayListener(listener)
        displayListener = null
    }
}

/**
 * Grand côté sur petit côté : comparé dans ce sens, l'orientation du tampon ne fait pas passer un
 * 4:3 et un 3:4 pour deux rapports différents.
 */
private fun Size.sideRatio(): Float = max(width, height).toFloat() / min(width, height)
