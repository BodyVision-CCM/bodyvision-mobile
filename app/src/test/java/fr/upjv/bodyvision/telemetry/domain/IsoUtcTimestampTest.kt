package fr.upjv.bodyvision.telemetry.domain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/** Mille milliards de millisecondes depuis l'epoch : le 09/09/2001 à 01:46:40 UTC. */
private const val ONE_TRILLION_MS = 1_000_000_000_000L

/** Le format des champs `startedAt` et `endedAt` du contrat. */
class IsoUtcTimestampTest {
    private lateinit var defaultLocale: Locale
    private lateinit var defaultTimeZone: TimeZone

    @Before
    fun rememberDefaults() {
        defaultLocale = Locale.getDefault()
        defaultTimeZone = TimeZone.getDefault()
    }

    @After
    fun restoreDefaults() {
        Locale.setDefault(defaultLocale)
        TimeZone.setDefault(defaultTimeZone)
    }

    @Test
    fun `un instant connu donne l'horodatage ISO 8601 attendu`() {
        assertEquals("1970-01-01T00:00:00.000Z", isoUtcTimestampOf(0L))
        assertEquals("2001-09-09T01:46:40.000Z", isoUtcTimestampOf(ONE_TRILLION_MS))
    }

    @Test
    fun `une locale et un fuseau locaux ne changent pas l'horodatage`() {
        // Les deux pièges d'un coup, et chacun fait tomber ce test si sa précaution disparaît de
        // `isoUtcTimestampOf` :
        // - sans fuseau explicite, le format écrirait 03:46:40 sous une étiquette `Z` — l'heure de
        //   Paris en été présentée comme de l'UTC, donc fausse de deux heures ;
        // - sans locale explicite, un calendrier non grégorien change l'année : la locale thaïe
        //   écrit **2544** au lieu de 2001 (constaté, pas supposé). `Locale.FRANCE` ne prouvait
        //   rien ici, puisque le français est grégorien et en chiffres arabes : le test passait
        //   même sans `Locale.ROOT`. En `ar-SA`, ce sont les chiffres eux-mêmes qui changent, donc
        //   une chaîne non ASCII dans un champ `date-time`.
        Locale.setDefault(Locale.forLanguageTag("th-TH-u-ca-buddhist"))
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))

        assertEquals("2001-09-09T01:46:40.000Z", isoUtcTimestampOf(ONE_TRILLION_MS))
    }
}
