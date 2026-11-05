package fr.upjv.bodyvision.pose.ui

/**
 * Un point en pixels de la vue.
 *
 * Un type à nous, et non l'`Offset` de Compose : c'est ce qui garde [projectOnView] testable sur
 * la JVM, sans appareil ni environnement Android.
 */
data class ViewPoint(
    val x: Float,
    val y: Float,
)
