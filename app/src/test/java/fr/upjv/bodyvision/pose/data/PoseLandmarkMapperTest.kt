package fr.upjv.bodyvision.pose.data

import com.google.mlkit.vision.pose.PoseLandmark
import fr.upjv.bodyvision.pose.domain.PoseLandmarkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tolérance d'une division en virgule flottante, sans rapport avec un seuil métier. */
private const val DELTA = 1e-6f

/**
 * La partie pure du mappeur : des dimensions et des pixels en entrée, des coordonnées relatives
 * ou `null` en sortie. Pas une ligne d'Android, donc testable sur la JVM.
 *
 * La conversion depuis les types ML Kit n'est pas testée ici, et c'est volontaire : `Pose` et
 * `PoseLandmark` ne se construisent pas sans appareil, et un test qui les simulerait ne
 * testerait que la simulation.
 */
class PoseLandmarkMapperTest {
    @Test
    fun `une rotation droite garde les dimensions de l'image`() {
        // 0 et 180 : l'image reste en paysage, donc 640x480 comme la sortie de l'analyse.
        listOf(0, 180).forEach { rotationDegrees ->
            assertEquals(
                "rotation $rotationDegrees",
                UprightImageSize(width = 640, height = 480),
                uprightImageSizeOf(imageWidth = 640, imageHeight = 480, rotationDegrees = rotationDegrees),
            )
        }
    }

    @Test
    fun `un quart de tour echange les dimensions de l'image`() {
        // 90 et 270 : ML Kit redresse l'image, donc les landmarks sortent dans un repère 480x640.
        // Diviser par les dimensions d'origine déplacerait chaque point.
        listOf(90, 270).forEach { rotationDegrees ->
            assertEquals(
                "rotation $rotationDegrees",
                UprightImageSize(width = 480, height = 640),
                uprightImageSizeOf(imageWidth = 640, imageHeight = 480, rotationDegrees = rotationDegrees),
            )
        }
    }

    @Test
    fun `une image dont une dimension est nulle est rejetee sans exception`() {
        // Le rejet est un résultat, pas une erreur : lever ici arrêterait la détection.
        assertNull("largeur nulle", uprightImageSizeOf(imageWidth = 0, imageHeight = 480, rotationDegrees = 0))
        assertNull("hauteur nulle", uprightImageSizeOf(imageWidth = 640, imageHeight = 0, rotationDegrees = 0))
    }

    @Test
    fun `une coordonnee hors cadre ressort hors de 0 a 1 sans etre bornee`() {
        // ML Kit continue d'estimer un point sorti du cadre : c'est un cas normal, et le borner
        // ramènerait le point sur le bord, ce qui déformerait les angles sans aucun signe.
        val size = UprightImageSize(width = 640, height = 480)

        val beyondRight = normalizedLandmarkOf(x = 800f, y = 240f, z = 0f, visibility = 0.4f, size = size)
        val beforeLeft = normalizedLandmarkOf(x = -64f, y = 240f, z = 0f, visibility = 0.4f, size = size)

        assertEquals(1.25f, beyondRight.x, DELTA)
        assertEquals(-0.1f, beforeLeft.x, DELTA)
        assertTrue("au-delà du bord droit", beyondRight.x > 1f)
        assertTrue("avant le bord gauche", beforeLeft.x < 0f)
    }

    @Test
    fun `z est divise par la largeur et non par la hauteur`() {
        // Le domaine demande une profondeur « dans la même échelle que x ». Diviser par la
        // hauteur donnerait ici 0,25 au lieu de 0,1875, soit un tiers de trop.
        val landmark =
            normalizedLandmarkOf(
                x = 320f,
                y = 240f,
                z = 120f,
                visibility = 0.9f,
                size = UprightImageSize(width = 640, height = 480),
            )

        assertEquals(0.1875f, landmark.z, DELTA)
    }

    @Test
    fun `l'horodatage passe des nanosecondes aux millisecondes`() {
        // `ImageInfo.timestamp` vient de `CaptureResult.SENSOR_TIMESTAMP`, en nanosecondes.
        assertEquals(1_234L, timestampMsOf(1_234_567_890L))
    }

    @Test
    fun `chaque constante ML Kit pointe le type du domaine de meme nom`() {
        // L'oracle est volontairement indépendant de la table : la constante est lue par
        // réflexion à partir du nom du type, donc une inversion gauche-droite ne peut pas se
        // cacher derrière un comptage d'entrées — compter 33 valeurs distinctes est vrai de
        // n'importe quelle permutation. Les constantes `PoseLandmark.*` ne sont pas citées ici :
        // le test referait sinon exactement l'erreur du code qu'il surveille.
        //
        // Ce que le compte vérifie encore, et que la boucle ne couvre pas : une entrée en trop,
        // pour une constante qui n'existe pas dans le domaine.
        assertEquals("nombre d'entrées", PoseLandmarkType.entries.size, landmarkTypesByMlKitType.size)

        PoseLandmarkType.entries.forEach { type ->
            val mlKitType = PoseLandmark::class.java.getField(type.name).getInt(null)
            assertEquals(
                "constante ML Kit ${type.name} = $mlKitType",
                type,
                landmarkTypesByMlKitType[mlKitType],
            )
        }
    }
}
