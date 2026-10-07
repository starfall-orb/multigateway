package org.starfall.multigateway

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
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
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
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
import org.starfall.multigateway.ui.components.CrashReportDialog

class MainActivity : ComponentActivity() {

    private val container get() = (application as MultiGatewayApplication).container
    private val viewModel: ChatViewModel by viewModels { container.viewModelFactory }
    private val configurationViewModel: ConfigurationViewModel by viewModels { container.viewModelFactory }
    private val settingsViewModel: SettingsViewModel by viewModels { container.viewModelFactory }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        enableEdgeToEdge()

        lifecycleScope.launch {
            viewModel.isGenerating.collect { busy ->
                updateGenerationService(busy)
            }
        }

        // Register before Compose so dialogs, drawers and navigation handle Back
        // first. At the root, retain the generation's ViewModel instead of finishing
        // the activity (Android versions before 12 finish root activities on Back).
        onBackPressedDispatcher.addCallback(this) {
            if (viewModel.isGenerating.value) {
                moveTaskToBack(true)
            } else {
                isEnabled = false
                try { onBackPressedDispatcher.onBackPressed() }
                finally { isEnabled = true }
            }
        }

        setContent {
            val crashReports = (application as MultiGatewayApplication).crashReports
            var pendingCrash by remember { mutableStateOf(crashReports.pending()) }
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
                pendingCrash?.let { report ->
                    CrashReportDialog(report,
                        onCreateIssue = {
                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, report.issueUri())) }
                                .onFailure {
                                    Toast.makeText(this, R.string.crash_issue_open_failed, Toast.LENGTH_LONG).show()
                                }
                        },
                        onDismiss = {
                            crashReports.dismiss(report.id)
                            pendingCrash = null
                        })
                }
            }
        }
        requestBackgroundNotificationPermission()
    }

    override fun onPause() {
        // Flush the current generation state before leaving the visible activity.
        // This includes Home, switching apps, and turning the screen off, even if
        // the StateFlow collector has not yet processed a newly started response.
        updateGenerationService(viewModel.isGenerating.value)
        super.onPause()
    }

    override fun onDestroy() {
        // Finishing clears this activity's ViewModel and cancels its generation.
        // Rotation retains the ViewModel, so its service must stay running.
        if (isFinishing) updateGenerationService(false)
        super.onDestroy()
    }

    private fun updateGenerationService(busy: Boolean) {
        val serviceIntent = Intent(this, ChatBackgroundService::class.java)
        if (!busy) {
            stopService(serviceIntent)
            return
        }
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (e: IllegalStateException) {
            Log.w("ChatBackgroundService", "System refused foreground service start", e)
        } catch (e: SecurityException) {
            Log.w("ChatBackgroundService", "Foreground service permission unavailable", e)
        }
    }

    private fun requestBackgroundNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED) return
        val permissionPrefs = getSharedPreferences("notification_permission", MODE_PRIVATE)
        if (permissionPrefs.getBoolean("background_requested", false)) return
        permissionPrefs.edit().putBoolean("background_requested", true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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
