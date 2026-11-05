package fr.upjv.bodyvision.camera.data

import androidx.camera.core.CameraSelector

/**
 * L'objectif utilisé pour la captation.
 *
 * Un enum du projet et non un `CameraSelector` de CameraX : l'état de l'écran et le
 * `SavedStateHandle` n'ont pas à transporter un type de bibliothèque, et celui-ci se compare,
 * s'enregistre par son nom et se lit dans un journal.
 */
enum class CameraLens {
    BACK,
    FRONT,
}

/** La traduction vers CameraX, au seul endroit du projet qui connaît CameraX. */
internal fun CameraLens.toCameraSelector(): CameraSelector =
    when (this) {
        CameraLens.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
        CameraLens.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
    }
