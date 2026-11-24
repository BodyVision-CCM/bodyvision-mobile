package fr.upjv.bodyvision.metrics.data

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

private const val TAG = "ThermalStatus"

/**
 * Ce qu'on écrit tant que l'état thermique n'est pas connu : avant le premier événement, et pour
 * toujours sous Android 10, où `getCurrentThermalStatus` n'existe pas. Jamais `NONE`, qui
 * affirmerait un téléphone froid sans l'avoir mesuré.
 */
const val THERMAL_STATUS_UNAVAILABLE_LABEL = "NA"

/** Rien à retirer : ce qu'on rend quand il n'y a rien eu à observer. */
private val NOTHING_TO_STOP: () -> Unit = {}

/**
 * Observe l'état thermique du téléphone et rend de quoi **arrêter** de l'observer.
 *
 * Appelée par l'écran et non par le `ViewModel` : `PowerManager` demande un `Context`, que le
 * `ViewModel` n'a pas et ne doit pas avoir — même motif que les objectifs disponibles et le dossier
 * de télémétrie. Ce qui remonte est un **libellé**, donc rien d'Android : la sonde de débit n'a pas
 * à connaître `PowerManager`.
 *
 * `getCurrentThermalStatus` et `addThermalStatusListener` sont de l'API 29, et `minSdk` est 24 :
 * sous Android 10 cette fonction ne fait rien et la ligne de mesure porte
 * [THERMAL_STATUS_UNAVAILABLE_LABEL]. Sans cette garde, le lint en ferait une **erreur**, et il
 * aurait raison.
 *
 * L'état courant est lu tout de suite, en plus de l'écoute : l'écouteur ne parle qu'au prochain
 * **changement**, et un téléphone qui reste à `NONE` pendant dix minutes n'en produirait aucun.
 */
fun observeThermalStatus(
    context: Context,
    onLabelChanged: (String) -> Unit,
): () -> Unit =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        observeThermalStatusFromApi29(context, onLabelChanged)
    } else {
        NOTHING_TO_STOP
    }

@RequiresApi(Build.VERSION_CODES.Q)
private fun observeThermalStatusFromApi29(
    context: Context,
    onLabelChanged: (String) -> Unit,
): () -> Unit {
    val powerManager = ContextCompat.getSystemService(context, PowerManager::class.java)
    if (powerManager == null) {
        // Rien à rattraper et rien à inventer : la mesure se fait sans la colonne thermique, et la
        // ligne le dit elle-même.
        Log.w(TAG, "État thermique non suivi : service d'alimentation introuvable")
        return NOTHING_TO_STOP
    }

    val listener =
        PowerManager.OnThermalStatusChangedListener { status -> onLabelChanged(thermalStatusLabelOf(status)) }
    onLabelChanged(thermalStatusLabelOf(powerManager.currentThermalStatus))
    powerManager.addThermalStatusListener(listener)
    return { powerManager.removeThermalStatusListener(listener) }
}

/**
 * Le libellé d'un état thermique, tel qu'il part dans le journal de mesure.
 *
 * Les constantes de `PowerManager` et non leurs valeurs numériques : un `7 -> "SHUTDOWN"` serait un
 * nombre magique, et il n'y a aucune raison de recopier une table que la plateforme fournit.
 */
@RequiresApi(Build.VERSION_CODES.Q)
private fun thermalStatusLabelOf(status: Int): String =
    when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        // Un état que cette version d'Android connaît et que ce code ne connaît pas : il part dans
        // le journal tel quel, plutôt que d'être écrasé en « inconnu ».
        else -> "UNKNOWN($status)"
    }
