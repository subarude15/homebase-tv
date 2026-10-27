package com.homebase.tv

import android.app.Application
import android.content.Context
import com.homebase.tv.data.apps.InstalledAppsRepository
import com.homebase.tv.data.backend.BackendRepository
import com.homebase.tv.data.settings.SettingsRepository

class HomebaseApp : Application() {
    val container by lazy { AppContainer(this) }
}

class AppContainer(context: Context) {
    val settings = SettingsRepository(context)
    val apps = InstalledAppsRepository(context)
    val backend = BackendRepository()
}
