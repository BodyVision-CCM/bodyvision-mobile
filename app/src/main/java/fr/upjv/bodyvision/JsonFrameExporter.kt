package fr.upjv.bodyvision

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.pose.Pose
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.zip.GZIPOutputStream

/**
 * Bonus SCRUM-23 : sérialise une frame complète (33 landmarks) en JSON pour dimensionner le
 * contrat de télémétrie du projet final. Écrit le fichier à côté du CSV et estime le poids
 * d'une série de 30 s à 30 FPS (900 frames), brute et gzip, à partir des frames réellement
 * capturées pendant le run (voir [onFrameAnalyzed]).
 */
object JsonFrameExporter {

    private const val FRAMES_IN_30S_AT_30FPS = 900

    // Tampon glissant des dernières frames réellement sérialisées pendant le run en cours.
    private val frameBuffer = ArrayDeque<String>()

    data class FrameSizeEstimate(
        val singleFrameBytes: Int,
        val framesInSeries: Int,
        val seriesRawBytes: Long,
        val seriesGzipBytes: Long,
        val capturedFrameCount: Int,
        val extrapolated: Boolean
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

    /** À appeler à chaque frame analysée pendant un run pour alimenter le tampon de capture. */
    @Synchronized
    fun onFrameAnalyzed(pose: Pose) {
        frameBuffer.addLast(poseToJson(pose).toString())
        while (frameBuffer.size > FRAMES_IN_30S_AT_30FPS) {
            frameBuffer.removeFirst()
        }
    }

    /** À appeler au démarrage de chaque run, pour ne pas mélanger les frames de deux runs/configs. */
    @Synchronized
    fun clearBuffer() {
        frameBuffer.clear()
    }

    /**
     * Écrit la frame JSON dans Documents et retourne l'estimation de poids d'une série, basée
     * sur les frames réellement capturées pendant le run (tampon des 900 dernières). Si moins de
     * 900 frames sont disponibles, le résultat est extrapolé au prorata (estimate.extrapolated).
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

        val bufferedFrames = synchronized(this) { frameBuffer.toList() }

        val seriesRawBytes: Long
        val seriesGzipBytes: Long
        val capturedFrameCount: Int
        val extrapolated: Boolean

        if (bufferedFrames.isEmpty()) {
            // Repli si le bouton est pressé avant toute frame capturée : frame courante répétée.
            seriesRawBytes = singleFrameBytes.toLong() * FRAMES_IN_30S_AT_30FPS
            val seriesArray = JSONArray()
            repeat(FRAMES_IN_30S_AT_30FPS) { seriesArray.put(json) }
            seriesGzipBytes = gzipSize(seriesArray.toString().toByteArray(Charsets.UTF_8))
            capturedFrameCount = 0
            extrapolated = true
        } else {
            val bufferedJson = "[" + bufferedFrames.joinToString(",") + "]"
            val bufferedBytes = bufferedJson.toByteArray(Charsets.UTF_8)
            val bufferedRawBytes = bufferedBytes.size.toLong()
            val bufferedGzipBytes = gzipSize(bufferedBytes)
            val scale = FRAMES_IN_30S_AT_30FPS.toDouble() / bufferedFrames.size
            seriesRawBytes = (bufferedRawBytes * scale).toLong()
            seriesGzipBytes = (bufferedGzipBytes * scale).toLong()
            capturedFrameCount = bufferedFrames.size
            extrapolated = bufferedFrames.size < FRAMES_IN_30S_AT_30FPS
        }

        val estimate = FrameSizeEstimate(
            singleFrameBytes = singleFrameBytes,
            framesInSeries = FRAMES_IN_30S_AT_30FPS,
            seriesRawBytes = seriesRawBytes,
            seriesGzipBytes = seriesGzipBytes,
            capturedFrameCount = capturedFrameCount,
            extrapolated = extrapolated
        )
        return uri to estimate
    }

    private fun gzipSize(data: ByteArray): Long {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(data) }
        return baos.size().toLong()
    }
}
