package fr.upjv.bodyvision

import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.abs
import kotlin.math.atan2

/** Les connexions du squelette dessinées à l'écran (33 landmarks ML Kit). */
val POSE_CONNECTIONS: List<Pair<Int, Int>> = listOf(
    PoseLandmark.NOSE to PoseLandmark.LEFT_EYE_INNER,
    PoseLandmark.LEFT_EYE_INNER to PoseLandmark.LEFT_EYE,
    PoseLandmark.LEFT_EYE to PoseLandmark.LEFT_EYE_OUTER,
    PoseLandmark.LEFT_EYE_OUTER to PoseLandmark.LEFT_EAR,
    PoseLandmark.NOSE to PoseLandmark.RIGHT_EYE_INNER,
    PoseLandmark.RIGHT_EYE_INNER to PoseLandmark.RIGHT_EYE,
    PoseLandmark.RIGHT_EYE to PoseLandmark.RIGHT_EYE_OUTER,
    PoseLandmark.RIGHT_EYE_OUTER to PoseLandmark.RIGHT_EAR,
    PoseLandmark.LEFT_MOUTH to PoseLandmark.RIGHT_MOUTH,
    PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
    PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_ELBOW,
    PoseLandmark.LEFT_ELBOW to PoseLandmark.LEFT_WRIST,
    PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW,
    PoseLandmark.RIGHT_ELBOW to PoseLandmark.RIGHT_WRIST,
    PoseLandmark.LEFT_WRIST to PoseLandmark.LEFT_THUMB,
    PoseLandmark.LEFT_WRIST to PoseLandmark.LEFT_PINKY,
    PoseLandmark.LEFT_WRIST to PoseLandmark.LEFT_INDEX,
    PoseLandmark.LEFT_INDEX to PoseLandmark.LEFT_PINKY,
    PoseLandmark.RIGHT_WRIST to PoseLandmark.RIGHT_THUMB,
    PoseLandmark.RIGHT_WRIST to PoseLandmark.RIGHT_PINKY,
    PoseLandmark.RIGHT_WRIST to PoseLandmark.RIGHT_INDEX,
    PoseLandmark.RIGHT_INDEX to PoseLandmark.RIGHT_PINKY,
    PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_HIP,
    PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_HIP,
    PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP,
    PoseLandmark.LEFT_HIP to PoseLandmark.LEFT_KNEE,
    PoseLandmark.LEFT_KNEE to PoseLandmark.LEFT_ANKLE,
    PoseLandmark.RIGHT_HIP to PoseLandmark.RIGHT_KNEE,
    PoseLandmark.RIGHT_KNEE to PoseLandmark.RIGHT_ANKLE,
    PoseLandmark.LEFT_ANKLE to PoseLandmark.LEFT_HEEL,
    PoseLandmark.LEFT_HEEL to PoseLandmark.LEFT_FOOT_INDEX,
    PoseLandmark.LEFT_ANKLE to PoseLandmark.LEFT_FOOT_INDEX,
    PoseLandmark.RIGHT_ANKLE to PoseLandmark.RIGHT_HEEL,
    PoseLandmark.RIGHT_HEEL to PoseLandmark.RIGHT_FOOT_INDEX,
    PoseLandmark.RIGHT_ANKLE to PoseLandmark.RIGHT_FOOT_INDEX
)

/** Landmarks du côté droit utilisés pour la métrique de précision (inFrameLikelihood moyen). */
private val RIGHT_SIDE_LANDMARKS = listOf(
    PoseLandmark.RIGHT_SHOULDER,
    PoseLandmark.RIGHT_HIP,
    PoseLandmark.RIGHT_KNEE,
    PoseLandmark.RIGHT_ANKLE
)

/** Angle au genou droit (hanche-genou-cheville) en degrés, via atan2. Null si un point manque. */
fun rightKneeAngleDegrees(pose: Pose): Double? {
    val hip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP) ?: return null
    val knee = pose.getPoseLandmark(PoseLandmark.RIGHT_KNEE) ?: return null
    val ankle = pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE) ?: return null

    val angleHipKnee = atan2(
        (hip.position.y - knee.position.y).toDouble(),
        (hip.position.x - knee.position.x).toDouble()
    )
    val angleAnkleKnee = atan2(
        (ankle.position.y - knee.position.y).toDouble(),
        (ankle.position.x - knee.position.x).toDouble()
    )
    var degrees = Math.toDegrees(angleHipKnee - angleAnkleKnee)
    degrees = abs(degrees)
    if (degrees > 180.0) degrees = 360.0 - degrees
    return degrees
}

/** Moyenne de inFrameLikelihood sur épaule/hanche/genou/cheville droits. */
fun rightSideAvgLikelihood(pose: Pose): Double {
    val likelihoods = RIGHT_SIDE_LANDMARKS.mapNotNull { pose.getPoseLandmark(it)?.inFrameLikelihood }
    return if (likelihoods.isEmpty()) 0.0 else likelihoods.average()
}
