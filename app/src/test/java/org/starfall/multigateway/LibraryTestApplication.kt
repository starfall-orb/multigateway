package org.starfall.multigateway

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import okhttp3.OkHttp
import org.starfall.multigateway.data.service.AppImages

/** Real image/network assets without production database initialization in each test. */
class LibraryTestApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        OkHttp.initialize(this)
    }

    override fun newImageLoader(context: Context): ImageLoader = AppImages.createLoader(context)
}
