package fr.upjv.bodyvision.pose.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val DELTA = 0.01

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
    fun `deux points confondus ne font pas diviser par zero`() {
        val pose =
            poseWith(
                PoseLandmarkType.RIGHT_HIP to (0.5f to 0.5f),
                PoseLandmarkType.RIGHT_KNEE to (0.5f to 0.5f),
                PoseLandmarkType.RIGHT_ANKLE to (0.6f to 0.5f),
            )

        assertEquals(0.0, rightKneeAngle(pose), DELTA)
    }

    @Test
    fun `l angle est le meme dans les deux sens de lecture`() {
        val pose =
            poseWith(
                PoseLandmarkType.RIGHT_HIP to (0.41f to 0.22f),
                PoseLandmarkType.RIGHT_KNEE to (0.53f to 0.64f),
                PoseLandmarkType.RIGHT_ANKLE to (0.78f to 0.37f),
            )

        assertEquals(
            rightKneeAngle(pose),
            angleOf(pose, PoseLandmarkType.RIGHT_ANKLE, PoseLandmarkType.RIGHT_KNEE, PoseLandmarkType.RIGHT_HIP),
            DELTA,
        )
    }

    @Test
    fun `un point non detecte rend null plutot qu un angle faux`() {
        val hip = PoseLandmarkType.RIGHT_HIP to (0.5f to 0.3f)
        val knee = PoseLandmarkType.RIGHT_KNEE to (0.5f to 0.5f)
        val ankle = PoseLandmarkType.RIGHT_ANKLE to (0.7f to 0.5f)

        assertNull(rawRightKneeAngle(poseWith(knee, ankle)))
        assertNull(rawRightKneeAngle(poseWith(hip, ankle)))
        assertNull(rawRightKneeAngle(poseWith(hip, knee)))
    }

    @Test
    fun `une pose sans aucun point rend null`() {
        assertNull(rawRightKneeAngle(PoseFrame(landmarks = emptyMap(), timestampMs = 0L)))
    }
}

private fun poseWith(vararg points: Pair<PoseLandmarkType, Pair<Float, Float>>): PoseFrame =
    PoseFrame(
        landmarks =
            points.associate { (type, position) ->
                type to NormalizedLandmark(x = position.first, y = position.second, z = 0f, visibility = 1f)
            },
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
