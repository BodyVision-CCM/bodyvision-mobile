package fr.upjv.bodyvision

import android.util.Size
import com.google.mlkit.vision.pose.PoseDetectorOptionsBase
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions

/**
 * L'accélération "la meilleure des deux précédentes" (configs 3 et 4, cf. CLAUDE.md) ne peut
 * être connue qu'après dépouillement des runs des configs 1 et 2 sur le Honor 400. Choix exposé
 * dans l'interface (spinner) plutôt que figé en dur, pour pouvoir tester les deux sans recompiler.
 */
enum class AccelerationChoice(val label: String) {
    DEFAULT("Accélération config 3/4 : Défaut"),
    CPU_GPU("Accélération config 3/4 : CPU_GPU")
}

/** Les 4 configurations à comparer, cf. CLAUDE.md. */
enum class PoseConfig(
    val fileTag: String,
    val label: String,
    val analysisResolution: Size,
    private val optionsFactory: (AccelerationChoice) -> PoseDetectorOptionsBase
) {
    CONFIG_1_BASE_DEFAULT(
        fileTag = "config1_base_defaut_1280x720",
        label = "1 - Base / défaut / 1280x720",
        analysisResolution = Size(1280, 720),
        optionsFactory = {
            PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptionsBase.STREAM_MODE)
                .build()
        }
    ),
    CONFIG_2_BASE_CPU_GPU(
        fileTag = "config2_base_cpugpu_1280x720",
        label = "2 - Base / CPU_GPU / 1280x720",
        analysisResolution = Size(1280, 720),
        optionsFactory = {
            PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptionsBase.STREAM_MODE)
                .setPreferredHardwareConfigs(PoseDetectorOptionsBase.CPU_GPU)
                .build()
        }
    ),
    CONFIG_3_BASE_BEST_640(
        fileTag = "config3_base_meilleure_640x480",
        label = "3 - Base / meilleure accel / 640x480",
        analysisResolution = Size(640, 480),
        optionsFactory = { acceleration ->
            val builder = PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptionsBase.STREAM_MODE)
            if (acceleration == AccelerationChoice.CPU_GPU) {
                builder.setPreferredHardwareConfigs(PoseDetectorOptionsBase.CPU_GPU)
            }
            builder.build()
        }
    ),
    CONFIG_4_ACCURATE_BEST_1280(
        fileTag = "config4_accurate_meilleure_1280x720",
        label = "4 - Accurate / meilleure accel / 1280x720",
        analysisResolution = Size(1280, 720),
        optionsFactory = { acceleration ->
            val builder = AccuratePoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptionsBase.STREAM_MODE)
            if (acceleration == AccelerationChoice.CPU_GPU) {
                builder.setPreferredHardwareConfigs(PoseDetectorOptionsBase.CPU_GPU)
            }
            builder.build()
        }
    );

    /** [accelerationForBestConfigs] n'est appliqué que par les configs 3 et 4. */
    fun buildOptions(accelerationForBestConfigs: AccelerationChoice): PoseDetectorOptionsBase =
        optionsFactory(accelerationForBestConfigs)
}
