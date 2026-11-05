package fr.upjv.bodyvision.session.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.upjv.bodyvision.R
import fr.upjv.bodyvision.camera.data.CameraLens
import fr.upjv.bodyvision.camera.data.availableCameraLenses
import fr.upjv.bodyvision.camera.ui.CameraPreview
import fr.upjv.bodyvision.pose.ui.SkeletonOverlay

private val PANE_PADDING = 24.dp
private val PANE_SPACING = 16.dp

/** Au-delà, une ligne de texte devient trop longue à lire en paysage. */
private val PANE_MAX_WIDTH = 360.dp

private val BANNER_MARGIN = 16.dp
private val BANNER_TEXT_PADDING_HORIZONTAL = 16.dp
private val COUNTERS_MARGIN = 16.dp
private val COUNTERS_TEXT_PADDING_HORIZONTAL = 12.dp
private val COUNTERS_TEXT_PADDING_VERTICAL = 6.dp

/** Assez opaque pour que le texte reste lisible sur une image de caméra claire. */
private const val BANNER_OPACITY = 0.9f

/**
 * Écran de captation. Affiche un état et émet des actions ; c'est le [LiveSessionViewModel] qui
 * décide.
 */
@Composable
fun LiveSessionScreen(viewModel: LiveSessionViewModel = viewModel()) {
    val context = LocalContext.current

    // LocalActivity plutôt qu'un `context as Activity` : le transtypage lèverait un
    // ClassCastException partout où le contexte hôte n'est pas une activité, alors qu'ici
    // l'absence d'activité se traite comme un cas normal, sans `!!`.
    val activity = LocalActivity.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            viewModel.onPermissionRequestResult(isGranted, activity.canShowCameraRationale())
        }

    // Au retour depuis les réglages système, l'écran doit afficher l'aperçu sans redémarrage.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onPermissionStateObserved(
            isGranted = context.isCameraPermissionGranted(),
            canShowRationale = activity.canShowCameraRationale(),
        )
    }

    // La permission est demandée au moment où l'aperçu est demandé, pas dans onCreate. La clé
    // `Unit` ne garantit rien à elle seule : cet effet rejoue à chaque recréation de l'activité,
    // donc à chaque rotation, y compris pendant que le dialogue système est affiché. C'est le
    // ViewModel qui tient le compte des demandes déjà envoyées.
    LaunchedEffect(Unit) {
        viewModel.onPermissionStateObserved(
            isGranted = context.isCameraPermissionGranted(),
            canShowRationale = activity.canShowCameraRationale(),
        )
        if (viewModel.consumePendingPermissionRequest()) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Les objectifs de l'appareil ne changent pas en cours de route, mais la demande peut
    // échouer : d'où les deux clés. La permission, parce que sur l'écran de refus énumérer les
    // caméras serait du travail pour quelqu'un qui n'en veut pas ; et l'indisponibilité, pour que
    // « Réessayer » reparte d'une vraie demande — un ensemble vide fait afficher l'écran de panne,
    // et sans cette clé le bouton rendrait la main à un écran qui attend un objectif qui ne
    // viendrait jamais.
    // C'est l'écran qui demande, parce que CameraX veut un `Context` et que le `ViewModel` n'en a
    // pas.
    LaunchedEffect(uiState.permission, uiState.isCameraUnavailable) {
        if (uiState.permission == CameraPermissionStatus.GRANTED && !uiState.isCameraUnavailable) {
            viewModel.onAvailableLensesObserved(availableCameraLenses(context))
        }
    }

    // L'arrêt ne défait pas la liaison — `CameraPreview` reste dans la composition — donc rien
    // d'autre ne viendrait oublier la dernière pose, et elle serait redessinée au retour avant la
    // première image de la caméra.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.onScreenStopped()
    }

    when (uiState.permission) {
        // Le dialogue système est à l'écran : rien à afficher derrière.
        CameraPermissionStatus.UNKNOWN -> Box(modifier = Modifier.fillMaxSize())

        CameraPermissionStatus.GRANTED -> {
            val lens = uiState.lens
            when {
                uiState.isCameraUnavailable ->
                    MessagePane(
                        message = stringResource(R.string.camera_unavailable_message),
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = viewModel::onRetryCamera,
                    )

                // Les objectifs ne sont pas encore connus : rien à afficher, comme pendant le
                // dialogue de permission. Lier l'arrière « en attendant » ferait un battement de
                // caméra pour qui a choisi la frontale.
                lens == null -> Box(modifier = Modifier.fillMaxSize())

                // Une superposition, et non une colonne : un Box n'a pas de hauteur à répartir,
                // donc il est indifférent à l'orientation. C'est ce qui permet au bandeau et aux
                // compteurs de se poser par-dessus l'aperçu sans lui prendre de place.
                else ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        CameraPreview(
                            lens = lens,
                            analysis = viewModel.frameAnalysis,
                            onCameraUnavailable = viewModel::onCameraUnavailable,
                            onCameraRecoveringChanged = viewModel::onCameraRecoveringChanged,
                            onCameraUnbound = viewModel::onCameraUnbound,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // Retiré de la composition pendant une reprise : l'aperçu est figé, et un
                        // squelette posé sur une image qui ne bouge plus serait un squelette périmé
                        // présenté comme une détection. Son état local repart donc de zéro au retour.
                        if (!uiState.isCameraRecovering) {
                            SkeletonOverlay(
                                frames = viewModel.poseFrames,
                                isMirrored = lens == CameraLens.FRONT,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        // En bas et non en haut : le bandeau de reprise occupe le haut, et deux
                        // messages superposés seraient illisibles au moment où l'un des deux compte.
                        PoseCounters(
                            fps = uiState.fps,
                            landmarkCount = uiState.detectedLandmarkCount,
                            modifier = Modifier.align(Alignment.BottomStart),
                        )
                        if (uiState.isLensSwitchAvailable) {
                            // En bas à droite : les compteurs tiennent le bas à gauche et le bandeau
                            // de reprise le haut, donc rien ne se recouvre dans aucune orientation.
                            LensSwitchButton(
                                lens = lens,
                                onClick = viewModel::onLensToggled,
                                modifier = Modifier.align(Alignment.BottomEnd),
                            )
                        }
                        // Un bandeau et non un panneau : CameraPreview reste dans la composition,
                        // donc aucun unbind() ne vient interrompre la reprise que CameraX est en
                        // train de faire tout seul.
                        if (uiState.isCameraRecovering) {
                            RecoveringBanner(modifier = Modifier.align(Alignment.TopCenter))
                        }
                    }
            }
        }

        CameraPermissionStatus.DENIED ->
            MessagePane(
                message = stringResource(R.string.camera_permission_denied_message),
                actionLabel = stringResource(R.string.action_retry),
                onAction = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            )

        CameraPermissionStatus.PERMANENTLY_DENIED ->
            MessagePane(
                message = stringResource(R.string.camera_permission_blocked_message),
                actionLabel = stringResource(R.string.action_open_settings),
                onAction = { context.startActivity(appSettingsIntent(context.packageName)) },
            )
    }
}

/**
 * Les deux chiffres de SCRUM-32, posés sur l'aperçu : images analysées par seconde et points du
 * squelette dans la dernière pose.
 *
 * Publiés une fois par seconde par le `ViewModel`, donc une recomposition par seconde et non
 * trente : c'est tout l'intérêt de ne faire passer que des scalaires par l'état.
 */
@Composable
private fun PoseCounters(
    fps: Int,
    landmarkCount: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        // Bas et côtés : l'inset du haut n'ajouterait qu'un vide au-dessus d'un élément aligné
        // en bas, et en paysage l'encoche est sur un côté.
        modifier =
            modifier
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                ).padding(COUNTERS_MARGIN),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = BANNER_OPACITY),
        // Explicite pour la même raison que le bandeau : l'alpha empêche `Surface` de déduire
        // la couleur du texte.
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Text(
            text =
                stringResource(
                    R.string.session_counters,
                    fps,
                    pluralStringResource(R.plurals.session_landmark_count, landmarkCount, landmarkCount),
                ),
            style = MaterialTheme.typography.labelLarge,
            modifier =
                Modifier.padding(
                    horizontal = COUNTERS_TEXT_PADDING_HORIZONTAL,
                    vertical = COUNTERS_TEXT_PADDING_VERTICAL,
                ),
        )
    }
}

/**
 * Le bouton de bascule arrière / frontale.
 *
 * Un bouton texte et non une icône : `material-icons-extended` n'est pas une dépendance de ce
 * projet, et je n'en ajoute pas une pour un pictogramme.
 *
 * Le libellé nomme l'objectif **vers lequel** on va, parce que c'est ce qu'un bouton promet. La
 * description d'accessibilité reprend l'action en entier — « Basculer vers… » — parce qu'un
 * lecteur d'écran annonce un bouton hors de son contexte visuel, où « Caméra frontale » pourrait
 * passer pour un état.
 */
@Composable
private fun LensSwitchButton(
    lens: CameraLens,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isBack = lens == CameraLens.BACK
    val label =
        if (isBack) {
            stringResource(R.string.action_use_front_camera)
        } else {
            stringResource(R.string.action_use_back_camera)
        }
    val description =
        if (isBack) {
            stringResource(R.string.action_use_front_camera_description)
        } else {
            stringResource(R.string.action_use_back_camera_description)
        }

    Button(
        onClick = onClick,
        // Bas et côtés, comme les compteurs : en paysage l'encoche est sur un côté, et l'inset du
        // haut n'ajouterait qu'un vide au-dessus d'un élément aligné en bas.
        modifier =
            modifier
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                ).padding(COUNTERS_MARGIN)
                .semantics { contentDescription = description },
    ) {
        Text(text = label)
    }
}

/**
 * Message d'attente posé sur l'aperçu, pendant que CameraX réouvre la caméra.
 *
 * Sans bouton, et c'est le propre de ce cas : il n'y a rien à faire que d'attendre, et
 * proposer « Réessayer » inviterait à démonter une liaison en cours de reprise. Le bandeau
 * disparaît de lui-même quand la caméra revient.
 */
@Composable
private fun RecoveringBanner(modifier: Modifier = Modifier) {
    Surface(
        // Haut et côtés seulement : en paysage l'encoche est sur un côté, et l'inset du bas
        // n'ajouterait qu'un vide sous un bandeau qui n'occupe que le haut.
        modifier =
            modifier
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                ).padding(BANNER_MARGIN),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = BANNER_OPACITY),
        // Explicite : la couleur ci-dessus n'est plus celle du thème à cause de l'alpha, donc
        // `Surface` ne saurait pas en déduire la couleur de texte et laisserait du noir.
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Text(
            text = stringResource(R.string.camera_recovering_message),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .widthIn(max = PANE_MAX_WIDTH)
                    .padding(horizontal = BANNER_TEXT_PADDING_HORIZONTAL, vertical = BANNER_MARGIN / 2),
        )
    }
}

/**
 * Mise en page commune aux trois écrans sans aperçu : une explication et une seule action.
 *
 * Centré quand ça tient, défilant sinon — le cas du paysage, où la hauteur disponible est
 * faible.
 */
@Composable
private fun MessagePane(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints {
            Column(
                modifier =
                    Modifier
                        .verticalScroll(rememberScrollState())
                        // Donne au contenu au moins la hauteur visible. Sans ça, un conteneur
                        // défilable mesure son contenu en hauteur infinie, et le centrage
                        // vertical n'a plus aucun effet.
                        .heightIn(min = maxHeight)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(PANE_PADDING),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(PANE_SPACING, Alignment.CenterVertically),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = PANE_MAX_WIDTH),
                )
                Button(onClick = onAction) {
                    Text(text = actionLabel)
                }
            }
        }
    }
}

private fun Context.isCameraPermissionGranted(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** Sans activité, aucune explication n'est à montrer : c'est un `false`, pas une erreur. */
private fun Activity?.canShowCameraRationale(): Boolean =
    this != null && ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)

private fun appSettingsIntent(packageName: String): Intent =
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    )
