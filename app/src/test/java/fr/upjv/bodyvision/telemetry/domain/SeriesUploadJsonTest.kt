package fr.upjv.bodyvision.telemetry.domain

import fr.upjv.bodyvision.pose.domain.NormalizedLandmark
import fr.upjv.bodyvision.pose.domain.UprightImageSize
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** Un UUID fixe : le `batchId` réel est tiré au hasard, donc il ne peut pas être dans un oracle. */
private const val TEST_BATCH_ID = "11111111-2222-3333-4444-555555555555"

/** Mille milliards de millisecondes depuis l'epoch, soit le 09/09/2001 à 01:46:40 UTC. */
private const val TEST_STARTED_AT_MS = 1_000_000_000_000L

private val TEST_IMAGE_SIZE = UprightImageSize(width = 640, height = 480)

/**
 * Le même point 33 fois : l'oracle du document complet ne tient qu'à cette condition, et ce qu'il
 * éprouve est la **forme** du document, pas la variété des nombres. L'ordre du tableau, lui, est le
 * sujet de `MlKitLandmarkOrderTest`.
 */
private val TEST_LANDMARK = NormalizedLandmark(x = 0.1f, y = 0.2f, z = -0.3f, visibility = 0.9f)

private const val TEST_LANDMARK_JSON = "[0.100,0.200,-0.300,0.900]"

private val TEST_LM_JSON = List(LANDMARK_COUNT) { TEST_LANDMARK_JSON }.joinToString(",")

private fun seriesOf(
    frames: List<TelemetryFrame>,
    landmark: NormalizedLandmark = TEST_LANDMARK,
): TelemetrySeries {
    val landmarks = List(LANDMARK_COUNT) { landmark }
    return TelemetrySeries(
        batchId = TEST_BATCH_ID,
        startedAtEpochMs = TEST_STARTED_AT_MS,
        imageSize = TEST_IMAGE_SIZE,
        frames = frames.map { it.copy(landmarks = landmarks) },
    )
}

private fun frameAt(offsetMs: Long) = TelemetryFrame(offsetMs = offsetMs, landmarks = emptyList())

/**
 * L'encodeur du corps `SeriesUpload`.
 *
 * Le test de locale est le plus important du fichier : la locale par défaut est exactement ce qui
 * produirait un fichier invalide sans qu'aucune erreur n'apparaisse sur le téléphone.
 */
class SeriesUploadJsonTest {
    private lateinit var defaultLocale: Locale

    @Before
    fun rememberDefaultLocale() {
        defaultLocale = Locale.getDefault()
    }

    @After
    fun restoreDefaultLocale() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `une petite serie connue redonne exactement le document attendu`() {
        val expected =
            """{"batchId":"$TEST_BATCH_ID","index":0,"exerciseCode":"SQUAT",""" +
                """"startedAt":"2001-09-09T01:46:40.000Z","endedAt":"2001-09-09T01:46:40.000Z",""" +
                // `1.0` écrit en dur, et c'est volontaire : un oracle décrit ce que le **contrat**
                // impose, pas ce que l'implémentation produit. Interpoler la constante ferait
                // passer ce test quelle que soit sa valeur, alors que changer la version du format
                // doit être un geste délibéré — qui touche le contrat, la constante **et** ce test.
                """"contractVersion":"1.0","capture":{"landmarkSet":"MLKIT_POSE_33","model":"BASE",""" +
                """"analysisWidth":640,"analysisHeight":480,"nominalFps":10},""" +
                """"frames":[{"t":0,"lm":[$TEST_LM_JSON]}]}"""

        assertEquals(expected, encodeSeriesUpload(seriesOf(listOf(frameAt(0L)))))
    }

    @Test
    fun `sous une locale francaise les nombres gardent un point decimal`() {
        // Le piège : sans `Locale.ROOT` dans l'encodeur, cette seule ligne suffirait à rendre le
        // fichier invalide — `0,100` n'est pas un nombre JSON — et rien sur l'appareil ne le
        // signalerait, puisque l'écriture réussirait.
        Locale.setDefault(Locale.FRANCE)

        val json = encodeSeriesUpload(seriesOf(listOf(frameAt(0L))))

        assertTrue("point décimal attendu", json.contains("[0.100,0.200,"))
        assertFalse("aucune virgule décimale", json.contains("0,100"))
    }

    @Test
    fun `une coordonnee est ecrite a trois decimales`() {
        // Règle d'arrondi, constatée et non devinée : `%.3f` arrondit au plus près et à l'écart de
        // zéro en cas d'égalité, mais sur la valeur **flottante réellement représentée**. 0,1235f
        // vaut 0,12349999696… en binaire, donc il descend à 0.123 au lieu de monter à 0.124, là où
        // 0,1236f monte bien. L'écart est d'un millième sur une coordonnée normalisée, soit moins
        // d'un pixel : sans conséquence ici, mais à savoir avant de comparer deux fichiers.
        val landmark = NormalizedLandmark(x = 0.12345f, y = 0.1235f, z = 0.1236f, visibility = 0.0005f)

        val json = encodeSeriesUpload(seriesOf(listOf(frameAt(0L)), landmark = landmark))

        assertTrue(json, json.contains("[0.123,0.123,0.124,0.001]"))
    }

    @Test
    fun `une coordonnee hors cadre est gardee telle quelle`() {
        // ML Kit continue d'estimer un point sorti du cadre, et le contrat l'admet : borner
        // déplacerait le point, donc fausserait l'angle calculé plus tard.
        val landmark = NormalizedLandmark(x = 1.25f, y = -0.1f, z = -1.5f, visibility = 0f)

        val json = encodeSeriesUpload(seriesOf(listOf(frameAt(0L)), landmark = landmark))

        assertTrue(json, json.contains("[1.250,-0.100,-1.500,0.000]"))
    }

    @Test
    fun `la fin de serie est le debut plus le decalage de la derniere image`() {
        // Les horodatages des images sont relatifs au démarrage de l'appareil : la seule façon d'en
        // tirer une heure murale est de les ajouter à celle de la première image gardée.
        val json = encodeSeriesUpload(seriesOf(listOf(frameAt(0L), frameAt(1_234L))))

        assertTrue(json, json.contains("\"endedAt\":\"2001-09-09T01:46:41.234Z\""))
    }

    @Test
    fun `le document ne contient rien qui demanderait un echappement`() {
        val json = encodeSeriesUpload(seriesOf(listOf(frameAt(0L))))

        // Le jeu de caractères autorisés : le souligné pour `MLKIT_POSE_33`, les tirets pour l'UUID
        // et les dates. Il attrape le non-ASCII — une date rendue en chiffres arabes-indiens, par
        // exemple — mais **pas** un guillemet au milieu d'une valeur, puisqu'un guillemet est aussi
        // un caractère de structure. D'où les deux comptes qui suivent.
        assertTrue(json, Regex("""^[0-9A-Za-z_{}\[\]":,.\-]+$""").matches(json))

        // 22 chaînes dans ce document d'**une seule image** : 15 noms de champs et 7 valeurs — le
        // `batchId`, `SQUAT`, les deux horodatages, la version du contrat, `MLKIT_POSE_33` et
        // `BASE` — donc exactement 44 guillemets, tous de structure. Chaque image supplémentaire en
        // ajouterait 4 (`"t"` et `"lm"`), donc ce 44 va avec le vecteur et non avec le format. Un guillemet entré dans une valeur en ferait 45, et un
        // guillemet déjà échappé serait attrapé par le second compte : aucune barre oblique inverse
        // n'a de raison d'exister ici.
        //
        // Ce compte tombe aussi le jour où un champ est ajouté au document, et c'est voulu : il
        // faut alors relire d'où viennent les chaînes, et décider s'il faut échapper.
        assertEquals(44, Regex("(?<!\\\\)\"").findAll(json).count())
        assertEquals(0, json.count { it == '\\' })
    }
}
