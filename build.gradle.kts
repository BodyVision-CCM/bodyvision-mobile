plugins {
    // Le projet racine n’a sinon ni check ni build, donc la tâche ktlint de ses
    // propres scripts ne serait accrochée à rien.
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Appliqué ici aussi, et pas seulement sur :app : sinon les scripts Gradle de la
    // racine ne sont couverts par aucune tâche ktlint.
    alias(libs.plugins.ktlint)
}
