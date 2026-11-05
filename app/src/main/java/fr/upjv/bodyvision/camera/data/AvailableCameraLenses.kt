package fr.upjv.bodyvision.camera.data

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "AvailableCameraLenses"

/**
 * Les objectifs que l'appareil offre réellement, demandés à CameraX.
 *
 * `hasCamera` est la réponse autoritaire : `PackageManager.hasSystemFeature` serait une seconde
 * source de vérité pour la même question, et c'est CameraX qui liera la caméra, donc c'est son
 * avis qui compte.
 *
 * À quoi ça sert, précisément : **ne pas proposer le bouton de bascule** quand l'objectif
 * n'existe pas. Sans ça, la liaison échouerait et l'écran annoncerait « caméra indisponible » à
 * quelqu'un dont la caméra marche très bien.
 *
 * Rend un ensemble vide si le fournisseur de caméra ne vient pas. C'est le repli le plus sûr :
 * l'écran reste sur l'arrière et ne propose rien.
 */
suspend fun availableCameraLenses(context: Context): Set<CameraLens> {
    val provider = awaitCameraProvider(context) ?: return emptySet()

    return CameraLens.entries
        .filter { lens ->
            try {
                provider.hasCamera(lens.toCameraSelector())
            } catch (e: CameraInfoUnavailableException) {
                // Rattrapée parce qu'on sait quoi en faire : un objectif dont on ne peut rien
                // dire est traité comme absent, donc non proposé.
                Log.w(TAG, "Objectif $lens non interrogeable, traité comme absent", e)
                false
            }
        }.toSet()
}

/**
 * Le fournisseur de caméra, attendu sans bloquer, ou `null` s'il ne vient pas.
 *
 * Écrit à la main sur le même `addListener` que `CameraSessionBinder`, plutôt qu'avec
 * `ProcessCameraProvider.awaitInstance` : cette extension existe en 1.4.2, mais son
 * implémentation passe par `androidx.concurrent.futures.await`, qui n'est pas une dépendance
 * déclarée de ce projet. Quinze lignes connues valent mieux qu'un `NoClassDefFoundError` au
 * lancement, sur un appareil que je ne peux pas tester moi-même.
 */
private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider? =
    suspendCancellableCoroutine { continuation ->
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider =
                try {
                    providerFuture.get()
                } catch (e: Exception) {
                    // Rattrapée parce qu'on sait quoi en faire : sans fournisseur, aucun objectif
                    // n'est proposé, et la liaison de l'aperçu signalera elle-même la panne.
                    Log.e(TAG, "Fournisseur de caméra indisponible", e)
                    null
                }
            // L'écran peut avoir disparu entre la demande et la réponse. Une continuation
            // annulée ignore ce qu'on lui pousse de toute façon ; la condition le rend lisible
            // au lieu de le laisser deviner.
            if (continuation.isActive) continuation.resume(provider)
        }, ContextCompat.getMainExecutor(context))
    }
