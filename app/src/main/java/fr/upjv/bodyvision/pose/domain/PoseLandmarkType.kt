package fr.upjv.bodyvision.pose.domain

/**
 * Les 33 points du squelette. L'ordre de déclaration n'a aucune signification : la
 * correspondance avec les constantes entières de ML Kit est établie explicitement dans la
 * couche données, pour que le domaine ne dépende pas de leur numérotation.
 */
enum class PoseLandmarkType {
    NOSE,
    LEFT_EYE_INNER,
    LEFT_EYE,
    LEFT_EYE_OUTER,
    RIGHT_EYE_INNER,
    RIGHT_EYE,
    RIGHT_EYE_OUTER,
    LEFT_EAR,
    RIGHT_EAR,
    LEFT_MOUTH,
    RIGHT_MOUTH,
    LEFT_SHOULDER,
    RIGHT_SHOULDER,
    LEFT_ELBOW,
    RIGHT_ELBOW,
    LEFT_WRIST,
    RIGHT_WRIST,
    LEFT_PINKY,
    RIGHT_PINKY,
    LEFT_INDEX,
    RIGHT_INDEX,
    LEFT_THUMB,
    RIGHT_THUMB,
    LEFT_HIP,
    RIGHT_HIP,
    LEFT_KNEE,
    RIGHT_KNEE,
    LEFT_ANKLE,
    RIGHT_ANKLE,
    LEFT_HEEL,
    RIGHT_HEEL,
    LEFT_FOOT_INDEX,
    RIGHT_FOOT_INDEX,
}
