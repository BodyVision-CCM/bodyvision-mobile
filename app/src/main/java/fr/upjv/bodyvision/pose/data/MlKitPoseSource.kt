package fr.upjv.bodyvision.pose.data

import android.media.Image
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import fr.upjv.bodyvision.camera.data.FrameAnalysis
import fr.upjv.bodyvision.pose.domain.PoseFrame
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "MlKitPoseSource"

/**
 * Nom du thread d'analyse, court à dessein : `ps` tronque les noms de thread à 15 caractères, et
 * c'est par ce nom qu'on vérifie sur l'appareil qu'aucun thread d'analyse ne survit à [close].
 */
private const val ANALYSIS_THREAD_NAME = "bv-pose"

/**
 * Une panne de l'analyse se répète à chaque image : on journalise la première occurrence, puis
 * une sur cent. Trente lignes par seconde noieraient le logcat et masqueraient le reste.
 */
private const val THROTTLED_LOG_PERIOD = 100

/**
 * Une inférence lancée, et ce qu'il faut garder de l'image pour lire son résultat : les
 * dimensions redressées et l'horodatage, qui ne seront plus lisibles sur l'`ImageProxy` une fois
 * celui-ci fermé.
 */
private class StartedInference(
    val task: Task<Pose>,
    val size: UprightImageSize,
    val timestampNanos: Long,
)

/**
 * La boucle de détection, reprise du spike SCRUM-22 dont elle garde le cœur mesuré.
 *
 * Possède le détecteur ML Kit **et** l'exécuteur d'analyse : les écouteurs de `Task` tournent
 * sur cet exécuteur, donc les deux ont la même durée de vie. Rien ici n'est créé dans un
 * composable, même avec `remember` : un `remember` ne survit pas à un changement de
 * configuration, et chaque rotation recréerait le détecteur en redéclenchant les 30 secondes
 * de chauffe ML Kit.
 *
 * Aucun `Context` n'est demandé — `PoseDetection.getClient` n'en a pas besoin — donc le
 * `ViewModel` qui possède cette source reste constructible par la fabrique par défaut de
 * `viewModel()`.
 */
class MlKitPoseSource {
    private val detector = PoseDetection.getClient(mlKitPoseDetectorOptions())
    private val analysisExecutor =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, ANALYSIS_THREAD_NAME) }

    /**
     * Le portillon du spike : une image qui arrive pendant une inférence est fermée et
     * abandonnée, jamais mise en file. Une file ferait croître la latence sans fin sous charge
     * thermique, et l'alerte posturale porterait alors sur une position vieille de plusieurs
     * secondes ; une image jetée ne coûte que son tour.
     *
     * Atomique et non un simple booléen, bien que tout tourne sur l'exécuteur d'analyse :
     * `process()` est asynchrone, donc le portillon est pris sur un tour et rendu sur un autre.
     *
     * **Redondant aujourd'hui** avec `STRATEGY_KEEP_ONLY_LATEST`, qui ne livre pas d'image tant
     * que la précédente n'est pas fermée — et elle ne l'est qu'après l'inférence, donc le
     * portillon n'est jamais contendu en pratique. Gardé comme invariant local : c'est lui qui
     * rend lisible l'ordre d'arrêt de [close], et il tient si la stratégie de contre-pression
     * change un jour.
     */
    private val isInferenceInFlight = AtomicBoolean(false)

    /** Garde d'idempotence de [close] : le deuxième appel ne fait rien. */
    private val isCloseRequested = AtomicBoolean(false)

    /** Lu par le `ViewModel`, sur un autre thread que l'analyse, d'où l'atomique. */
    private val analyzedFrameCount = AtomicInteger(0)

    /** Même raison : écrit par l'analyse, lu par le `ViewModel` à son battement. */
    @Volatile
    var lastLandmarkCount: Int = 0
        private set

    /** Écrits et lus seulement sur l'exécuteur d'analyse, donc sans synchronisation. */
    private var isClosing = false
    private var rejectedFrameCount = 0
    private var failedInferenceStartCount = 0

    private val mutableFrames =
        MutableSharedFlow<PoseFrame>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /**
     * Les poses détectées. `replay = 1` garde la dernière disponible pour un collecteur qui
     * arrive en retard — ce dont l'overlay de S4 aura besoin après une recomposition — et
     * `DROP_OLDEST` garantit qu'une émission ne bloque jamais l'analyse.
     *
     * Un `SharedFlow` et non un `StateFlow` : ce dernier appelle `equals()` à chaque
     * affectation pour décider s'il publie, soit une comparaison de map de 33 entrées 30 fois
     * par seconde, pour un résultat toujours faux.
     *
     * **Personne ne le collecte en S3**, et c'est normal : la preuve que les landmarks
     * arrivent, c'est le compteur à l'écran.
     */
    val frames: SharedFlow<PoseFrame> = mutableFrames.asSharedFlow()

    /** À passer à la liaison caméra, qui en tire l'`ImageAnalysis`. */
    val frameAnalysis =
        FrameAnalysis(
            analyzer = ImageAnalysis.Analyzer { analyze(it) },
            executor = analysisExecutor,
        )

    /**
     * Rend le nombre d'images analysées depuis le dernier appel, et le remet à zéro — le nom le
     * dit. C'est le `ViewModel` qui en fait un FPS, à son battement d'une seconde.
     */
    fun consumeAnalyzedFrameCount(): Int = analyzedFrameCount.getAndSet(0)

    /**
     * Ferme le détecteur et arrête l'exécuteur, **depuis l'exécuteur lui-même**.
     *
     * C'est le point délicat de tout ce fichier. `shutdown()` laisse finir ce qui est déjà en
     * file mais refuse toute nouvelle soumission — or l'écouteur de fin n'est soumis qu'au
     * moment où `process()` se termine. Quitter l'application pendant une inférence, ce qui
     * arrive presque à chaque fois puisqu'une inférence occupe 26 ms sur 33, ferait rejeter cet
     * écouteur : `imageProxy.close()` n'aurait jamais lieu et l'exception partirait dans un
     * thread de Play Services. D'où cette mise en ordre : l'arrêt est une tâche de la même file
     * que l'analyse, et c'est le dernier écouteur de fin qui arrête l'exécuteur s'il reste une
     * inférence en vol. `shutdownNow()` serait exactement le défaut qu'on cherche à éviter.
     *
     * Fermer le détecteur pendant une inférence n'en perd pas la fin : ML Kit annule la `Task`
     * en cours, et une annulation reste une fin — l'écouteur de complétion est appelé, ceux de
     * succès et d'échec ne le sont pas.
     *
     * Idempotente : le deuxième appel ne fait rien. Sans cette garde, il soumettrait une tâche à
     * un exécuteur déjà arrêté, donc lèverait un `RejectedExecutionException` sur le thread
     * appelant — le thread principal, dans `onCleared()`.
     */
    fun close() {
        if (isCloseRequested.getAndSet(true)) return
        analysisExecutor.execute {
            isClosing = true
            detector.close()
            if (!isInferenceInFlight.get()) analysisExecutor.shutdown()
        }
    }

    /**
     * Tourne sur l'exécuteur d'analyse, jamais sur le thread principal.
     *
     * **`imageProxy.close()` est appelé sur les trois chemins** : sortie anticipée, succès et
     * échec — les deux derniers par l'écouteur de complétion, qui couvre aussi l'annulation. Un
     * `close()` manqué gèle la caméra au bout de quelques images, sans aucun message.
     */
    @OptIn(ExperimentalGetImage::class)
    private fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (isClosing || mediaImage == null || !isInferenceInFlight.compareAndSet(false, true)) {
            // Aucun portillon à rendre : l'évaluation court-circuite, donc il n'a pas été pris.
            imageProxy.close()
            return
        }

        // Le portillon est pris et aucun `close()` n'est encore programmé : c'est la seule
        // fenêtre du fichier où une levée laisserait le portillon fermé pour toujours et l'image
        // jamais rendue au pool de CameraX, c'est-à-dire un aperçu gelé sans aucun message.
        // L'enregistrement des écouteurs reste **en dehors** du try : une levée après eux
        // fermerait l'image une deuxième fois.
        val inference =
            try {
                startInference(imageProxy, mediaImage)
            } catch (cause: Exception) {
                logFailedInferenceStart(cause)
                null
            }

        if (inference == null) {
            // Rejet ou levée : un seul endroit rend le portillon et ferme l'image, parce que
            // c'est ici, et nulle part ailleurs, qu'on sait qu'il a été pris.
            isInferenceInFlight.set(false)
            imageProxy.close()
            return
        }

        inference.task
            // L'exécuteur d'analyse est passé explicitement : sans lui, les écouteurs tournent
            // sur le thread principal, et la conversion des 33 landmarks y tomberait 30 fois
            // par seconde.
            .addOnSuccessListener(analysisExecutor) { pose ->
                val frame = pose.toPoseFrame(inference.size, inference.timestampNanos)
                lastLandmarkCount = frame.landmarks.size
                // Compté avant l'émission, comme `onFrameAnalyzed` dans le spike : ce chiffre
                // doit rester comparable aux 29,4 FPS de SCRUM-22, qui comptaient les succès de
                // `process()` et non ce qui survit à un tampon.
                analyzedFrameCount.incrementAndGet()
                // `tryEmit` et non une coroutine par image : avec `replay = 1` et
                // `DROP_OLDEST`, il réussit toujours et ne suspend jamais.
                mutableFrames.tryEmit(frame)
            }.addOnFailureListener(analysisExecutor) { cause ->
                Log.w(TAG, "Échec de l'inférence de pose", cause)
            }.addOnCompleteListener(analysisExecutor) {
                isInferenceInFlight.set(false)
                imageProxy.close()
                // Inférence en vol au moment de la fermeture : c'est ici, et seulement ici, que
                // l'exécuteur peut s'arrêter sans perdre ce `close()`.
                if (isClosing) analysisExecutor.shutdown()
            }
    }

    /**
     * Lance l'inférence, ou rend `null` pour une image rejetée — une dimension absurde — après
     * l'avoir journalisée.
     *
     * Ne ferme rien et ne rend pas le portillon : c'est [analyze] qui s'en charge, au même
     * endroit pour le rejet et pour la levée.
     */
    private fun startInference(
        imageProxy: ImageProxy,
        mediaImage: Image,
    ): StartedInference? {
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val size = uprightImageSizeOf(imageProxy.width, imageProxy.height, rotationDegrees)
        if (size == null) {
            logRejectedFrame(imageProxy.width, imageProxy.height)
            return null
        }

        return StartedInference(
            task = detector.process(InputImage.fromMediaImage(mediaImage, rotationDegrees)),
            size = size,
            timestampNanos = imageProxy.imageInfo.timestamp,
        )
    }

    private fun logRejectedFrame(
        imageWidth: Int,
        imageHeight: Int,
    ) {
        rejectedFrameCount++
        if (rejectedFrameCount == 1 || rejectedFrameCount % THROTTLED_LOG_PERIOD == 0) {
            Log.w(
                TAG,
                "Image rejetée, dimensions ${imageWidth}x$imageHeight : " +
                    "$rejectedFrameCount depuis le démarrage",
            )
        }
    }

    private fun logFailedInferenceStart(cause: Throwable) {
        failedInferenceStartCount++
        if (failedInferenceStartCount == 1 || failedInferenceStartCount % THROTTLED_LOG_PERIOD == 0) {
            Log.e(
                TAG,
                "Inférence non lancée, image abandonnée : " +
                    "$failedInferenceStartCount depuis le démarrage",
                cause,
            )
        }
    }
}
