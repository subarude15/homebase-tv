package com.homebase.tv.data.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

data class InstalledApp(
    val packageName: String,
    val label: String,
    val image: ImageBitmap,
    val isBanner: Boolean,
)

/** Favorites in saved order; fall back to the full list when none are installed. */
fun <T> favoriteRow(items: List<T>, favorites: List<String>, key: (T) -> String): List<T> {
    val byKey = items.associateBy(key)
    val row = favorites.mapNotNull { byKey[it] }
    return row.ifEmpty { items }
}

class InstalledAppsRepository(private val context: Context) {
    private val pm = context.packageManager

    fun load(): List<InstalledApp> {
        val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return pm.queryIntentActivities(leanback, PackageManager.MATCH_ALL)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName) return@mapNotNull null
                val label = ri.loadLabel(pm).toString()
                val banner = ri.activityInfo.loadBanner(pm) ?: pm.getApplicationBanner(ri.activityInfo.applicationInfo)
                val drawable = banner ?: ri.loadIcon(pm) ?: return@mapNotNull null
                val bmp = drawable.toBitmap(
                    width = if (banner != null) 320 else 96,
                    height = if (banner != null) 180 else 96,
                    config = Bitmap.Config.ARGB_8888,
                )
                InstalledApp(pkg, label, bmp.asImageBitmap(), isBanner = banner != null)
            }
            .sortedBy { it.label.lowercase() }
    }

    fun launch(packageName: String) {
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName)
            ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
