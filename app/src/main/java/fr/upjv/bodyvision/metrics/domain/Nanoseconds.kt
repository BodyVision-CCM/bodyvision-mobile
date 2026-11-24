package fr.upjv.bodyvision.metrics.domain

/**
 * Les deux conversions depuis la nanoseconde, l'unité dans laquelle cette fonctionnalité compte le
 * temps — c'est celle de `SystemClock.elapsedRealtimeNanos` et celle des horodatages d'image.
 *
 * Dans un fichier à part parce qu'elles servent au calcul des fenêtres, aux percentiles **et** au
 * formatage de la ligne : à la troisième occurrence, une constante se factorise.
 *
 * En `Double`, parce que toutes les divisions qui les emploient rendent une durée fractionnaire.
 */
internal const val NANOS_PER_MILLI = 1_000_000.0

internal const val NANOS_PER_SECOND = 1_000_000_000.0
