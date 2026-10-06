package org.starfall.multigateway

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.starfall.multigateway.di.AppContainer

class MultiGatewayApplication : Application(), coil3.SingletonImageLoader.Factory {
    override fun newImageLoader(context: android.content.Context): coil3.ImageLoader =
        org.starfall.multigateway.data.service.AppImages.createLoader(context)

    val container by lazy { AppContainer(this) }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch { container.defaultDataInitializer.initialize() }
    }
}
