package com.homebase.tv.data.apps

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val label: String,
    val image: ImageBitmap,
    val isBanner: Boolean,
)

class InstalledAppsRepository(private val context: Context) {
    private val pm = context.packageManager

    suspend fun load(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        pm.queryIntentActivities(query, 0)
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { info ->
                val banner = info.loadBanner(pm)
                val bitmap = banner?.toBitmap(320, 180) ?: info.loadIcon(pm).toBitmap(128, 128)
                InstalledApp(info.packageName, info.loadLabel(pm).toString(), bitmap.asImageBitmap(), banner != null)
            }
            .sortedBy { it.label.lowercase() }
    }

    fun launch(packageName: String): Boolean {
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName)
            ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    /** Emby ignores deep links delivered to an already-open instance, so its task is restarted. */
    fun openLink(uri: String, packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}

/** Favorites in saved order, skipping uninstalled ones; falls back to every app when none are set. */
fun <T> favoriteRow(apps: List<T>, favorites: List<String>, packageName: (T) -> String): List<T> {
    val byPackage = apps.associateBy(packageName)
    return favorites.mapNotNull { byPackage[it] }.ifEmpty { apps }
}
