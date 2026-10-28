package fr.upjv.bodyvision.session.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La table de décision est le piège du ticket SCRUM-31 : avant la première demande,
 * `shouldShowRequestPermissionRationale` est faux exactement comme après un refus définitif.
 * Un test par comportement, nommé par la raison de son échec.
 */
class CameraPermissionStatusTest {
    @Test
    fun `une permission accordee est accordee quel que soit le reste`() {
        // isGranted doit court-circuiter : c'est l'ordre des branches qui est testé ici, donc
        // les quatre combinaisons des deux autres entrées.
        listOf(false to false, false to true, true to false, true to true).forEach { (rationale, asked) ->
            assertStatus(
                CameraPermissionStatus.GRANTED,
                isGranted = true,
                canShowRationale = rationale,
                hasBeenAskedOnce = asked,
            )
        }
    }

    @Test
    fun `une permission jamais demandee reste inconnue`() {
        // Les deux valeurs de canShowRationale : avant la première demande, elle ne veut rien
        // dire, et elle ne doit donc pas peser sur le résultat.
        assertStatus(
            CameraPermissionStatus.UNKNOWN,
            isGranted = false,
            canShowRationale = false,
            hasBeenAskedOnce = false,
        )
        assertStatus(
            CameraPermissionStatus.UNKNOWN,
            isGranted = false,
            canShowRationale = true,
            hasBeenAskedOnce = false,
        )
    }

    @Test
    fun `un refus avec explication encore possible est un refus simple`() {
        assertStatus(
            CameraPermissionStatus.DENIED,
            isGranted = false,
            canShowRationale = true,
            hasBeenAskedOnce = true,
        )
    }

    @Test
    fun `un refus sans explication possible devient definitif a la deuxieme occurrence`() {
        assertStatus(
            CameraPermissionStatus.PERMANENTLY_DENIED,
            isGranted = false,
            canShowRationale = false,
            hasBeenAskedOnce = true,
            ambiguousDenialCount = 2,
        )
    }

    @Test
    fun `un dialogue ferme sans reponse n-est pas encore un blocage`() {
        // Le cas 8 du plan de test : le geste retour renvoie « non accordé » sans explication à
        // montrer, exactement comme un blocage réel. Une occurrence seule doit rester un refus
        // simple, sinon l'écran envoie changer un réglage que personne n'a changé.
        val afterCancel = nextAmbiguousDenialCount(0, isGranted = false, canShowRationale = false)

        assertStatus(
            CameraPermissionStatus.DENIED,
            isGranted = false,
            canShowRationale = false,
            hasBeenAskedOnce = true,
            ambiguousDenialCount = afterCancel,
        )
    }

    @Test
    fun `deux occurrences consecutives de la combinaison ambigue bloquent`() {
        // Le blocage réel : la demande revient refusée aussitôt, sans dialogue, donc deux fois
        // de suite.
        var count = nextAmbiguousDenialCount(0, isGranted = false, canShowRationale = false)
        count = nextAmbiguousDenialCount(count, isGranted = false, canShowRationale = false)

        assertStatus(
            CameraPermissionStatus.PERMANENTLY_DENIED,
            isGranted = false,
            canShowRationale = false,
            hasBeenAskedOnce = true,
            ambiguousDenialCount = count,
        )
    }

    @Test
    fun `un refus explicite remet le compteur a zero`() {
        // canShowRationale vrai veut dire que le dialogue a bien été montré et refusé : il n'y a
        // plus rien d'ambigu à cumuler.
        val afterCancel = nextAmbiguousDenialCount(0, isGranted = false, canShowRationale = false)
        val afterDenial = nextAmbiguousDenialCount(afterCancel, isGranted = false, canShowRationale = true)

        assertEquals("compteur apres un refus explicite", 0, afterDenial)
        assertStatus(
            CameraPermissionStatus.DENIED,
            isGranted = false,
            canShowRationale = true,
            hasBeenAskedOnce = true,
            ambiguousDenialCount = afterDenial,
        )
    }

    @Test
    fun `une permission accordee apres une annulation remet le compteur a zero`() {
        val afterCancel = nextAmbiguousDenialCount(0, isGranted = false, canShowRationale = false)
        val afterGrant = nextAmbiguousDenialCount(afterCancel, isGranted = true, canShowRationale = false)

        assertEquals("compteur apres une permission accordee", 0, afterGrant)
        assertStatus(
            CameraPermissionStatus.GRANTED,
            isGranted = true,
            canShowRationale = false,
            hasBeenAskedOnce = true,
            ambiguousDenialCount = afterGrant,
        )
    }
}

/** Nomme les quatre entrées dans le message : sans ça, un échec ne dit pas quelle ligne a bougé. */
private fun assertStatus(
    expected: CameraPermissionStatus,
    isGranted: Boolean,
    canShowRationale: Boolean,
    hasBeenAskedOnce: Boolean,
    ambiguousDenialCount: Int = 0,
) {
    assertEquals(
        "accordee=$isGranted explication=$canShowRationale deja demandee=$hasBeenAskedOnce " +
            "ambigus=$ambiguousDenialCount",
        expected,
        resolveCameraPermissionStatus(isGranted, canShowRationale, hasBeenAskedOnce, ambiguousDenialCount),
    )
}
