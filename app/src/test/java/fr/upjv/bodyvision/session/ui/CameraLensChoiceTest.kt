package fr.upjv.bodyvision.session.ui

import fr.upjv.bodyvision.camera.data.CameraLens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Le repli d'objectif, qui est la logique à risque de la bascule : une seule de ses branches qui
 * se tromperait mènerait à l'écran « caméra indisponible » pour un appareil en parfait état, ou
 * détruirait le choix enregistré.
 */
class CameraLensChoiceTest {
    @Test
    fun `le choix enregistre est garde quand l'appareil l'a`() {
        assertEquals(
            CameraLens.FRONT,
            resolveCameraLens(CameraLens.FRONT, setOf(CameraLens.BACK, CameraLens.FRONT)),
        )
    }

    @Test
    fun `aucun objectif connu ne donne aucun objectif a lier`() {
        // Pas de repli déguisé sur l'arrière : on ne sait pas encore, et l'écran doit attendre
        // plutôt que de lier une caméra pour la délier 200 ms plus tard.
        assertNull(resolveCameraLens(CameraLens.FRONT, emptySet()))
    }

    @Test
    fun `un choix impossible retombe sur la camera arriere`() {
        // Le cas d'un état restauré venu d'un autre appareil, ou d'une caméra désactivée par
        // politique : il se corrige, il ne se signale pas comme une panne.
        assertEquals(CameraLens.BACK, resolveCameraLens(CameraLens.FRONT, setOf(CameraLens.BACK)))
    }

    @Test
    fun `un appareil sans camera arriere garde l'objectif qui reste`() {
        // Ici le repli sur l'arrière serait un objectif absent, donc un écran de panne pour un
        // appareil qui a bel et bien une caméra.
        assertEquals(CameraLens.FRONT, resolveCameraLens(CameraLens.BACK, setOf(CameraLens.FRONT)))
    }
}
