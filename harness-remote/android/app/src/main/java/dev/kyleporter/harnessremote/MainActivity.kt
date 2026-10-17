package dev.kyleporter.harnessremote

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.kyleporter.harnessremote.service.EXTRA_SESSION
import dev.kyleporter.harnessremote.ui.HarnessTheme
import dev.kyleporter.harnessremote.ui.NewSessionScreen
import dev.kyleporter.harnessremote.ui.ServersScreen
import dev.kyleporter.harnessremote.ui.SessionScreen
import dev.kyleporter.harnessremote.ui.SessionsScreen

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hub = hub
        if (savedInstanceState == null && hub.client.value == null) hub.lastServer()?.let { hub.connect(it) }
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            HarnessTheme {
                val route by hub.route.collectAsState()
                val client by hub.client.collectAsState()
                val c = client
                BackHandler(enabled = route != Route.Servers) {
                    hub.route.value = when (route) {
                        is Route.Session, Route.NewSession -> Route.Sessions
                        else -> Route.Servers
                    }
                }
                when {
                    c == null || route == Route.Servers -> ServersScreen(hub)
                    route == Route.Sessions -> SessionsScreen(hub, c)
                    route == Route.NewSession -> NewSessionScreen(hub, c)
                    route is Route.Session -> SessionScreen(hub, c, (route as Route.Session).id)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_SESSION) ?: return
        if (hub.client.value != null) hub.route.value = Route.Session(id)
    }
}
