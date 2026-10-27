package com.homebase.tv

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.homebase.tv.data.apps.InstalledAppsRepository
import com.homebase.tv.data.backend.BackendRepository
import com.homebase.tv.data.settings.SettingsRepository

class HomebaseApp : Application(), SingletonImageLoader.Factory {
    val container by lazy { AppContainer(this) }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.backend.imageClient })) }
        .crossfade(true)
        .build()
}

class AppContainer(context: Context) {
    val settings = SettingsRepository(context)
    val apps = InstalledAppsRepository(context)
    val backend = BackendRepository(context.filesDir)
}
