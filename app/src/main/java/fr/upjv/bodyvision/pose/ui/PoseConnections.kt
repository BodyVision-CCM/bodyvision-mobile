package fr.upjv.bodyvision.pose.ui

import fr.upjv.bodyvision.pose.domain.PoseLandmarkType

/**
 * Les segments du squelette : les mêmes paires que `POSE_CONNECTIONS` du spike SCRUM-22, mais
 * exprimées en [PoseLandmarkType] et non avec les constantes entières de ML Kit, pour que rien
 * en dehors de `pose/data` ne dépende de leur numérotation.
 *
 * Ici et non dans le domaine : aujourd'hui seul le dessin s'en sert. Si une règle d'alerte en a
 * besoin un jour, elle descendra d'un cran à ce moment-là, pas avant.
 *
 * Une paire dont un point manque dans la pose n'est simplement pas dessinée : c'est le contrat de
 * `PoseFrame`, où un point non détecté est une clé absente.
 */
internal val POSE_CONNECTIONS: List<Pair<PoseLandmarkType, PoseLandmarkType>> =
    listOf(
        PoseLandmarkType.NOSE to PoseLandmarkType.LEFT_EYE_INNER,
        PoseLandmarkType.LEFT_EYE_INNER to PoseLandmarkType.LEFT_EYE,
        PoseLandmarkType.LEFT_EYE to PoseLandmarkType.LEFT_EYE_OUTER,
        PoseLandmarkType.LEFT_EYE_OUTER to PoseLandmarkType.LEFT_EAR,
        PoseLandmarkType.NOSE to PoseLandmarkType.RIGHT_EYE_INNER,
        PoseLandmarkType.RIGHT_EYE_INNER to PoseLandmarkType.RIGHT_EYE,
        PoseLandmarkType.RIGHT_EYE to PoseLandmarkType.RIGHT_EYE_OUTER,
        PoseLandmarkType.RIGHT_EYE_OUTER to PoseLandmarkType.RIGHT_EAR,
        PoseLandmarkType.LEFT_MOUTH to PoseLandmarkType.RIGHT_MOUTH,
        PoseLandmarkType.LEFT_SHOULDER to PoseLandmarkType.RIGHT_SHOULDER,
        PoseLandmarkType.LEFT_SHOULDER to PoseLandmarkType.LEFT_ELBOW,
        PoseLandmarkType.LEFT_ELBOW to PoseLandmarkType.LEFT_WRIST,
        PoseLandmarkType.RIGHT_SHOULDER to PoseLandmarkType.RIGHT_ELBOW,
        PoseLandmarkType.RIGHT_ELBOW to PoseLandmarkType.RIGHT_WRIST,
        PoseLandmarkType.LEFT_WRIST to PoseLandmarkType.LEFT_THUMB,
        PoseLandmarkType.LEFT_WRIST to PoseLandmarkType.LEFT_PINKY,
        PoseLandmarkType.LEFT_WRIST to PoseLandmarkType.LEFT_INDEX,
        PoseLandmarkType.LEFT_INDEX to PoseLandmarkType.LEFT_PINKY,
        PoseLandmarkType.RIGHT_WRIST to PoseLandmarkType.RIGHT_THUMB,
        PoseLandmarkType.RIGHT_WRIST to PoseLandmarkType.RIGHT_PINKY,
        PoseLandmarkType.RIGHT_WRIST to PoseLandmarkType.RIGHT_INDEX,
        PoseLandmarkType.RIGHT_INDEX to PoseLandmarkType.RIGHT_PINKY,
        PoseLandmarkType.LEFT_SHOULDER to PoseLandmarkType.LEFT_HIP,
        PoseLandmarkType.RIGHT_SHOULDER to PoseLandmarkType.RIGHT_HIP,
        PoseLandmarkType.LEFT_HIP to PoseLandmarkType.RIGHT_HIP,
        PoseLandmarkType.LEFT_HIP to PoseLandmarkType.LEFT_KNEE,
        PoseLandmarkType.LEFT_KNEE to PoseLandmarkType.LEFT_ANKLE,
        PoseLandmarkType.RIGHT_HIP to PoseLandmarkType.RIGHT_KNEE,
        PoseLandmarkType.RIGHT_KNEE to PoseLandmarkType.RIGHT_ANKLE,
        PoseLandmarkType.LEFT_ANKLE to PoseLandmarkType.LEFT_HEEL,
        PoseLandmarkType.LEFT_HEEL to PoseLandmarkType.LEFT_FOOT_INDEX,
        PoseLandmarkType.LEFT_ANKLE to PoseLandmarkType.LEFT_FOOT_INDEX,
        PoseLandmarkType.RIGHT_ANKLE to PoseLandmarkType.RIGHT_HEEL,
        PoseLandmarkType.RIGHT_HEEL to PoseLandmarkType.RIGHT_FOOT_INDEX,
        PoseLandmarkType.RIGHT_ANKLE to PoseLandmarkType.RIGHT_FOOT_INDEX,
    )
