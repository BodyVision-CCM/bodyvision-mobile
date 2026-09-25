package fr.upjv.bodyvision

import android.util.Size
import com.google.mlkit.vision.pose.PoseDetectorOptionsBase
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions

/**
 * Les 4 configurations à comparer, cf. CLAUDE.md.
 *
 * BEST_ACCELERATION : à ajuster manuellement une fois les runs des configs 1 et 2 dépouillés
 * (le ticket demande "la meilleure des deux précédentes", ce qui ne peut être connu qu'après
 * mesure sur le Honor 400). Valeur par défaut CPU_GPU, à corriger ici si le CPU seul s'avère
 * meilleur en pratique.
 */
private const val BEST_ACCELERATION = PoseDetectorOptionsBase.CPU_GPU

enum class PoseConfig(
    val fileTag: String,
    val label: String,
    val analysisResolution: Size,
    private val optionsFactory: () -> PoseDetectorOptionsBase
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
        optionsFactory = {
            PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptionsBase.STREAM_MODE)
                .setPreferredHardwareConfigs(BEST_ACCELERATION)
                .build()
        }
    ),
    CONFIG_4_ACCURATE_BEST_1280(
        fileTag = "config4_accurate_meilleure_1280x720",
        label = "4 - Accurate / meilleure accel / 1280x720",
        analysisResolution = Size(1280, 720),
        optionsFactory = {
            AccuratePoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptionsBase.STREAM_MODE)
                .setPreferredHardwareConfigs(BEST_ACCELERATION)
                .build()
        }
    );

    fun buildOptions(): PoseDetectorOptionsBase = optionsFactory()
}
