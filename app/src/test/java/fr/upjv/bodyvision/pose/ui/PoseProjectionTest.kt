package fr.upjv.bodyvision.pose.ui

import fr.upjv.bodyvision.pose.domain.UprightImageSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tolérance d'un calcul en virgule flottante, sans rapport avec un seuil métier. */
private const val DELTA = 1e-3f

/** L'image redressée du projet en portrait : 640x480 analysé, axes échangés par la rotation. */
private val IMAGE = UprightImageSize(width = 480, height = 640)

/**
 * La projection `FILL_CENTER`, sans Compose ni appareil.
 *
 * C'est la fonction qui décide si le squelette tombe sur le corps ou à côté, et elle est pure :
 * elle se teste donc entièrement sur la JVM, là où la vérification sur l'appareil ne dit que
 * « ça a l'air bon ».
 */
class PoseProjectionTest {
    @Test
    fun `un meme rapport d'aspect ne recadre rien`() {
        // Vue deux fois l'image, même rapport : les deux échelles valent 2, donc aucun débord et
        // aucun décalage. L'image remplit exactement la vue.
        val topLeft = project(0f, 0f, viewWidth = 960f, viewHeight = 1280f)
        val bottomRight = project(1f, 1f, viewWidth = 960f, viewHeight = 1280f)

        assertEquals(0f, topLeft.x, DELTA)
        assertEquals(0f, topLeft.y, DELTA)
        assertEquals(960f, bottomRight.x, DELTA)
        assertEquals(1280f, bottomRight.y, DELTA)
    }

    @Test
    fun `une vue plus large que l'image la recadre en haut et en bas`() {
        // Vue carrée 1000x1000 pour une image en 3:4. L'échelle retenue est celle de la largeur
        // (2,083), donc la hauteur dessinée fait 1333 px pour 1000 disponibles : 167 px sortent
        // en haut et autant en bas. Avec le `min`, on aurait deux bandes vides à la place.
        val top = project(0f, 0f, viewWidth = 1000f, viewHeight = 1000f)
        val bottom = project(1f, 1f, viewWidth = 1000f, viewHeight = 1000f)

        assertEquals("la largeur est remplie", 0f, top.x, DELTA)
        assertEquals("la largeur est remplie", 1000f, bottom.x, DELTA)
        assertEquals(-166.667f, top.y, DELTA)
        assertEquals(1166.667f, bottom.y, DELTA)
    }

    @Test
    fun `une vue plus haute que l'image la recadre a gauche et a droite`() {
        // Vue 480x1000 : l'échelle retenue est celle de la hauteur (1,5625), donc la largeur
        // dessinée fait 750 px pour 480 disponibles, et 135 px sortent de chaque côté.
        val left = project(0f, 0f, viewWidth = 480f, viewHeight = 1000f)
        val right = project(1f, 1f, viewWidth = 480f, viewHeight = 1000f)

        assertEquals(-135f, left.x, DELTA)
        assertEquals(615f, right.x, DELTA)
        assertEquals("la hauteur est remplie", 0f, left.y, DELTA)
        assertEquals("la hauteur est remplie", 1000f, right.y, DELTA)
    }

    @Test
    fun `le centre de l'image tombe au centre de la vue malgre le recadrage`() {
        // Le débord est centré, donc le milieu reste le milieu. C'est ce qui attrape un décalage
        // oublié : avec un décalage à zéro, ce point partirait vers le haut de la vue.
        val center = project(0.5f, 0.5f, viewWidth = 1000f, viewHeight = 1000f)

        assertEquals(500f, center.x, DELTA)
        assertEquals(500f, center.y, DELTA)
    }

    @Test
    fun `le miroir echange les deux moities de la vue sans toucher a la hauteur`() {
        // Un point à 0,2 vu en miroir doit se poser là où se poserait un point à 0,8 sans
        // miroir : c'est la définition d'un retournement horizontal autour du milieu.
        val mirrored = project(0.2f, 0.3f, viewWidth = 960f, viewHeight = 1280f, isMirrored = true)
        val expected = project(0.8f, 0.3f, viewWidth = 960f, viewHeight = 1280f)

        assertEquals(expected.x, mirrored.x, DELTA)
        assertEquals("le miroir ne touche pas l'axe vertical", expected.y, mirrored.y, DELTA)
    }

    @Test
    fun `un point hors cadre se projette hors de la vue sans etre borne`() {
        // ML Kit continue d'estimer un poignet sorti du cadre. Le borner le poserait sur le bord
        // de l'écran, au milieu d'un segment, et le squelette aurait l'air cassé plutôt
        // qu'incomplet.
        val beyondRight = project(1.25f, 0.5f, viewWidth = 960f, viewHeight = 1280f)
        val beforeLeft = project(-0.1f, 0.5f, viewWidth = 960f, viewHeight = 1280f)

        assertEquals(1200f, beyondRight.x, DELTA)
        assertEquals(-96f, beforeLeft.x, DELTA)
        assertTrue("au-delà du bord droit", beyondRight.x > 960f)
        assertTrue("avant le bord gauche", beforeLeft.x < 0f)
    }
}

private fun project(
    normalizedX: Float,
    normalizedY: Float,
    viewWidth: Float,
    viewHeight: Float,
    isMirrored: Boolean = false,
): ViewPoint =
    projectOnView(
        normalizedX = normalizedX,
        normalizedY = normalizedY,
        imageSize = IMAGE,
        viewWidth = viewWidth,
        viewHeight = viewHeight,
        isMirrored = isMirrored,
    )
