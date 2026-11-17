package fr.upjv.bodyvision.telemetry.data

import com.google.mlkit.vision.pose.PoseLandmark
import fr.upjv.bodyvision.pose.data.landmarkTypesByMlKitType
import fr.upjv.bodyvision.telemetry.domain.LANDMARK_COUNT
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * L'ordre dans lequel le tableau `lm` du contrat est écrit.
 *
 * Testable sur la JVM bien que ce fichier parte de ML Kit : les constantes de [PoseLandmark] sont
 * des `static final int`, donc inlinées à la compilation, et aucune classe de la bibliothèque n'est
 * chargée. C'est déjà ce qui rend `PoseLandmarkMapperTest` possible.
 */
class MlKitLandmarkOrderTest {
    @Test
    fun `l'ordre contient les trente-trois points sans doublon`() {
        assertEquals(LANDMARK_COUNT, mlKitLandmarkOrder.size)
        assertEquals(LANDMARK_COUNT, mlKitLandmarkOrder.toSet().size)
    }

    @Test
    fun `chaque point est a la position de son indice ML Kit`() {
        // Ce test n'est pas une tautologie, et c'est ce qui le rend utile : la liste est triée par
        // clé, donc « la position vaut l'indice » n'est vrai que si les indices de ML Kit sont
        // exactement 0 à 32, sans trou. C'est une propriété d'une bibliothèque en beta, pas une
        // garantie — un indice 33 ajouté, ou une valeur sautée, décalerait tout le tableau `lm`
        // sans qu'une seule ligne de notre code change.
        listOf(
            PoseLandmark.NOSE,
            PoseLandmark.LEFT_SHOULDER,
            PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_HIP,
            PoseLandmark.RIGHT_FOOT_INDEX,
        ).forEach { mlKitType ->
            assertEquals(
                "indice $mlKitType",
                landmarkTypesByMlKitType[mlKitType],
                mlKitLandmarkOrder[mlKitType],
            )
        }
    }
}
