package fr.upjv.bodyvision

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.pose.Pose
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Bonus SCRUM-23 : sérialise une frame complète (33 landmarks) en JSON pour dimensionner le
 * contrat de télémétrie du projet final. Écrit le fichier à côté du CSV et estime le poids
 * d'une série de 30 s à 30 FPS (900 frames), brute et gzip.
 */
object JsonFrameExporter {

    private const val FRAMES_IN_30S_AT_30FPS = 900

    data class FrameSizeEstimate(
        val singleFrameBytes: Int,
        val framesInSeries: Int,
        val seriesRawBytes: Long,
        val seriesGzipBytes: Long
    )

    fun poseToJson(pose: Pose): JSONObject {
        val landmarksArray = JSONArray()
        for (landmark in pose.allPoseLandmarks) {
            val obj = JSONObject()
            obj.put("type", landmark.landmarkType)
            obj.put("x", landmark.position3D.x.toDouble())
            obj.put("y", landmark.position3D.y.toDouble())
            obj.put("z", landmark.position3D.z.toDouble())
            obj.put("inFrameLikelihood", landmark.inFrameLikelihood.toDouble())
            landmarksArray.put(obj)
        }
        val root = JSONObject()
        root.put("landmarks", landmarksArray)
        return root
    }

    /**
     * Écrit la frame JSON dans Documents et retourne l'estimation de poids d'une série.
     * L'estimation gzip repose sur la répétition de cette unique frame capturée (les vraies
     * frames d'un run varient légèrement) : c'est une borne optimiste, à signaler dans la note
     * de mesures.
     */
    fun exportFrameAndEstimate(
        context: Context,
        config: PoseConfig,
        pose: Pose,
        timestamp: String
    ): Pair<Uri?, FrameSizeEstimate> {
        val json = poseToJson(pose)
        val jsonString = json.toString()
        val singleFrameBytes = jsonString.toByteArray(Charsets.UTF_8).size

        val fileName = CsvExporter.buildFileName(config, timestamp, "json")
        val uri = CsvExporter.writeToPublicDocuments(context, fileName, jsonString, "application/json")

        val seriesRawBytes = singleFrameBytes.toLong() * FRAMES_IN_30S_AT_30FPS

        val seriesArray = JSONArray()
        repeat(FRAMES_IN_30S_AT_30FPS) { seriesArray.put(json) }
        val seriesGzipBytes = gzipSize(seriesArray.toString().toByteArray(Charsets.UTF_8))

        val estimate = FrameSizeEstimate(
            singleFrameBytes = singleFrameBytes,
            framesInSeries = FRAMES_IN_30S_AT_30FPS,
            seriesRawBytes = seriesRawBytes,
            seriesGzipBytes = seriesGzipBytes
        )
        return uri to estimate
    }

    private fun gzipSize(data: ByteArray): Long {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(data) }
        return baos.size().toLong()
    }
}
