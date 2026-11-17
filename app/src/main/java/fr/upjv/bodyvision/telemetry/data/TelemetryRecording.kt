package fr.upjv.bodyvision.telemetry.data

import android.util.Log
import fr.upjv.bodyvision.pose.domain.PoseFrame
import fr.upjv.bodyvision.telemetry.domain.SeriesRecorder
import fr.upjv.bodyvision.telemetry.domain.SeriesStatus
import fr.upjv.bodyvision.telemetry.domain.encodeSeriesUpload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val TAG = "TelemetryRecording"

/**
 * L'enregistrement d'une série : la collecte des poses, l'encodage, l'écriture du fichier et le
 * journal.
 *
 * Vit dans la fonctionnalité `telemetry` et non dans le `ViewModel` de l'écran : celui-ci décide
 * déjà de la permission, de l'objectif et des compteurs, et l'enregistrement est un quatrième
 * sujet, avec ses propres fils d'exécution. Le `ViewModel` le **possède** — donc l'état survit à la
 * rotation — et ne lui demande que deux choses, démarrer et arrêter.
 *
 * Rien d'Android ici sinon le journal et un `File` : ni `Context`, ni cycle de vie. Les décisions,
 * elles, sont toutes dans `telemetry/domain`, qui se teste sur la JVM.
 *
 * [frames] est le flux de poses de `MlKitPoseSource`, déjà partagé avec l'overlay. Son
 * `onBufferOverflow` garantit qu'une émission ne bloque jamais l'analyse : un collecteur lent perd
 * des images, il ne ralentit pas la détection. Le travail par image est ici minuscule — comparer un
 * horodatage, et au plus une fois sur trois recopier 33 points — donc rien n'est perdu en pratique.
 *
 * **Tout l'état de cet objet est lu et écrit sur le thread principal**, puisque [scope] est celui
 * du `ViewModel`, qui est sur `Dispatchers.Main.immediate`. Seules la collecte, l'encodage et
 * l'écriture partent ailleurs.
 */
class TelemetryRecording(
    private val scope: CoroutineScope,
    private val frames: SharedFlow<PoseFrame>,
) {
    private val mutableIsRecording = MutableStateFlow(false)

    /**
     * Vrai tant qu'une série est **ouverte**, et faux dès qu'elle est close — même si son fichier
     * n'est pas encore écrit. L'écriture est la queue de l'opération, pas sa fin : annoncer un
     * enregistrement en cours pendant qu'on écrit laisserait croire qu'on capte encore.
     */
    val isRecording: StateFlow<Boolean> = mutableIsRecording.asStateFlow()

    private var recorder: SeriesRecorder? = null
    private var directory: File? = null
    private var collectJob: Job? = null

    /**
     * Démarre une série. Sans effet si une autre est déjà en cours.
     *
     * Le `batchId` et l'accumulateur sont deux **variables locales** : rien ne les garde au-delà de
     * l'enregistrement en cours, donc un second appui ne peut pas les réutiliser — il n'y a rien à
     * réutiliser. Chaque série a son UUID, et donc son fichier.
     *
     * La série peut se terminer **toute seule**, sans que personne appuie : une image d'une autre
     * taille, ou la 900e image gardée. Dans ce cas la collecte s'arrête d'elle-même et la suite de
     * cette coroutine écrit le fichier — c'est tout l'intérêt de `takeWhile`, qui évite de lever
     * une exception pour sortir d'une collecte.
     */
    fun start(directory: File) {
        if (collectJob != null) return

        val batchId = UUID.randomUUID().toString()
        val recorder = SeriesRecorder(batchId, mlKitLandmarkOrder, System::currentTimeMillis)
        this.recorder = recorder
        this.directory = directory
        mutableIsRecording.value = true

        collectJob =
            scope.launch {
                try {
                    // Dispatchers.Default explicitement : `viewModelScope` est sur le thread
                    // principal, et sans ça la conversion des 33 points y tomberait dix fois par
                    // seconde.
                    withContext(Dispatchers.Default) {
                        frames.takeWhile { recorder.accept(it) == SeriesStatus.OPEN }.collect()
                    }
                } catch (cause: CancellationException) {
                    // L'arrêt a été demandé : c'est `stop()` qui nettoie et qui écrit. Relancée et
                    // jamais avalée, et le motif est précis : l'avaler ferait tomber la collecte
                    // annulée dans la queue ci-dessous, qui écrirait la série **en plus** de
                    // `stop()` — deux écritures sur le même `batchId`, donc sur le même chemin de
                    // fichier.
                    throw cause
                } catch (cause: Exception) {
                    // Sans ce chemin, une levée dans la collecte laisserait `isRecording` vrai pour
                    // toujours : l'indicateur resterait allumé, le bouton sur « Arrêter », et la
                    // garde de `start()` refuserait tout nouvel enregistrement. La série en cours
                    // est abandonnée — l'accumulateur a levé, donc son contenu n'est plus sûr.
                    Log.e(TAG, "Collecte interrompue par une erreur, série abandonnée", cause)
                    clearRecording()
                    return@launch
                }

                // On revient sur le thread principal : la série s'est fermée d'elle-même.
                // `NonCancellable` pour la même raison que dans `stop()` : son fichier est tout ce
                // qui reste d'elle.
                withContext(NonCancellable) {
                    clearRecording()
                    writeSeries(recorder, directory)
                }
            }
    }

    /**
     * Termine la série en cours et écrit son fichier. Sans effet s'il n'y en a pas, ce qui est le
     * cas courant : l'écran l'appelle à chaque discontinuité du flux caméra, enregistrement ou pas.
     */
    fun stop() {
        val recorder = this.recorder ?: return
        val directory = this.directory ?: return
        val collectJob = this.collectJob ?: return
        clearRecording()

        scope.launch {
            // `NonCancellable` autour des **deux** appels, et pas seulement autour de l'écriture :
            // c'est le correctif du défaut le plus coûteux de ce ticket. Le bouton retour exécute
            // `ON_PAUSE → ON_STOP → ON_DESTROY` dans un seul message du looper principal, donc
            // `onCleared()` annule `viewModelScope` pendant que `join()` attend encore le
            // collecteur — la coroutine mourait avant d'atteindre l'écriture, et la série était
            // perdue **à chaque fois**, pas dans une fenêtre étroite. Ce bloc démarre, lui,
            // synchronement avant l'annulation, donc il la franchit.
            withContext(NonCancellable) {
                // `cancelAndJoin` et non `cancel` : il faut que le collecteur soit **fini** avant
                // de lire l'accumulateur. Sinon une dernière image pourrait s'y ajouter pendant
                // qu'on le recopie. C'est ce qui remplace un verrou, et c'est plus simple à relire.
                collectJob.cancelAndJoin()
                writeSeries(recorder, directory)
            }
        }
    }

    private fun clearRecording() {
        recorder = null
        directory = null
        collectJob = null
        mutableIsRecording.value = false
    }

    private suspend fun writeSeries(
        recorder: SeriesRecorder,
        directory: File,
    ) {
        val series = recorder.complete()
        if (series == null) {
            // Ni taille d'image ni `startedAt` à annoncer : il n'y a pas de série valide à écrire.
            // Un fichier vide serait pire qu'aucun, il aurait l'air d'une mesure.
            Log.w(
                TAG,
                "Série vide, aucun fichier écrit : ${recorder.discardedFrameCount} image(s) écartée(s)",
            )
            return
        }

        // `Dispatchers.Default` pour l'encodage, et `NonCancellable` redit ici ce que les deux
        // appelants posent déjà : la série est close, ce fichier est la seule trace qui en reste, et
        // cette fonction doit rester sûre quel que soit le chemin qui l'appelle.
        val file =
            withContext(NonCancellable + Dispatchers.Default) {
                writeTelemetryFile(directory, series.batchId, encodeSeriesUpload(series))
            }

        if (file == null) {
            Log.e(TAG, "Série ${series.batchId} perdue : écriture impossible")
            return
        }
        Log.i(
            TAG,
            "Série ${series.batchId} écrite dans ${file.path} : ${file.sizeBytes} octets, " +
                "${series.frames.size} image(s) gardée(s), ${recorder.discardedFrameCount} écartée(s), " +
                "${recorder.sampledOutFrameCount} hors échantillon",
        )
    }
}
