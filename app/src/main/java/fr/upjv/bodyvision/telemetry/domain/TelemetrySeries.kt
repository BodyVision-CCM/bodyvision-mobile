package fr.upjv.bodyvision.telemetry.domain

import fr.upjv.bodyvision.pose.domain.UprightImageSize

/**
 * Une série **terminée**, prête à être encodée. Immuable : c'est le résultat d'un enregistrement,
 * plus rien ne s'y ajoute.
 *
 * Ne porte que ce qui **varie** d'une série à l'autre. Les autres champs de `SeriesUpload` —
 * l'exercice, le rang, le jeu de points, le modèle, le débit nominal, la version du contrat — sont
 * des constantes du contrat, et c'est l'encodeur qui les écrit depuis `TelemetryContract`. Les
 * recopier dans chaque série n'ajouterait que des champs toujours égaux à eux-mêmes.
 *
 * [batchId] est l'UUID aléatoire tiré à la création de la série, et le contrat en fait aussi
 * l'identifiant de la série (`serie.id = batchId`). Au S4 il ne sert qu'à nommer le fichier ; sa
 * valeur de clé d'idempotence n'existera qu'avec l'envoi, au S6.
 *
 * [startedAtEpochMs] est l'**horloge murale** lue au moment de la première image gardée, en
 * millisecondes depuis l'epoch. C'est le seul instant absolu de la série : les horodatages des
 * images, eux, sont relatifs au démarrage de l'appareil et n'ont aucun sens hors de celui-ci. Le
 * `endedAt` du contrat s'en déduit, en ajoutant le `offsetMs` de la dernière image gardée.
 *
 * [imageSize] est la taille redressée **commune à toutes** les images de la série : c'est ce que le
 * contrat annonce une fois par série dans `capture.analysisWidth` et `analysisHeight`. Une image
 * d'une autre taille termine la série au lieu d'y entrer.
 *
 * [frames] contient **au moins une** image, et au plus [MAX_SERIES_FRAMES] : c'est
 * [SeriesRecorder.complete] qui le garantit, en ne rendant aucune série quand rien n'a été gardé.
 */
data class TelemetrySeries(
    val batchId: String,
    val startedAtEpochMs: Long,
    val imageSize: UprightImageSize,
    val frames: List<TelemetryFrame>,
)
