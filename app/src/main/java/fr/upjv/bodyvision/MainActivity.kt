package fr.upjv.bodyvision

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import fr.upjv.bodyvision.core.ui.theme.BodyVisionTheme
import fr.upjv.bodyvision.session.ui.LiveSessionScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Les deux barres sont forcées en style sombre. Le défaut de enableEdgeToEdge() est
        // SystemBarStyle.auto, qui déduit le contraste des icônes du mode nuit du système ;
        // or l'interface est sombre sans condition, donc en mode clair le système dessinerait
        // des icônes noires sur notre fond noir.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            BodyVisionTheme {
                LiveSessionScreen()
            }
        }
    }
}
