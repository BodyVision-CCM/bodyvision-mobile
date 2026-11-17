package fr.upjv.bodyvision.telemetry.domain

import fr.upjv.bodyvision.pose.domain.NormalizedLandmark
import fr.upjv.bodyvision.pose.domain.PoseFrame
import fr.upjv.bodyvision.pose.domain.PoseLandmarkType
import fr.upjv.bodyvision.pose.domain.UprightImageSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Un ordre quelconque des 33 types suffit à cet accumulateur : il ne fait que lire la map dans cet
 * ordre. Que l'ordre soit bien celui des indices ML Kit est le sujet de `MlKitLandmarkOrderTest`,
 * et il se teste là-bas — ici, s'appuyer sur la vraie table ne prouverait rien de plus et rendrait
 * ce test dépendant d'une bibliothèque.
 */
private val LANDMARK_ORDER = PoseLandmarkType.entries.toList()

private val LANDSCAPE_SIZE = UprightImageSize(width = 640, height = 480)
private val PORTRAIT_SIZE = UprightImageSize(width = 480, height = 640)

/** Période réelle de l'appareil cible : 29,4 images par seconde mesurées en SCRUM-22, soit 34 ms. */
private const val DEVICE_FRAME_PERIOD_MS = 34L

/** L'horloge murale de la plupart des tests : une valeur fixe, la série n'en lit qu'une. */
private const val FIXED_WALL_CLOCK_MS = 1_700_000_000_000L

private fun recorder(wallClock: () -> Long = { FIXED_WALL_CLOCK_MS }) =
    SeriesRecorder(batchId = "serie-de-test", landmarkOrder = LANDMARK_ORDER, wallClock = wallClock)

private fun landmark(
    x: Float = 0.5f,
    y: Float = 0.5f,
    z: Float = 0f,
    visibility: Float = 1f,
) = NormalizedLandmark(x = x, y = y, z = z, visibility = visibility)

private fun poseFrame(
    timestampMs: Long,
    imageSize: UprightImageSize = LANDSCAPE_SIZE,
    landmarks: Map<PoseLandmarkType, NormalizedLandmark> = LANDMARK_ORDER.associateWith { landmark() },
) = PoseFrame(landmarks = landmarks, imageSize = imageSize, timestampMs = timestampMs)

/** Une seconde de flux régulier à la période donnée, et le nombre d'images retenues. */
private fun keptFrameCountOverOneSecond(framePeriodMs: Long): Int {
    val recorder = recorder()
    var timestampMs = 0L
    while (timestampMs < 1_000L) {
        recorder.accept(poseFrame(timestampMs))
        timestampMs += framePeriodMs
    }
    return recorder.keptFrameCount
}

/**
 * L'accumulateur d'une série : ce qu'il garde, ce qu'il écarte, et quand il se ferme. Du Kotlin
 * pur, donc éprouvable sans caméra, sans ML Kit et sans ordonnanceur — c'est tout l'intérêt de
 * l'avoir sorti de la couche Android.
 */
class SeriesRecorderTest {
    @Test
    fun `une entree a trente-quatre millisecondes garde dix images par seconde`() {
        // La cadence réelle de l'appareil cible : 29,4 images par seconde mesurées en SCRUM-22.
        assertEquals(10, keptFrameCountOverOneSecond(DEVICE_FRAME_PERIOD_MS))
    }

    @Test
    fun `une entree a trente-trois millisecondes garde aussi dix images par seconde`() {
        // Le cas que l'ancienne règle ratait, et la raison d'être de l'échéance : trois périodes de
        // 33 ms ne font que 99 ms, donc « au moins 100 ms depuis la dernière gardée » en gardait une
        // sur quatre et tombait à 7,6 par seconde — pour un `nominalFps` qui annonce 10, soit 24 %
        // d'écart dont le serveur tirerait des vitesses fausses. L'échéance, elle, ne dérive pas :
        // elle avance de 100 ms exactement, quelle que soit la période d'entrée.
        assertEquals(10, keptFrameCountOverOneSecond(33L))
    }

    @Test
    fun `un flux irregulier ne garde qu'une image par echeance`() {
        val recorder = recorder()

        // Un flux irrégulier autour de 15 images par seconde : c'est ce que donne l'appareil sous
        // chauffe.
        val timestampsMs = listOf(0L, 60L, 130L, 190L, 270L, 330L, 400L, 480L, 550L, 610L, 700L)
        timestampsMs.forEach { recorder.accept(poseFrame(it)) }

        // Une image pour la première échéance, puis une par tranche de 100 ms franchie : 7 échéances
        // sur 700 ms, donc 8 images. C'est ce qui plafonne le débit à 10 par seconde.
        //
        // Deux écarts y tombent sous 100 ms — 270 → 330 et 550 → 610 — et c'est la contrepartie
        // assumée de l'échéance : quand une image arrive en retard sur sa tranche, la suivante
        // revient sur l'horaire au lieu de décaler tout le reste. Les `t` restent strictement
        // croissants, et chaque image porte le sien, donc rien n'est faussé en aval : seule la
        // régularité de l'échantillonnage l'est.
        assertEquals(
            listOf(0L, 130L, 270L, 330L, 400L, 550L, 610L, 700L),
            recorder.complete()?.frames?.map { it.offsetMs },
        )
    }

    @Test
    fun `la premiere image est toujours gardee`() {
        val recorder = recorder()

        // Horodatage arbitrairement grand : il est relatif au démarrage de l'appareil, donc une
        // série qui commence après deux jours d'allumage part d'un très grand nombre.
        recorder.accept(poseFrame(timestampMs = 172_800_000L))

        assertEquals(1, recorder.keptFrameCount)
    }

    @Test
    fun `le decalage part de zero et croit strictement`() {
        val recorder = recorder()

        listOf(5_000L, 5_130L, 5_270L, 5_400L).forEach { recorder.accept(poseFrame(it)) }

        val offsetsMs = recorder.complete()?.frames?.map { it.offsetMs }
        assertEquals(listOf(0L, 130L, 270L, 400L), offsetsMs)
    }

    @Test
    fun `un horodatage en double ou en recul n'est pas garde`() {
        val recorder = recorder()

        recorder.accept(poseFrame(timestampMs = 0L))
        recorder.accept(poseFrame(timestampMs = 200L))
        // Les deux cas qui feraient un `t` non croissant, et le contrat l'interdit. Ils arrivent
        // tous deux après une image gardée, parce que c'est là qu'ils sont dangereux : l'échéance
        // rendue est toujours strictement supérieure à l'horodatage gardé, donc ni l'un ni l'autre
        // n'est dû.
        recorder.accept(poseFrame(timestampMs = 200L))
        recorder.accept(poseFrame(timestampMs = 150L))

        assertEquals(2, recorder.keptFrameCount)
    }

    @Test
    fun `une image sans personne est ecartee et comptee`() {
        val recorder = recorder()

        recorder.accept(poseFrame(timestampMs = 0L, landmarks = emptyMap()))

        assertEquals(0, recorder.keptFrameCount)
        assertEquals(1, recorder.discardedFrameCount)
    }

    @Test
    fun `une image a trente-deux points est ecartee sans qu'aucun point soit invente`() {
        val recorder = recorder()
        val incompleteLandmarks =
            LANDMARK_ORDER.associateWith { landmark() } - PoseLandmarkType.LEFT_WRIST

        recorder.accept(poseFrame(timestampMs = 0L, landmarks = incompleteLandmarks))

        // Ni zéro, ni répétition du point précédent : l'image entière est écartée. Une série sans
        // image gardée ne produit aucun fichier.
        assertEquals(1, recorder.discardedFrameCount)
        assertNull(recorder.complete())
    }

    @Test
    fun `une coordonnee non finie fait ecarter l'image`() {
        val recorder = recorder()
        val landmarksWithNaN =
            LANDMARK_ORDER.associateWith { landmark() } +
                (PoseLandmarkType.NOSE to landmark(x = Float.NaN))

        recorder.accept(poseFrame(timestampMs = 0L, landmarks = landmarksWithNaN))

        // `NaN` n'a pas de syntaxe en JSON : une seule valeur suffirait à rendre tout le fichier
        // illisible.
        assertEquals(1, recorder.discardedFrameCount)
        assertEquals(0, recorder.keptFrameCount)
    }

    @Test
    fun `une visibilite infinie fait ecarter l'image`() {
        val recorder = recorder()
        val landmarksWithInfinity =
            LANDMARK_ORDER.associateWith { landmark() } +
                (PoseLandmarkType.NOSE to landmark(visibility = Float.POSITIVE_INFINITY))

        recorder.accept(poseFrame(timestampMs = 0L, landmarks = landmarksWithInfinity))

        assertEquals(1, recorder.discardedFrameCount)
    }

    @Test
    fun `un changement de taille d'image termine la serie sur l'image precedente`() {
        val recorder = recorder()

        recorder.accept(poseFrame(timestampMs = 0L, imageSize = LANDSCAPE_SIZE))
        recorder.accept(poseFrame(timestampMs = 200L, imageSize = LANDSCAPE_SIZE))
        val status = recorder.accept(poseFrame(timestampMs = 400L, imageSize = PORTRAIT_SIZE))

        assertEquals(SeriesStatus.CLOSED, status)
        val series = recorder.complete()
        assertEquals(2, series?.frames?.size)
        // Le contrat ne porte qu'une taille par série : c'est celle des images qui y sont.
        assertEquals(LANDSCAPE_SIZE, series?.imageSize)
    }

    @Test
    fun `la neuf-centieme image gardee termine la serie`() {
        val recorder = recorder()

        var timestampMs = 0L
        repeat(MAX_SERIES_FRAMES) {
            recorder.accept(poseFrame(timestampMs))
            timestampMs += SAMPLE_PERIOD_MS
        }
        val statusAfterLimit = recorder.accept(poseFrame(timestampMs))

        assertEquals(SeriesStatus.CLOSED, statusAfterLimit)
        // `frames.maxItems` du contrat : une image de plus ferait refuser le lot entier.
        assertEquals(MAX_SERIES_FRAMES, recorder.keptFrameCount)
    }

    @Test
    fun `un accumulateur qui n'a vu aucune image ne rend aucune serie`() {
        assertNull(recorder().complete())
    }

    @Test
    fun `l'horloge murale est lue a la premiere image gardee`() {
        var readCount = 0
        val recorder =
            recorder(
                wallClock = {
                    readCount++
                    1_000L * readCount
                },
            )

        // La première image est écartée : elle ne doit pas fixer `startedAt`, sans quoi la série
        // commencerait avant sa première image réelle.
        recorder.accept(poseFrame(timestampMs = 0L, landmarks = emptyMap()))
        recorder.accept(poseFrame(timestampMs = 0L))
        recorder.accept(poseFrame(timestampMs = 200L))

        assertEquals(1_000L, recorder.complete()?.startedAtEpochMs)
        // Une seule lecture : `startedAt` est l'instant de la première image gardée, et de rien
        // d'autre.
        assertEquals(1, readCount)
    }

    @Test
    fun `un accumulateur clos reste clos`() {
        val recorder = recorder()
        recorder.accept(poseFrame(timestampMs = 0L))
        assertNotNull(recorder.complete())

        val status = recorder.accept(poseFrame(timestampMs = 200L))

        // À usage unique : un accumulateur réutilisé pour un second enregistrement ne produirait
        // pas une série mélangée, il ne produirait rien.
        assertEquals(SeriesStatus.CLOSED, status)
        assertEquals(1, recorder.keptFrameCount)
    }
}
