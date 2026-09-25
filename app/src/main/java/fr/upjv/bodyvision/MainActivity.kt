package fr.upjv.bodyvision

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "BodyVisionSpike"

@androidx.camera.core.ExperimentalGetImage
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: SkeletonOverlayView
    private lateinit var hudText: TextView
    private lateinit var configSpinner: Spinner
    private lateinit var startStopButton: Button
    private lateinit var exportJsonButton: Button

    private lateinit var analysisExecutor: ExecutorService
    private lateinit var thermalMonitor: ThermalMonitor
    private lateinit var batteryMonitor: BatteryMonitor

    private var cameraProvider: ProcessCameraProvider? = null
    private var poseDetector: PoseDetector? = null
    private var currentConfig: PoseConfig = PoseConfig.CONFIG_1_BASE_DEFAULT

    private var runStats: RunStatistics? = null
    private var isRunning = false
    private val busy = AtomicBoolean(false)

    @Volatile
    private var lastPose: Pose? = null

    private var runStartTimestampIso = ""
    private var batteryStartPct = -1
    private var tempStartC = Double.NaN

    private val tickHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            onSecondTick()
            tickHandler.postDelayed(this, 1000)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.CAMERA] == true) {
            initCameraProvider()
        } else {
            Toast.makeText(this, "Permission caméra requise pour le spike.", Toast.LENGTH_LONG).show()
        }
    }

    private val poseAnalyzer = ImageAnalysis.Analyzer { imageProxy ->
        runStats?.onFrameReceived()
        val detector = poseDetector
        val mediaImage = imageProxy.image
        if (detector == null || mediaImage == null || !busy.compareAndSet(false, true)) {
            imageProxy.close()
            return@Analyzer
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val imgW: Int
        val imgH: Int
        if (rotation == 90 || rotation == 270) {
            imgW = imageProxy.height
            imgH = imageProxy.width
        } else {
            imgW = imageProxy.width
            imgH = imageProxy.height
        }
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)
        val startNanos = SystemClock.elapsedRealtimeNanos()

        detector.process(inputImage)
            .addOnSuccessListener { pose ->
                val latencyNanos = SystemClock.elapsedRealtimeNanos() - startNanos
                val kneeAngle = rightKneeAngleDegrees(pose)
                val likelihood = rightSideAvgLikelihood(pose)
                runStats?.onFrameAnalyzed(latencyNanos, kneeAngle, likelihood)
                lastPose = pose
                overlayView.updatePose(pose, imgW, imgH)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Échec inférence pose", e)
            }
            .addOnCompleteListener {
                busy.set(false)
                imageProxy.close()
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview_view)
        overlayView = findViewById(R.id.overlay_view)
        hudText = findViewById(R.id.hud_text)
        configSpinner = findViewById(R.id.config_spinner)
        startStopButton = findViewById(R.id.start_stop_button)
        exportJsonButton = findViewById(R.id.export_json_button)

        analysisExecutor = Executors.newSingleThreadExecutor()
        thermalMonitor = ThermalMonitor(this)
        batteryMonitor = BatteryMonitor(this)

        setupSpinner()
        startStopButton.setOnClickListener { onStartStopClicked() }
        exportJsonButton.setOnClickListener { onExportJsonClicked() }

        hudText.text = "En attente du démarrage du run..."

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            initCameraProvider()
        } else {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }

        showProtocolReminder()
    }

    override fun onStart() {
        super.onStart()
        batteryMonitor.start()
        thermalMonitor.start { status -> runStats?.onThermalStatusChanged(status) }
    }

    override fun onStop() {
        super.onStop()
        batteryMonitor.stop()
        thermalMonitor.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        tickHandler.removeCallbacks(tickRunnable)
        poseDetector?.close()
        analysisExecutor.shutdown()
    }

    private fun setupSpinner() {
        val labels = PoseConfig.values().map { it.label }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        configSpinner.adapter = adapter
        configSpinner.setSelection(0)
    }

    private fun showProtocolReminder() {
        val message = """
            - Téléphone sur trépied, sujet debout, à 2-3 m
            - Luminosité d'écran fixée à 50 %
            - Téléphone débranché (la charge fausse la mesure thermique)
            - Run de 10 minutes, mouvement continu (squats lents)
            - 15 min de refroidissement entre 2 runs, départ sous 30°C
            - 3 runs par configuration
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle("Protocole de test - à respecter")
            .setMessage(message)
            .setCancelable(true)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun initCameraProvider() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCameraUseCases(includeAnalysis = false)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(includeAnalysis: Boolean) {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        val useCases = mutableListOf<UseCase>(preview)

        if (includeAnalysis) {
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        currentConfig.analysisResolution,
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor, poseAnalyzer)
            useCases.add(analysis)
        }

        try {
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray())
        } catch (e: Exception) {
            Log.e(TAG, "Échec bind CameraX", e)
        }
    }

    private fun onStartStopClicked() {
        if (isRunning) stopRun() else startRun()
    }

    private fun startRun() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            return
        }
        if (cameraProvider == null) {
            Toast.makeText(this, "Caméra pas encore prête, réessayez.", Toast.LENGTH_SHORT).show()
            return
        }

        currentConfig = PoseConfig.values()[configSpinner.selectedItemPosition]
        poseDetector?.close()
        poseDetector = PoseDetection.getClient(currentConfig.buildOptions())
        lastPose = null
        busy.set(false)

        bindCameraUseCases(includeAnalysis = true)

        val stats = RunStatistics()
        stats.onThermalStatusChanged(thermalMonitor.currentStatus)
        runStats = stats

        batteryStartPct = batteryMonitor.batteryPercent()
        tempStartC = batteryMonitor.temperatureCelsius()
        runStartTimestampIso = isoNow()

        isRunning = true
        configSpinner.isEnabled = false
        startStopButton.text = "Arrêter"

        tickHandler.removeCallbacks(tickRunnable)
        tickHandler.post(tickRunnable)
    }

    private fun stopRun() {
        isRunning = false
        tickHandler.removeCallbacks(tickRunnable)
        configSpinner.isEnabled = true
        startStopButton.text = "Démarrer"

        val stats = runStats
        if (stats == null) {
            bindCameraUseCases(includeAnalysis = false)
            return
        }

        val batteryEndPct = batteryMonitor.batteryPercent()
        val tempEndC = batteryMonitor.temperatureCelsius()
        val timestamp = fileTimestampNow()

        val runInfo = RunInfo(
            phoneModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            config = currentConfig,
            resolution = "${currentConfig.analysisResolution.width}x${currentConfig.analysisResolution.height}",
            startDateIso = runStartTimestampIso,
            durationSeconds = stats.elapsedSeconds(),
            batteryStartPct = batteryStartPct,
            batteryEndPct = batteryEndPct
        )

        val csvContent = CsvExporter.buildCsvContent(runInfo, stats.secondRecords)
        val fileName = CsvExporter.buildFileName(currentConfig, timestamp)
        val uri = CsvExporter.writeToPublicDocuments(this, fileName, csvContent, "text/csv")

        Toast.makeText(
            this,
            if (uri != null) "CSV enregistré : Documents/$fileName" else "Échec de l'export CSV",
            Toast.LENGTH_LONG
        ).show()

        bindCameraUseCases(includeAnalysis = false)
        poseDetector?.close()
        poseDetector = null

        showSummaryDialog(stats.summary(), runInfo, tempStartC, tempEndC)
        runStats = null
    }

    private fun showSummaryDialog(summary: RunSummary, runInfo: RunInfo, tempStart: Double, tempEnd: Double) {
        val verdict = if (summary.meetsThresholds) "SEUILS OK" else "SEUILS NON ATTEINTS"
        val message = buildString {
            append("Configuration : ${runInfo.config.label}\n")
            append("Durée : ${runInfo.durationSeconds} s\n\n")
            append(String.format(Locale.US, "FPS moyen : %.1f (min %.1f)\n", summary.avgFps, summary.minFps))
            append(String.format(Locale.US, "Latence médiane : %.1f ms\n", summary.latencyMedianMs))
            append(String.format(Locale.US, "Latence p95 : %.1f ms\n", summary.latencyP95Ms))
            append("État thermique max : ${ThermalMonitor.statusLabel(summary.maxThermalStatus)}\n")
            append(String.format(Locale.US, "Écart-type angle moyen : %.1f °\n", summary.avgAngleStdDevDeg))
            append(String.format(Locale.US, "Température : %.1f°C -> %.1f°C\n", tempStart, tempEnd))
            append("Batterie : ${runInfo.batteryStartPct}% -> ${runInfo.batteryEndPct}%\n\n")
            append("Verdict : $verdict")
        }
        AlertDialog.Builder(this)
            .setTitle("Résumé du run - à recopier dans la note de mesures")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun onExportJsonClicked() {
        val pose = lastPose
        if (pose == null) {
            Toast.makeText(this, "Aucune pose détectée pour le moment.", Toast.LENGTH_SHORT).show()
            return
        }
        val timestamp = fileTimestampNow()
        val (uri, estimate) = JsonFrameExporter.exportFrameAndEstimate(this, currentConfig, pose, timestamp)
        val fileName = CsvExporter.buildFileName(currentConfig, timestamp, "json")
        val message = buildString {
            append("Frame JSON : ${estimate.singleFrameBytes} octets\n\n")
            append("Estimation série 30 s @ 30 FPS (${estimate.framesInSeries} frames) :\n")
            append("  brute : ${formatBytes(estimate.seriesRawBytes)}\n")
            append("  gzip  : ${formatBytes(estimate.seriesGzipBytes)}\n\n")
            append("(estimation gzip optimiste : une seule frame capturée, répétée)\n\n")
            append(if (uri != null) "Fichier : Documents/$fileName" else "Échec de l'écriture du fichier JSON")
        }
        AlertDialog.Builder(this)
            .setTitle("Poids d'une frame de pose (SCRUM-23)")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        return String.format(Locale.US, "%d octets (%.1f Ko)", bytes, kb)
    }

    private fun onSecondTick() {
        val stats = runStats ?: return
        stats.refreshWarmupState()

        val fps = stats.instantFps()
        val avgFps = stats.avgFpsRun()
        val latMed = stats.latencyMedianMs()
        val latP95 = stats.latencyP95Ms()
        val dropped = stats.droppedSinceLastTick()
        val temp = batteryMonitor.temperatureCelsius()
        val thermalLabel = ThermalMonitor.statusLabel(thermalMonitor.currentStatus)
        val battPct = batteryMonitor.batteryPercent()
        val likelihood = stats.currentAvgLikelihood()
        val angle = stats.lastKneeAngle()
        val angleStd = stats.currentAngleStdDev()
        val tSeconds = stats.elapsedSeconds()

        stats.addSecondRecord(
            SecondRecord(
                tSeconds = tSeconds,
                fps = fps,
                latencyMedianMs = latMed,
                latencyP95Ms = latP95,
                framesDropped = dropped,
                batteryTempC = temp,
                thermalStatus = thermalLabel,
                batteryPct = battPct,
                likelihoodAvg = likelihood,
                kneeAngleDeg = if (angle.isNaN()) 0.0 else angle,
                angleStdDevDeg = angleStd
            )
        )

        val warmupSuffix = if (stats.warmupActive) {
            "  [ÉCHAUFFEMENT ${(stats.warmupRemainingMs() / 1000) + 1}s]"
        } else {
            ""
        }
        val angleText = if (angle.isNaN()) "--" else String.format(Locale.US, "%.1f", angle)

        hudText.text = buildString {
            append("Config: ${currentConfig.label}$warmupSuffix\n")
            append(String.format(Locale.US, "FPS %.1f (moy %.1f)  lat med %.0fms p95 %.0fms\n", fps, avgFps, latMed, latP95))
            append(String.format(Locale.US, "temp %.1f°C | %s | batt %d%% | t=%s\n", temp, thermalLabel, battPct, formatElapsed(tSeconds)))
            append(String.format(Locale.US, "angle genou D: %s° | écart-type %.1f°", angleText, angleStd))
        }
    }

    private fun formatElapsed(totalSeconds: Long): String {
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())

    private fun fileTimestampNow(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}
