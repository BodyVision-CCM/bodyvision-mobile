package fr.upjv.bodyvision.telemetry.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Le fuseau du contrat : les deux horodatages sont en UTC, d'où le `Z` final du motif. */
private const val UTC_ZONE_ID = "UTC"

/**
 * Millisecondes depuis l'epoch → l'horodatage ISO 8601 en UTC qu'attendent `startedAt` et
 * `endedAt`.
 *
 * **`SimpleDateFormat` et non `java.time`**, qui serait le choix normal aujourd'hui :
 * `Instant` et `DateTimeFormatter` demandent l'API 26, ce projet descend à 24, et le desugaring de
 * la bibliothèque standard qui les rendrait disponibles est une dépendance de plus — interdite ici.
 * Le lint refuserait la compilation sur `NewApi`.
 *
 * Trois précautions dans trois lignes, et chacune corrige un vrai défaut :
 * - **`Locale.ROOT`** : sans elle, un calendrier local autre que grégorien — l'hégirien d'un
 *   téléphone réglé en `ar-SA` — changerait l'année écrite ;
 * - **le fuseau UTC** : sans lui, le format écrirait l'heure locale sous une étiquette `Z`, donc
 *   une heure fausse de deux heures en France l'été ;
 * - **une instance locale à chaque appel** : `SimpleDateFormat` n'est pas sûr entre fils
 *   d'exécution, et une instance partagée rendrait des dates mélangées sans jamais lever.
 *
 * Le motif ne contient aucun champ dépendant de la langue — ni nom de mois, ni nom de jour — donc
 * la sortie est la même partout. C'est ce qu'un test vérifie en forçant une locale et un fuseau.
 */
fun isoUtcTimestampOf(epochMillis: Long): String {
    val format = SimpleDateFormat(ISO_8601_UTC_PATTERN, Locale.ROOT)
    format.timeZone = TimeZone.getTimeZone(UTC_ZONE_ID)
    return format.format(Date(epochMillis))
}
