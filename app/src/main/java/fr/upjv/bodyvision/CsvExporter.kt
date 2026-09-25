package fr.upjv.bodyvision

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/** Métadonnées d'en-tête du run, écrites en première ligne du CSV. */
data class RunInfo(
    val phoneModel: String,
    val androidVersion: String,
    val config: PoseConfig,
    val resolution: String,
    val startDateIso: String,
    val durationSeconds: Long,
    val batteryStartPct: Int,
    val batteryEndPct: Int
)

/**
 * Écrit le CSV de mesures dans le dossier public Documents via MediaStore (API 29+), avec
 * repli sur l'écriture fichier directe + WRITE_EXTERNAL_STORAGE pour les API < 29.
 */
object CsvExporter {

    fun buildFileName(config: PoseConfig, timestamp: String, extension: String = "csv"): String =
        "bodyvision_spike_${config.fileTag}_$timestamp.$extension"

    fun buildCsvContent(runInfo: RunInfo, records: List<SecondRecord>): String {
        val sb = StringBuilder()
        sb.append("modele_telephone,version_android,configuration,resolution,date,duree_s,batterie_debut_pct,batterie_fin_pct\n")
        sb.append(csvEscape(runInfo.phoneModel)).append(',')
        sb.append(runInfo.androidVersion).append(',')
        sb.append(csvEscape(runInfo.config.label)).append(',')
        sb.append(runInfo.resolution).append(',')
        sb.append(runInfo.startDateIso).append(',')
        sb.append(runInfo.durationSeconds).append(',')
        sb.append(runInfo.batteryStartPct).append(',')
        sb.append(runInfo.batteryEndPct).append('\n')
        sb.append('\n')
        sb.append(
            "t_secondes,phase,fps,latence_mediane_ms,latence_p95_ms,frames_droppees,temp_batterie_c," +
                "etat_thermique,batterie_pct,likelihood_moyen,angle_genou_deg,ecart_type_angle_deg\n"
        )
        for (r in records) {
            sb.append(r.tSeconds).append(',')
            sb.append(r.phase).append(',')
            sb.append(fmt(r.fps)).append(',')
            sb.append(fmt(r.latencyMedianMs)).append(',')
            sb.append(fmt(r.latencyP95Ms)).append(',')
            sb.append(r.framesDropped).append(',')
            sb.append(fmt(r.batteryTempC)).append(',')
            sb.append(r.thermalStatus).append(',')
            sb.append(r.batteryPct).append(',')
            sb.append(fmt(r.likelihoodAvg, 3)).append(',')
            sb.append(fmt(r.kneeAngleDeg)).append(',')
            sb.append(fmt(r.angleStdDevDeg)).append('\n')
        }
        return sb.toString()
    }

    private fun fmt(v: Double, decimals: Int = 2): String =
        if (v.isNaN()) "" else String.format(Locale.US, "%.${decimals}f", v)

    private fun csvEscape(s: String): String =
        if (s.contains(",") || s.contains("\"")) "\"${s.replace("\"", "\"\"")}\"" else s

    /** Écrit [content] dans Documents/[fileName]. Retourne l'Uri écrit, ou null en cas d'échec. */
    fun writeToPublicDocuments(
        context: Context,
        fileName: String,
        content: String,
        mimeType: String
    ): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeViaMediaStore(context, fileName, content, mimeType)
        } else {
            writeLegacy(fileName, content)
        }
    }

    private fun writeViaMediaStore(
        context: Context,
        fileName: String,
        content: String,
        mimeType: String
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        } ?: return null
        return uri
    }

    private fun writeLegacy(fileName: String, content: String): Uri? {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        if (!docsDir.exists()) docsDir.mkdirs()
        val file = File(docsDir, fileName)
        return try {
            FileOutputStream(file).use { it.write(content.toByteArray(Charsets.UTF_8)) }
            Uri.fromFile(file)
        } catch (e: Exception) {
            null
        }
    }
}
