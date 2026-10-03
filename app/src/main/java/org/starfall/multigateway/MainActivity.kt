package org.starfall.multigateway

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.starfall.multigateway.data.service.ChatBackgroundService
import org.starfall.multigateway.ui.MainScreen
import org.starfall.multigateway.ui.theme.MultiGatewayTheme
import org.starfall.multigateway.ui.chat.ChatViewModel
import org.starfall.multigateway.ui.configuration.ConfigurationViewModel
import org.starfall.multigateway.ui.settings.SettingsViewModel
import org.starfall.multigateway.data.model.parseProviderLink

class MainActivity : ComponentActivity() {

    private val container get() = (application as MultiGatewayApplication).container
    private val viewModel: ChatViewModel by viewModels { container.viewModelFactory }
    private val configurationViewModel: ConfigurationViewModel by viewModels { container.viewModelFactory }
    private val settingsViewModel: SettingsViewModel by viewModels { container.viewModelFactory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        enableEdgeToEdge()

        lifecycleScope.launch {
            viewModel.isGenerating.collect { busy ->
                val serviceIntent = Intent(this@MainActivity, ChatBackgroundService::class.java)
                if (busy) {
                    androidx.core.content.ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                } else {
                    stopService(serviceIntent)
                }
            }
        }

        setContent {
            val appPrefs by settingsViewModel.preferences.collectAsStateWithLifecycle()
            MultiGatewayTheme(
                themeMode = appPrefs.themeMode,
                amoledMode = appPrefs.useAmoled,
                dynamicColor = appPrefs.useDynamicColor,
                colorSchemeName = appPrefs.colorSchemeName
            ) {
                val darkBars = MaterialTheme.colorScheme.background.luminance() < 0.5f
                SideEffect {
                    val transparent = android.graphics.Color.TRANSPARENT
                    window.statusBarColor = transparent
                    window.navigationBarColor = transparent
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        window.isNavigationBarContrastEnforced = false
                        window.isStatusBarContrastEnforced = false
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(viewModel, configurationViewModel, settingsViewModel, container.toolFiles)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme.equals("multigateway", true) && data.host.equals("provider", true)) {
            // Consume the launch URI before saving; Activity recreation must not import it again.
            intent.data = null
            runCatching { parseProviderLink(data) }
                .onSuccess(configurationViewModel::importProvider)
                .onFailure {
                    Toast.makeText(this, it.message ?: "Invalid provider link.", Toast.LENGTH_LONG).show()
                }
            return
        }

    }
}
