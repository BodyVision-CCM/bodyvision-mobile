package fr.upjv.bodyvision.pose.data

import com.google.mlkit.vision.pose.PoseDetectorOptionsBase
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions

/**
 * L'unique configuration de détection du projet, figée par les mesures de SCRUM-22 sur le
 * Honor 400 : modèle de base, `STREAM_MODE`, accélération `CPU_GPU` — 29,4 FPS, latence
 * médiane 26,4 ms. Le modèle `accurate` a été écarté, et la dépendance avec lui.
 *
 * **La résolution d'analyse n'est pas ici** : les 640x480 appartiennent à
 * `CameraSessionBinder`, qui construit l'`ImageAnalysis`. La répéter ici donnerait deux
 * sources de vérité pour un seul réglage, et celle qui ne sert à rien finirait par mentir.
 *
 * Une fonction et non une constante : `PoseDetectorOptions` se construit par un `Builder`, et
 * un détecteur ne se partage pas entre deux sources.
 */
fun mlKitPoseDetectorOptions(): PoseDetectorOptionsBase =
    PoseDetectorOptions
        .Builder()
        .setDetectorMode(PoseDetectorOptionsBase.STREAM_MODE)
        .setPreferredHardwareConfigs(PoseDetectorOptionsBase.CPU_GPU)
        .build()
