package fr.upjv.bodyvision.pose.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val DELTA = 0.01

/**
 * Image **carrée** à dessein, et c'est le seul changement que ce ticket apporte aux tests
 * existants.
 *
 * Les angles attendus ci-dessous ont été calculés à la main dans un repère isotrope, où une unité
 * de x vaut une unité de y. Les déclarer dans une image carrée les garde donc exacts à la
 * virgule près, sans qu'aucune valeur attendue ne bouge. L'anisotropie, elle, a son propre test :
 * celui qui donne une image 480x640 à des segments obliques.
 */
private val SQUARE_IMAGE = UprightImageSize(width = 640, height = 640)

class PoseGeometryTest {
    @Test
    fun `un angle droit vaut 90 degres`() {
        val pose =
            poseWith(
                PoseLandmarkType.RIGHT_HIP to (0.5f to 0.3f),
                PoseLandmarkType.RIGHT_KNEE to (0.5f to 0.5f),
                PoseLandmarkType.RIGHT_ANKLE to (0.7f to 0.5f),
            )

        assertEquals(90.0, rightKneeAngle(pose), DELTA)
    }

    @Test
    fun `trois points alignes valent 180 degres`() {
        val pose =
            poseWith(
                PoseLandmarkType.RIGHT_HIP to (0.3f to 0.5f),
                PoseLandmarkType.RIGHT_KNEE to (0.5f to 0.5f),
                PoseLandmarkType.RIGHT_ANKLE to (0.7f to 0.5f),
            )

        assertEquals(180.0, rightKneeAngle(pose), DELTA)
    }

    @Test
    fun `un segment entierement replie vaut 0 degre`() {
        val pose =
            poseWith(
                PoseLandmarkType.RIGHT_HIP to (0.3f to 0.5f),
                PoseLandmarkType.RIGHT_KNEE to (0.5f to 0.5f),
                PoseLandmarkType.RIGHT_ANKLE to (0.4f to 0.5f),
            )

        assertEquals(0.0, rightKneeAngle(pose), DELTA)
    }

    @Test
    fun `un angle rentrant est ramene sous 180 degres`() {
        // Les deux segments partent à +135° et -135° : l'écart brut vaut 270°, et c'est
        // l'angle saillant de 90° qui doit être rendu.
        val pose =
            poseWith(
                PoseLandmarkType.RIGHT_HIP to (0.42929f to 0.57071f),
                PoseLandmarkType.RIGHT_KNEE to (0.5f to 0.5f),
                PoseLandmarkType.RIGHT_ANKLE to (0.42929f to 0.42929f),
            )

        assertEquals(90.0, rightKneeAngle(pose), DELTA)
    }

    @Test
    fun `un segment de longueur nulle rend null car l angle est indefini`() {
        // Hanche et genou confondus : le segment hanche-genou n'a pas de direction. Rendre
        // 0° annoncerait « complètement plié » sur un membre que la caméra ne voit pas.
        val pose =
            poseWith(
                PoseLandmarkType.RIGHT_HIP to (0.5f to 0.5f),
                PoseLandmarkType.RIGHT_KNEE to (0.5f to 0.5f),
                PoseLandmarkType.RIGHT_ANKLE to (0.6f to 0.5f),
            )

        assertNull(rawRightKneeAngle(pose))
    }

    @Test
    fun `une coordonnee non finie rend null plutot qu un angle`() {
        // Ce qu'une division par une dimension d'image nulle produirait dans le mappeur.
        val pose =
            poseWith(
                PoseLandmarkType.RIGHT_HIP to (Float.NaN to Float.NaN),
                PoseLandmarkType.RIGHT_KNEE to (0.5f to 0.5f),
                PoseLandmarkType.RIGHT_ANKLE to (0.7f to 0.5f),
            )

        assertNull(rawRightKneeAngle(pose))
    }

    @Test
    fun `l angle d une geometrie quelconque vaut la meme valeur dans les deux sens`() {
        // Calcul à la main, sommet au genou (0,53 ; 0,64) :
        //   vers la hanche   (0,41 ; 0,22) : atan2(-0,42 ; -0,12) = -105,945°
        //   vers la cheville (0,78 ; 0,37) : atan2(-0,27 ; +0,25) =  -47,201°
        //   écart = -58,743°, valeur absolue 58,74°, inférieure à 180° donc rendue telle quelle.
        val pose =
            poseWith(
                PoseLandmarkType.RIGHT_HIP to (0.41f to 0.22f),
                PoseLandmarkType.RIGHT_KNEE to (0.53f to 0.64f),
                PoseLandmarkType.RIGHT_ANKLE to (0.78f to 0.37f),
            )

        assertEquals(58.74, rightKneeAngle(pose), DELTA)
        assertEquals(
            58.74,
            angleOf(pose, PoseLandmarkType.RIGHT_ANKLE, PoseLandmarkType.RIGHT_KNEE, PoseLandmarkType.RIGHT_HIP),
            DELTA,
        )
    }

    @Test
    fun `un point hors du cadre est utilise tel quel sans etre ramene dans 0 a 1`() {
        // La cheville est à droite du cadre (x = 1,5). Sommet au genou (0,5 ; 0,5) :
        //   vers la hanche   (0,5 ; 0,1) : atan2(-0,4 ; 0,0) = -90,000°
        //   vers la cheville (1,5 ; 0,9) : atan2(+0,4 ; 1,0) = +21,801°
        //   écart = -111,801°, soit 111,80°.
        // Un coerceIn(0f, 1f) dans le mappeur ramènerait la cheville à (1,0 ; 0,9), ce qui
        // donnerait 128,66° : ce test est ce qui attrape une telle « propreté ».
        val pose =
            poseWith(
                PoseLandmarkType.RIGHT_HIP to (0.5f to 0.1f),
                PoseLandmarkType.RIGHT_KNEE to (0.5f to 0.5f),
                PoseLandmarkType.RIGHT_ANKLE to (1.5f to 0.9f),
            )

        assertEquals(111.80, rightKneeAngle(pose), DELTA)
    }

    @Test
    fun `une hanche non detectee rend null`() {
        val pose =
            poseWith(
                PoseLandmarkType.RIGHT_KNEE to (0.5f to 0.5f),
                PoseLandmarkType.RIGHT_ANKLE to (0.7f to 0.5f),
            )

        assertNull(rawRightKneeAngle(pose))
    }

    @Test
    fun `un genou non detecte rend null`() {
        val pose =
            poseWith(
                PoseLandmarkType.RIGHT_HIP to (0.5f to 0.3f),
                PoseLandmarkType.RIGHT_ANKLE to (0.7f to 0.5f),
            )

        assertNull(rawRightKneeAngle(pose))
    }

    @Test
    fun `une cheville non detectee rend null`() {
        val pose =
            poseWith(
                PoseLandmarkType.RIGHT_HIP to (0.5f to 0.3f),
                PoseLandmarkType.RIGHT_KNEE to (0.5f to 0.5f),
            )

        assertNull(rawRightKneeAngle(pose))
    }

    @Test
    fun `une pose sans aucun point rend null`() {
        assertNull(
            rawRightKneeAngle(
                PoseFrame(landmarks = emptyMap(), imageSize = SQUARE_IMAGE, timestampMs = 0L),
            ),
        )
    }

    @Test
    fun `un angle droit oblique vaut 90 degres dans une image non carree`() {
        // Sommet au centre d'une image 480x640, les deux autres points à (+100, +100) et
        // (+100, -100) pixels de lui : 90° vrais, par construction.
        //
        // C'est le test qui échoue sur la version qui faisait son atan2 sur des x divisés par 480
        // et des y divisés par 640 : les deux segments y paraissaient à ±36,87° de l'horizontale
        // au lieu de ±45°, et la fonction rendait 73,74°. Les angles des tests précédents, eux,
        // sont alignés sur les axes ou calculés dans un repère carré, donc insensibles au défaut.
        val vertical = 100f / 640f
        val horizontal = 100f / 480f
        val pose =
            poseIn(
                UprightImageSize(width = 480, height = 640),
                PoseLandmarkType.RIGHT_HIP to (0.5f + horizontal to 0.5f + vertical),
                PoseLandmarkType.RIGHT_KNEE to (0.5f to 0.5f),
                PoseLandmarkType.RIGHT_ANKLE to (0.5f + horizontal to 0.5f - vertical),
            )

        assertEquals(90.0, rightKneeAngle(pose), DELTA)
    }
}

private fun poseWith(vararg points: Pair<PoseLandmarkType, Pair<Float, Float>>): PoseFrame =
    poseIn(SQUARE_IMAGE, *points)

private fun poseIn(
    imageSize: UprightImageSize,
    vararg points: Pair<PoseLandmarkType, Pair<Float, Float>>,
): PoseFrame =
    PoseFrame(
        landmarks =
            points.associate { (type, position) ->
                type to NormalizedLandmark(x = position.first, y = position.second, z = 0f, visibility = 1f)
            },
        imageSize = imageSize,
        timestampMs = 0L,
    )

private fun rawRightKneeAngle(pose: PoseFrame): Double? =
    angleDegrees(pose, PoseLandmarkType.RIGHT_HIP, PoseLandmarkType.RIGHT_KNEE, PoseLandmarkType.RIGHT_ANKLE)

private fun rightKneeAngle(pose: PoseFrame): Double =
    angleOf(pose, PoseLandmarkType.RIGHT_HIP, PoseLandmarkType.RIGHT_KNEE, PoseLandmarkType.RIGHT_ANKLE)

/** Déplie le résultat sans `!!` : un null ici est un échec de test, avec un message qui le dit. */
private fun angleOf(
    pose: PoseFrame,
    first: PoseLandmarkType,
    vertex: PoseLandmarkType,
    second: PoseLandmarkType,
): Double =
    requireNotNull(angleDegrees(pose, first, vertex, second)) {
        "angleDegrees a rendu null alors que $first, $vertex et $second sont tous présents"
    }
