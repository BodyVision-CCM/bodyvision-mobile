package fr.upjv.bodyvision

/** Les deux modes de durée de run proposés à l'utilisateur (cf. CLAUDE.md). */
enum class RunDurationMode(val label: String, val durationSeconds: Long) {
    COMPARAISON("Comparaison (4 min)", 4 * 60L),
    ENDURANCE("Endurance (10 min)", 10 * 60L)
}
