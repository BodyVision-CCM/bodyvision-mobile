package fr.upjv.bodyvision.telemetry.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val TAG = "TelemetryFileWriter"

/** Sous-dossier du stockage propre à l'application où les séries sont déposées. */
const val TELEMETRY_DIRECTORY_NAME = "telemetry"

private const val JSON_EXTENSION = "json"

/** Un fichier écrit : de quoi le retrouver par `adb pull` et dire ce qu'il pèse. */
data class TelemetryFile(
    val path: String,
    val sizeBytes: Long,
)

/**
 * Écrit le JSON d'une série dans `<directory>/<batchId>.json`, ou rend `null` si l'écriture a
 * échoué — après l'avoir journalisée, parce que c'est ici qu'on connaît le chemin et la cause.
 *
 * **Sur [Dispatchers.IO] et pas ailleurs.** `Dispatchers.Default` est dimensionné pour le calcul :
 * il a autant de fils que l'appareil a de cœurs, et un fil qui attend le disque en occuperait un
 * pour ne rien faire. `IO` est fait pour ça, il grandit quand plusieurs écritures attendent.
 * Sur le thread principal, les ~280 Ko d'une série de 10 squats seraient un gel visible.
 *
 * Le nom du fichier est le `batchId`, donc un UUID : deux séries ne peuvent pas se recouvrir, et le
 * nom est aussi la clé que le serveur verra au S6. Aucune permission n'est demandée, rien ne sort
 * du dossier de l'application, et aucune image n'est écrite — seulement des coordonnées.
 *
 * `IOException` est rattrapée parce qu'on sait quoi en faire : la série est perdue, on le dit, et
 * l'application continue de filmer. Tout le reste remonte.
 */
suspend fun writeTelemetryFile(
    directory: File,
    batchId: String,
    json: String,
): TelemetryFile? =
    withContext(Dispatchers.IO) {
        try {
            // `getExternalFilesDir` a normalement déjà créé le dossier ; il peut avoir été effacé
            // depuis, par exemple par « Vider le cache » dans les réglages de l'application.
            if (!directory.exists() && !directory.mkdirs()) {
                Log.e(TAG, "Dossier de télémétrie impossible à créer : ${directory.absolutePath}")
                return@withContext null
            }
            val file = File(directory, "$batchId.$JSON_EXTENSION")
            file.writeText(json)
            TelemetryFile(path = file.absolutePath, sizeBytes = file.length())
        } catch (cause: IOException) {
            Log.e(TAG, "Écriture de la série $batchId impossible dans ${directory.absolutePath}", cause)
            null
        }
    }
