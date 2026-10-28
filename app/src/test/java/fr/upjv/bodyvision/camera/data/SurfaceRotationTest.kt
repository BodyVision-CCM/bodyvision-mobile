package fr.upjv.bodyvision.camera.data

import android.view.OrientationEventListener
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Les bornes sont l'essentiel de ces tests : c'est le seul endroit où une erreur est plausible,
 * et le code de rotation n'est observable sur l'appareil qu'à partir de SCRUM-32, quand une
 * image réellement tournée traversera la chaîne.
 */
class SurfaceRotationTest {
    @Test
    fun `une orientation inconnue ne donne aucune rotation`() {
        assertNull(surfaceRotationFor(OrientationEventListener.ORIENTATION_UNKNOWN))
    }

    @Test
    fun `le portrait donne ROTATION_0`() {
        // 315 et 44 sont les deux bornes du secteur, qui enjambe 0.
        assertRotation(Surface.ROTATION_0, 315, 330, 359, 0, 20, 44)
    }

    @Test
    fun `un quart de tour horaire donne ROTATION_270`() {
        assertRotation(Surface.ROTATION_270, 45, 90, 134)
    }

    @Test
    fun `le portrait inverse donne ROTATION_180`() {
        assertRotation(Surface.ROTATION_180, 135, 180, 224)
    }

    @Test
    fun `un quart de tour antihoraire donne ROTATION_90`() {
        assertRotation(Surface.ROTATION_90, 225, 270, 314)
    }

    @Test
    fun `un tour complet retombe sur le portrait`() {
        // 360 n'est pas dans le contrat de OrientationEventListener, qui s'arrête à 359. Le cas
        // est couvert pour que la borne haute ne devienne pas un trou si le contrat change.
        assertRotation(Surface.ROTATION_0, 360)
    }
}

/** Nomme le degré fautif dans le message : sans ça, un échec ne dit pas quelle borne a bougé. */
private fun assertRotation(
    expected: Int,
    vararg orientationsDegrees: Int,
) {
    orientationsDegrees.forEach { degrees ->
        assertEquals("orientation de $degrees degres", expected, surfaceRotationFor(degrees))
    }
}
