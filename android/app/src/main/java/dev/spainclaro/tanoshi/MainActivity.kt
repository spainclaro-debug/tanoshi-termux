package dev.spainclaro.tanoshi

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.spainclaro.tanoshi.ui.TanoshiRoot
import dev.spainclaro.tanoshi.ui.theme.TanoshiTheme

/**
 * Single-activity host. The Activity only installs the Compose tree; the server
 * itself lives in [dev.spainclaro.tanoshi.server.TanhoshiService] and survives
 * configuration changes and Activity recreation.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge with light system-bar icons: the app is dark-first, so
        // the auto style would draw dark icons on a dark background when the
        // system is in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            TanoshiTheme {
                TanoshiRoot()
            }
        }
    }
}
