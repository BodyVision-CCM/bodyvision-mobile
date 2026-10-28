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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.upjv.bodyvision.R
import fr.upjv.bodyvision.camera.ui.CameraPreview

private val PANE_PADDING = 24.dp
private val PANE_SPACING = 16.dp

/** Au-delà, une ligne de texte devient trop longue à lire en paysage. */
private val PANE_MAX_WIDTH = 360.dp

private val BANNER_MARGIN = 16.dp
private val BANNER_TEXT_PADDING_HORIZONTAL = 16.dp

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

    when (uiState.permission) {
        // Le dialogue système est à l'écran : rien à afficher derrière.
        CameraPermissionStatus.UNKNOWN -> Box(modifier = Modifier.fillMaxSize())

        CameraPermissionStatus.GRANTED ->
            if (uiState.isCameraUnavailable) {
                MessagePane(
                    message = stringResource(R.string.camera_unavailable_message),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = viewModel::onRetryCamera,
                )
            } else {
                // Une superposition, et non une colonne : un Box n'a pas de hauteur à répartir,
                // donc il est indifférent à l'orientation. Les compteurs de SCRUM-32 viendront
                // se poser ici, par-dessus l'aperçu.
                Box(modifier = Modifier.fillMaxSize()) {
                    CameraPreview(
                        onCameraUnavailable = viewModel::onCameraUnavailable,
                        onCameraRecoveringChanged = viewModel::onCameraRecoveringChanged,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Un bandeau et non un panneau : CameraPreview reste dans la composition,
                    // donc aucun unbind() ne vient interrompre la reprise que CameraX est en
                    // train de faire tout seul.
                    if (uiState.isCameraRecovering) {
                        RecoveringBanner(modifier = Modifier.align(Alignment.TopCenter))
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
