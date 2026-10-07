package org.starfall.multigateway

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.starfall.multigateway.di.AppContainer
import org.starfall.multigateway.data.service.CrashReportStore
import org.starfall.multigateway.data.service.CrashRecordingHandler
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
        applicationScope.launch { container.defaultDataInitializer.initialize() }
    }
}
