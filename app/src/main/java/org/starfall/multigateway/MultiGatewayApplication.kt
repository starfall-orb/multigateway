package org.starfall.multigateway

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.starfall.multigateway.di.AppContainer
import org.starfall.multigateway.data.service.CrashReportStore
import org.starfall.multigateway.data.service.CrashRecordingHandler
import org.starfall.multigateway.ui.chat.ChatRenderCaches
import java.io.File

class MultiGatewayApplication : Application(), coil3.SingletonImageLoader.Factory {
    override fun newImageLoader(context: android.content.Context): coil3.ImageLoader =
        org.starfall.multigateway.data.service.AppImages.createLoader(context)

    val container by lazy { AppContainer(this) }
    internal val crashReports by lazy { CrashReportStore(File(filesDir, "last_crash.json")) }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(
            CrashRecordingHandler(crashReports, Thread.getDefaultUncaughtExceptionHandler())
        )
        // The chat render caches are a convenience only: give the RAM back as soon as Android asks.
        registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) = ChatRenderCaches.onTrimMemory(level)
            override fun onLowMemory() = ChatRenderCaches.clearAll()
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        })
        applicationScope.launch { container.defaultDataInitializer.initialize() }
    }
}
