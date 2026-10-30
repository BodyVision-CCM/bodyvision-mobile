package fr.upjv.bodyvision.pose.data

import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import fr.upjv.bodyvision.pose.domain.NormalizedLandmark
import fr.upjv.bodyvision.pose.domain.PoseFrame
import fr.upjv.bodyvision.pose.domain.PoseLandmarkType

private const val NANOS_PER_MILLI = 1_000_000L
private const val QUARTER_TURN_DEGREES = 90
private const val THREE_QUARTER_TURN_DEGREES = 270

/**
 * Taille de l'image **redressée**, c'est-à-dire telle que ML Kit la voit après la rotation
 * qu'on lui passe : c'est dans ce repère que sortent les coordonnées des landmarks, donc c'est
 * par celle-là qu'il faut diviser.
 */
data class UprightImageSize(
    val width: Int,
    val height: Int,
)

/**
 * Dimensions de l'image une fois redressée : échangées pour un quart de tour, gardées pour une
 * rotation droite.
 *
 * Rend `null` pour une image dont une dimension est nulle — ou négative, qui est la même
 * absurdité. C'est un rejet, pas une exception : le domaine rend `null` plutôt que de lever
 * (voir l'invariant de [NormalizedLandmark]), et un `require` dans une boucle à 30 images par
 * seconde arrêterait la détection pour une image abîmée. Rien n'est journalisé ici : cette
 * fonction doit rester testable sur la JVM, donc sans `android.util.Log`. C'est
 * [MlKitPoseSource] qui journalise le rejet, avec les dimensions reçues.
 */
fun uprightImageSizeOf(
    imageWidth: Int,
    imageHeight: Int,
    rotationDegrees: Int,
): UprightImageSize? {
    if (imageWidth <= 0 || imageHeight <= 0) return null

    val isQuarterTurn =
        rotationDegrees == QUARTER_TURN_DEGREES || rotationDegrees == THREE_QUARTER_TURN_DEGREES
    return if (isQuarterTurn) {
        UprightImageSize(width = imageHeight, height = imageWidth)
    } else {
        UprightImageSize(width = imageWidth, height = imageHeight)
    }
}

/**
 * Pixels de l'image redressée → coordonnées relatives à sa taille.
 *
 * **Aucun bornage**, et c'est le point délicat : ML Kit continue d'estimer un point qui sort du
 * cadre, donc une coordonnée hors de 0..1 est un cas normal. La ramener dans l'intervalle
 * déplacerait le point et déformerait silencieusement les angles.
 *
 * [z] est divisé par la **largeur** et non par la hauteur : le domaine demande une profondeur
 * « dans la même échelle que x ».
 *
 * Pas de retournement de l'axe x : les coordonnées restent dans le repère de l'image. Un
 * éventuel effet miroir, si la caméra frontale arrive un jour, est une affaire d'affichage.
 */
fun normalizedLandmarkOf(
    x: Float,
    y: Float,
    z: Float,
    visibility: Float,
    size: UprightImageSize,
): NormalizedLandmark =
    NormalizedLandmark(
        x = x / size.width,
        y = y / size.height,
        z = z / size.width,
        visibility = visibility,
    )

/** `ImageInfo.timestamp` vient de `CaptureResult.SENSOR_TIMESTAMP`, donc en nanosecondes. */
fun timestampMsOf(timestampNanos: Long): Long = timestampNanos / NANOS_PER_MILLI

/**
 * Les 33 constantes entières de ML Kit vers les types du domaine, **explicitement**.
 *
 * Jamais par `ordinal` : le domaine dit que l'ordre de déclaration de son enum n'a aucune
 * signification, et s'appuyer sur la numérotation de ML Kit la rendrait silencieusement
 * dépendante d'une bibliothèque en beta.
 *
 * Les constantes de [PoseLandmark] sont des `static final int`, donc inlinées à la
 * compilation : cette table se lit depuis un test JVM sans charger une classe ML Kit.
 */
internal val landmarkTypesByMlKitType: Map<Int, PoseLandmarkType> =
    mapOf(
        PoseLandmark.NOSE to PoseLandmarkType.NOSE,
        PoseLandmark.LEFT_EYE_INNER to PoseLandmarkType.LEFT_EYE_INNER,
        PoseLandmark.LEFT_EYE to PoseLandmarkType.LEFT_EYE,
        PoseLandmark.LEFT_EYE_OUTER to PoseLandmarkType.LEFT_EYE_OUTER,
        PoseLandmark.RIGHT_EYE_INNER to PoseLandmarkType.RIGHT_EYE_INNER,
        PoseLandmark.RIGHT_EYE to PoseLandmarkType.RIGHT_EYE,
        PoseLandmark.RIGHT_EYE_OUTER to PoseLandmarkType.RIGHT_EYE_OUTER,
        PoseLandmark.LEFT_EAR to PoseLandmarkType.LEFT_EAR,
        PoseLandmark.RIGHT_EAR to PoseLandmarkType.RIGHT_EAR,
        PoseLandmark.LEFT_MOUTH to PoseLandmarkType.LEFT_MOUTH,
        PoseLandmark.RIGHT_MOUTH to PoseLandmarkType.RIGHT_MOUTH,
        PoseLandmark.LEFT_SHOULDER to PoseLandmarkType.LEFT_SHOULDER,
        PoseLandmark.RIGHT_SHOULDER to PoseLandmarkType.RIGHT_SHOULDER,
        PoseLandmark.LEFT_ELBOW to PoseLandmarkType.LEFT_ELBOW,
        PoseLandmark.RIGHT_ELBOW to PoseLandmarkType.RIGHT_ELBOW,
        PoseLandmark.LEFT_WRIST to PoseLandmarkType.LEFT_WRIST,
        PoseLandmark.RIGHT_WRIST to PoseLandmarkType.RIGHT_WRIST,
        PoseLandmark.LEFT_PINKY to PoseLandmarkType.LEFT_PINKY,
        PoseLandmark.RIGHT_PINKY to PoseLandmarkType.RIGHT_PINKY,
        PoseLandmark.LEFT_INDEX to PoseLandmarkType.LEFT_INDEX,
        PoseLandmark.RIGHT_INDEX to PoseLandmarkType.RIGHT_INDEX,
        PoseLandmark.LEFT_THUMB to PoseLandmarkType.LEFT_THUMB,
        PoseLandmark.RIGHT_THUMB to PoseLandmarkType.RIGHT_THUMB,
        PoseLandmark.LEFT_HIP to PoseLandmarkType.LEFT_HIP,
        PoseLandmark.RIGHT_HIP to PoseLandmarkType.RIGHT_HIP,
        PoseLandmark.LEFT_KNEE to PoseLandmarkType.LEFT_KNEE,
        PoseLandmark.RIGHT_KNEE to PoseLandmarkType.RIGHT_KNEE,
        PoseLandmark.LEFT_ANKLE to PoseLandmarkType.LEFT_ANKLE,
        PoseLandmark.RIGHT_ANKLE to PoseLandmarkType.RIGHT_ANKLE,
        PoseLandmark.LEFT_HEEL to PoseLandmarkType.LEFT_HEEL,
        PoseLandmark.RIGHT_HEEL to PoseLandmarkType.RIGHT_HEEL,
        PoseLandmark.LEFT_FOOT_INDEX to PoseLandmarkType.LEFT_FOOT_INDEX,
        PoseLandmark.RIGHT_FOOT_INDEX to PoseLandmarkType.RIGHT_FOOT_INDEX,
    )

/**
 * La frontière : ML Kit entre, [PoseFrame] sort. Fonction d'extension parce que la conversion
 * appartient à l'appelant et non au type converti — c'est ce qui garde `com.google.mlkit` dans
 * `pose/data`.
 *
 * Un point qu'on ne sait pas nommer est une **clé absente**, jamais une valeur nulle : c'est le
 * contrat de [PoseFrame], et il interdit du même coup tout `!!` chez les appelants. Une image
 * sans personne donne une pose à `landmarks` vide, ce qui est un résultat et pas une erreur.
 *
 * Non testable sur la JVM : [Pose] et [PoseLandmark] ne se construisent pas sans appareil. Un
 * test qui simulerait ces types ne testerait que la simulation.
 */
fun Pose.toPoseFrame(
    size: UprightImageSize,
    timestampNanos: Long,
): PoseFrame {
    val landmarks =
        allPoseLandmarks.mapNotNull { landmark ->
            val type = landmarkTypesByMlKitType[landmark.landmarkType] ?: return@mapNotNull null
            val position = landmark.position3D
            type to
                normalizedLandmarkOf(
                    x = position.x,
                    y = position.y,
                    z = position.z,
                    visibility = landmark.inFrameLikelihood,
                    size = size,
                )
        }
    return PoseFrame(landmarks = landmarks.toMap(), timestampMs = timestampMsOf(timestampNanos))
}
