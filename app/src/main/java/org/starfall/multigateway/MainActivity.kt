package org.starfall.multigateway

import android.content.Intent
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
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
import org.starfall.multigateway.ui.mcp.OAuthReceiver

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
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "multigateway" && data.host == "oauth") {
            val fullUrl = data.toString()
            val token = data.getQueryParameter("access_token")
                ?: data.fragment?.split("&")?.find { it.startsWith("access_token=") }?.substringAfter("access_token=")
                ?: Uri.parse(fullUrl.replace("#", "?")).getQueryParameter("access_token")
            if (token != null) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("OAuth Token", token))
                Toast.makeText(this, "OAuth2 Authenticated! Token copied and auto-filled.", Toast.LENGTH_LONG).show()
                OAuthReceiver.postToken(token)
            }
        }
    }
}
