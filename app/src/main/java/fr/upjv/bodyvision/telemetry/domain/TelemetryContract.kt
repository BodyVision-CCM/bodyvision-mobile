package fr.upjv.bodyvision.telemetry.domain

/*
 * Les valeurs que le contrat fixe, et que ce dépôt ne décide pas.
 *
 * Elles vivent toutes ici, au même endroit, parce qu'elles ont la même raison de changer : une
 * nouvelle version du contrat. Les éparpiller dans l'encodeur et dans l'accumulateur obligerait à
 * les chercher le jour où le contrat bouge.
 *
 * Le contrat fait foi dans `BodyVision-CCM/bodyvision-contracts`, branche `main`, schémas
 * `SeriesUpload` et `Frame`. Il n'est pas recopié ici : une copie locale périme en silence.
 */

/**
 * Version annoncée dans le champ `contractVersion` : celle du **format de télémétrie**, c'est-à-dire
 * de la structure de `SeriesUpload` et de `Frame`.
 *
 * **Distincte de `info.version` du document OpenAPI**, et c'est le piège : les deux numéros vivent
 * côte à côte dans le même fichier de contrat et ne bougent pas ensemble. Le format en est à sa
 * première version ; le document, lui, est en `0.4.1` et continuera d'avancer sans que cette
 * constante change.
 *
 * À incrémenter **seulement** si la structure de `SeriesUpload` ou de `Frame` change — un champ
 * ajouté, retiré, ou dont le sens change. Une correction de description ou une autre opération du
 * contrat n'y touche pas.
 *
 * Tranché par le propriétaire du contrat.
 */
const val TELEMETRY_CONTRACT_VERSION = "1.0"

/**
 * Exercice annoncé dans `exerciseCode`. **Valeur de démonstration**, en attendant que le catalogue
 * `/exercises` existe et que l'écran laisse choisir : il n'y a aujourd'hui aucun endroit où ce
 * choix pourrait être fait.
 *
 * Respecte le motif `^[A-Z0-9_]+$` que le contrat impose aux codes d'exercice.
 */
const val DEMO_EXERCISE_CODE = "SQUAT"

/**
 * Rang de la série dans la séance. Toujours 0 : un enregistrement produit une seule série, et un
 * rang n'a pas de sens tant qu'il n'y a pas de séance à laquelle la rattacher (S6).
 */
const val SERIES_INDEX = 0

/** Jeu de points décrit par `lm`, au sens de l'énumération `capture.landmarkSet` du contrat. */
const val LANDMARK_SET = "MLKIT_POSE_33"

/**
 * Modèle de pose, au sens de l'énumération `capture.model`. `BASE` et non `ACCURATE` : c'est la
 * configuration ML Kit figée du projet, mesurée sur l'appareil cible.
 */
const val POSE_MODEL = "BASE"

/** Nombre de points d'un squelette ML Kit, et la taille exacte que `lm` exige. */
const val LANDMARK_COUNT = 33

/**
 * Débit annoncé dans `capture.nominalFps`, et celui vers lequel on sous-échantillonne. L'analyse,
 * elle, continue de tourner à 30 FPS pour l'alerte locale : c'est l'envoi qui est allégé.
 */
const val NOMINAL_FPS = 10

/**
 * Période de l'échéance d'échantillonnage : une image gardée par tranche de 100 ms.
 *
 * Dérivée de [NOMINAL_FPS] plutôt qu'écrite en dur, pour qu'il n'y ait pas deux nombres à garder
 * cohérents — c'est bien le débit annoncé au serveur qui la fixe.
 */
const val SAMPLE_PERIOD_MS = 1_000L / NOMINAL_FPS

/**
 * `frames.maxItems` du contrat, soit 90 secondes à [NOMINAL_FPS]. Au-delà, la série se termine
 * d'elle-même : un lot plus gros serait refusé par le serveur.
 */
const val MAX_SERIES_FRAMES = 900

/**
 * Format d'une coordonnée : **trois décimales**, comme le contrat le demande.
 *
 * Toujours employé avec `Locale.ROOT`. Sans locale explicite, un téléphone en français rendrait
 * `0,500`, et une virgule décimale n'est pas du JSON.
 */
const val COORDINATE_FORMAT = "%.3f"

/** Motif ISO 8601 en UTC des champs `startedAt` et `endedAt`, tous deux en `format: date-time`. */
const val ISO_8601_UTC_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
