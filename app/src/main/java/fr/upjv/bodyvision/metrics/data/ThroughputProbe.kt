package fr.upjv.bodyvision.metrics.data

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import android.util.Log
import android.util.Range
import fr.upjv.bodyvision.metrics.domain.SensorFrameFacts
import fr.upjv.bodyvision.metrics.domain.ThroughputRunAggregate
import fr.upjv.bodyvision.metrics.domain.ThroughputWindow
import fr.upjv.bodyvision.metrics.domain.formatThroughputLine
import fr.upjv.bodyvision.metrics.domain.isWarmupWindow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Étiquette **dédiée** à la mesure : `adb logcat -s BvThroughput` ne garde que ces lignes, là où le
 * reste du journal émet deux lignes par image affichée. Volontairement distincte des étiquettes de
 * classe du reste du dépôt, qui servent au diagnostic et non au relevé.
 */
private const val TAG = "BvThroughput"

/**
 * La sonde de débit : ce qui compte les images, mesure les latences, et écrit **une ligne par
 * fenêtre de 5 secondes**.
 *
 * Frontière Android de la fonctionnalité `metrics` : l'horloge, les compteurs partagés entre
 * threads, le rappel de capture camera2 et le journal sont ici ; tout le calcul est dans
 * `metrics/domain`, donc éprouvable sur la JVM.
 *
 * **Deux threads l'alimentent, et un seul la fait avancer.**
 * - L'**exécuteur caméra de CameraX** appelle [sessionCaptureCallback] à chaque image produite. Il
 *   ne touche que des champs atomiques ou `@Volatile` : un incrément et trois lectures de clé, de
 *   l'ordre de la microseconde.
 * - L'**exécuteur d'analyse** (`bv-pose`) appelle les quatre `on…`, et c'est lui seul qui ouvre et
 *   clôt les fenêtres. [ThroughputWindow] et [ThroughputRunAggregate] n'ont donc besoin d'aucune
 *   synchronisation.
 *
 * **La fenêtre se clôt à l'arrivée d'une image**, jamais sur une minuterie : aucun verrou, et
 * surtout aucune tâche postée depuis le thread principal sur un exécuteur qu'un `close()` peut
 * avoir arrêté. Conséquence assumée : si plus **aucune** image n'atteint l'analyseur, aucune ligne
 * n'est écrite — l'absence de ligne est alors l'information, et le compteur à l'écran tombe à 0.
 * Une interruption plus courte, elle, se lit dans le `win_ms` allongé de la fenêtre suivante, dont
 * le débit est calculé sur cette durée réelle.
 *
 * **Elle n'est pas conditionnée à une variante de compilation**, donc elle tourne aussi en release.
 * Ses deux listes de débits grandissent d'un élément par fenêtre, soit 720 par heure de session :
 * négligeable pour un run de mesure de dix minutes, mais c'est une raison de la retirer ou de la
 * borner le jour où elle aura servi.
 *
 * [isRecording] est **lu** et non stocké : l'enregistrement a déjà sa source de vérité dans
 * `TelemetryRecording`, et en garder une copie ici ferait deux états à resynchroniser. La lecture a
 * lieu sur `bv-pose`, à chaque image, sur un `StateFlow` dont la valeur est sûre entre threads.
 */
class ThroughputProbe(
    private val isRecording: () -> Boolean,
) {
    private val window = ThroughputWindow()
    private val aggregate = ThroughputRunAggregate()

    /** Écrit par l'exécuteur caméra, remis à zéro par `bv-pose` à chaque clôture de fenêtre. */
    private val sensorFrameCount = AtomicInteger(0)

    private val hasLoggedCaptureThread = AtomicBoolean(false)

    @Volatile
    private var frameDurationNanos: Long? = null

    @Volatile
    private var exposureNanos: Long? = null

    @Volatile
    private var aeFpsRange: Range<Int>? = null

    /**
     * Écrit par le thread principal — l'écran observe `PowerManager` — et lu sur `bv-pose` à chaque
     * clôture, d'où le `@Volatile`. Un libellé et non un code : voir [observeThermalStatus].
     */
    @Volatile
    var thermalLabel: String = THERMAL_STATUS_UNAVAILABLE_LABEL

    /** Écrits et lus seulement sur l'exécuteur d'analyse, donc sans synchronisation. */
    private var runStartNanos: Long? = null
    private var firstInferenceNanos: Long? = null
    private var hasLoggedAnalysisThread = false

    /**
     * Le rappel de capture camera2, à poser sur l'`ImageAnalysis` par `Camera2Interop`. **C'est le
     * seul moyen de compter les images que le capteur produit** : avec
     * `STRATEGY_KEEP_ONLY_LATEST`, celles que CameraX jette avant de nous les livrer sont
     * invisibles pour l'analyseur.
     *
     * Vérifié dans les sources de camera-camera2 1.4.2 : le rappel est enregistré pour la requête
     * **répétée** de la session, donc appelé une fois par image que le HAL complète pour l'aperçu
     * et l'analyse réunis. Le chemin complet est écrit dans `docs/plan-s5.md`.
     *
     * Il n'**écoute** que : aucune option de requête n'est posée, donc aucun réglage de la caméra
     * n'est modifié par la mesure.
     */
    val sessionCaptureCallback: CameraCaptureSession.CaptureCallback =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                sensorFrameCount.incrementAndGet()
                // Les trois clés sont optionnelles dans un résultat de capture : une absence est un
                // cas normal, et la ligne de mesure écrit alors « NA » plutôt qu'un zéro.
                frameDurationNanos = result.get(CaptureResult.SENSOR_FRAME_DURATION)
                exposureNanos = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                aeFpsRange = result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)
                logThreadOnce()
            }

            /**
             * Une seule ligne pour tout le run, et elle prouve ce qu'un commentaire ne peut
             * qu'affirmer : ce rappel ne tourne ni sur le thread principal, ni sur `bv-pose`.
             */
            private fun logThreadOnce() {
                if (hasLoggedCaptureThread.compareAndSet(false, true)) {
                    Log.i(TAG, "Rappel de capture sur le thread ${Thread.currentThread().name}")
                }
            }
        }

    /**
     * Une image livrée à l'analyseur. À appeler **pour toutes**, avant tout autre comptage : c'est
     * ce qui rend lisible dans la ligne l'invariant `recv = drop_gate + drop_other + inf + fail`,
     * et donc `sensor − recv` égal à ce que CameraX a jeté en silence. Les deux réserves de cet
     * invariant sont écrites dans `WindowStats`.
     *
     * [imageTimestampNanos] est l'horodatage du capteur porté par l'image.
     */
    fun onFrameReceived(imageTimestampNanos: Long) {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        rollWindow(nowNanos)
        window.onFrameReceived(imageTimestampNanos, isRecording())
        if (!hasLoggedAnalysisThread) {
            hasLoggedAnalysisThread = true
            Log.i(TAG, "Analyse sur le thread ${Thread.currentThread().name}")
        }
    }

    fun onFrameDroppedByGate() {
        window.onFrameDroppedByGate()
    }

    fun onFrameDroppedOtherwise() {
        window.onFrameDroppedOtherwise()
    }

    /**
     * Une inférence réussie, et sa latence. [startNanos] est l'instant pris juste avant
     * `process()`, avec la même horloge et au même endroit que dans le spike SCRUM-22 : c'est ce
     * qui garde les latences comparables aux 26,4 ms.
     *
     * C'est aussi ici que la chauffe trouve son origine, à la **première** réussie.
     */
    fun onInferenceSucceeded(startNanos: Long) {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        if (firstInferenceNanos == null) firstInferenceNanos = nowNanos
        window.onInferenceSucceeded(nowNanos - startNanos)
        rollWindow(nowNanos)
    }

    /**
     * Une inférence lancée qui a échoué. Comptée à part, et surtout **comptée** : c'est la
     * quatrième issue possible d'une image reçue, et la laisser muette rendrait un débit effondré
     * inexplicable au relevé.
     *
     * Elle ne donne pas son origine à la chauffe : un échec ne dit pas que ML Kit a fini de
     * choisir son accélération.
     */
    fun onInferenceFailed() {
        window.onInferenceFailed()
        rollWindow(SystemClock.elapsedRealtimeNanos())
    }

    /**
     * Ouvre la première fenêtre, ou clôt celle en cours si sa durée est écoulée.
     *
     * Les images du capteur comptées entre la clôture et l'image suivante tombent dans la fenêtre
     * suivante : le décalage est de quelques millisecondes sur 5 000, et le `sensor` d'une fenêtre
     * reste donc juste à une image près.
     */
    private fun rollWindow(nowNanos: Long) {
        val runStart = runStartNanos
        if (runStart == null) {
            runStartNanos = nowNanos
            // Remis à zéro ici aussi, et pas seulement à la clôture : le rappel de capture compte
            // dès que la session caméra tourne, c'est-à-dire avant la première image livrée à
            // l'analyse. Sans ça, la première ligne annoncerait un `sensor` sans rapport avec son
            // `win_ms` — un chiffre faux qu'on recopierait sans se méfier.
            sensorFrameCount.set(0)
            window.start(nowNanos)
            return
        }
        if (!window.isElapsedAt(nowNanos)) return

        val stats =
            window.statsAt(
                nowNanos = nowNanos,
                sensorFrameCount = sensorFrameCount.getAndSet(0),
                isWarmup = isWarmupWindow(window.startNanos, firstInferenceNanos),
            )
        aggregate.add(stats)
        val range = aeFpsRange
        Log.i(
            TAG,
            formatThroughputLine(
                elapsedNanos = nowNanos - runStart,
                window = stats,
                sensor =
                    SensorFrameFacts(
                        frameDurationNanos = frameDurationNanos,
                        exposureNanos = exposureNanos,
                        aeFpsMin = range?.lower,
                        aeFpsMax = range?.upper,
                    ),
                thermalLabel = thermalLabel,
                withoutRecording = aggregate.withoutRecording(),
                whileRecording = aggregate.whileRecording(),
            ),
        )
        window.start(nowNanos)
    }
}
