package fr.upjv.bodyvision.telemetry.domain

import fr.upjv.bodyvision.pose.domain.NormalizedLandmark
import fr.upjv.bodyvision.pose.domain.UprightImageSize
import java.util.Locale

/**
 * Une série → le corps JSON de `SeriesUpload`, en une seule ligne compacte.
 *
 * **Encodeur écrit à la main, et c'est défendable ici.** `org.json` est un stub vide dans
 * `android.jar`, donc inutilisable depuis un test JVM ; kotlinx.serialization ajouterait un plugin
 * de compilation à une chaîne d'outils volontairement figée. Et surtout le schéma est petit, plat
 * et stable : huit champs, un objet, un tableau de nombres. Sur un schéma qui changerait souvent,
 * ce choix serait mauvais — chaque champ ajouté se paierait en code et en test, là où une
 * bibliothèque suivrait la classe de données toute seule.
 *
 * **Aucune chaîne n'a besoin d'être échappée, et c'est structurel, pas une chance.** Les seules
 * chaînes écrites sont des littéraux de ce fichier et de `TelemetryContract` — les noms de clés,
 * `SQUAT`, `MLKIT_POSE_33`, `BASE`, la version — plus deux valeurs que l'encodeur **fabrique
 * lui-même** : un UUID, qui n'a que des chiffres hexadécimaux et des tirets, et deux horodatages
 * produits par un motif de date fixe. Aucun texte venu de quelqu'un, d'un fichier ou du réseau
 * n'entre jamais ici : `exerciseCode` est une constante du code et non un paramètre d'écran. Le
 * jour où un champ **libre** apparaîtrait — un nom d'appareil, une note — il faudrait un
 * échappement, et cette phrase ne serait plus vraie.
 *
 * **Tous les nombres passent par `Locale.ROOT`.** Voir [appendCoordinate].
 */
fun encodeSeriesUpload(series: TelemetrySeries): String {
    // `last()` et non `lastOrNull()` : une série sans image n'existe pas — `SeriesRecorder.complete`
    // n'en rend aucune — et si l'invariant tombait un jour, mieux vaut une levée bruyante ici
    // qu'un `endedAt` égal à `startedAt` écrit dans un fichier qui aurait l'air normal.
    val endedAtEpochMs = series.startedAtEpochMs + series.frames.last().offsetMs

    return buildString {
        append("{")
        append("\"batchId\":\"").append(series.batchId).append("\",")
        append("\"index\":").append(SERIES_INDEX).append(",")
        append("\"exerciseCode\":\"").append(DEMO_EXERCISE_CODE).append("\",")
        append("\"startedAt\":\"").append(isoUtcTimestampOf(series.startedAtEpochMs)).append("\",")
        append("\"endedAt\":\"").append(isoUtcTimestampOf(endedAtEpochMs)).append("\",")
        append("\"contractVersion\":\"").append(TELEMETRY_CONTRACT_VERSION).append("\",")
        appendCapture(series.imageSize)
        append(",\"frames\":[")
        series.frames.forEachIndexed { index, frame ->
            if (index > 0) append(",")
            appendFrame(frame)
        }
        append("]}")
    }
}

/** L'objet `capture` : ce que la série dit d'elle-même, et qui ne change pas d'une image à l'autre. */
private fun StringBuilder.appendCapture(imageSize: UprightImageSize) {
    append("\"capture\":{")
    append("\"landmarkSet\":\"").append(LANDMARK_SET).append("\",")
    append("\"model\":\"").append(POSE_MODEL).append("\",")
    append("\"analysisWidth\":").append(imageSize.width).append(",")
    append("\"analysisHeight\":").append(imageSize.height).append(",")
    append("\"nominalFps\":").append(NOMINAL_FPS)
    append("}")
}

private fun StringBuilder.appendFrame(frame: TelemetryFrame) {
    append("{\"t\":").append(frame.offsetMs).append(",\"lm\":[")
    frame.landmarks.forEachIndexed { index, landmark ->
        if (index > 0) append(",")
        appendLandmark(landmark)
    }
    append("]}")
}

/**
 * Un point, dans l'ordre `[x, y, z, v]` du contrat. L'ordre est celui du tableau, et il fait foi :
 * aucune clé ne vient le rappeler, c'est tout l'intérêt de cet encodage compact.
 */
private fun StringBuilder.appendLandmark(landmark: NormalizedLandmark) {
    append("[")
    appendCoordinate(landmark.x)
    append(",")
    appendCoordinate(landmark.y)
    append(",")
    appendCoordinate(landmark.z)
    append(",")
    appendCoordinate(landmark.visibility)
    append("]")
}

/**
 * Une coordonnée, à trois décimales, **en `Locale.ROOT`**.
 *
 * C'est le piège le plus coûteux de tout ce fichier : sans locale explicite, `String.format` suit
 * celle de l'appareil, et un téléphone réglé en français écrit `0,500`. Une virgule décimale n'est
 * pas du JSON : le fichier serait refusé par le serveur, et **rien sur le téléphone ne l'aurait
 * signalé** — l'écriture aurait réussi, le fichier aurait la bonne taille, et la panne
 * n'apparaîtrait qu'au bout de la chaîne. Un test force `Locale.FRANCE` avant d'encoder pour
 * épingler cette ligne.
 *
 * **Règle d'arrondi, écrite parce qu'elle n'est pas évidente** : `%.3f` arrondit au plus près, et à
 * l'écart de zéro en cas d'égalité — mais sur la valeur flottante **réellement représentée**, pas
 * sur celle qu'on a écrite dans le code. `0,1235f` vaut un peu moins que 0,1235 en binaire, donc il
 * descend à `0.123` au lieu de monter à `0.124`. L'écart est d'un millième sur une coordonnée
 * normalisée, soit moins d'un pixel : sans conséquence ici, mais à savoir avant de comparer deux
 * fichiers au caractère près.
 *
 * Aucun bornage : une coordonnée hors de 0..1 est un cas normal — ML Kit continue d'estimer un
 * point sorti du cadre — et le contrat l'admet explicitement.
 */
private fun StringBuilder.appendCoordinate(value: Float) {
    append(String.format(Locale.ROOT, COORDINATE_FORMAT, value))
}
