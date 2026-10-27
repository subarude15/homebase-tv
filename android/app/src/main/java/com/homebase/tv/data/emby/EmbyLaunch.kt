package com.homebase.tv.data.emby

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

const val EMBY_TV_PACKAGE = "tv.emby.embyatv"

/** Emby for Android TV item info / play screen. */
fun embyItemUriString(itemId: String): String = "embyatv://tv.emby.embyatv/play/$itemId"

/**
 * Opens the item in Emby for Android TV. Falls back to launching Emby if the
 * deep link isn't handled. Returns false when Emby isn't installed.
 */
fun playInEmby(context: Context, itemId: String): Boolean {
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(embyItemUriString(itemId))).apply {
        setPackage(EMBY_TV_PACKAGE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(view)
        true
    } catch (_: ActivityNotFoundException) {
        val launch = context.packageManager.getLeanbackLaunchIntentForPackage(EMBY_TV_PACKAGE)
            ?: context.packageManager.getLaunchIntentForPackage(EMBY_TV_PACKAGE)
            ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        true
    }
}
