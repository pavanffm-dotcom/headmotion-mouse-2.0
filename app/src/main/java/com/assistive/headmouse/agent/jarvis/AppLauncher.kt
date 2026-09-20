package com.assistive.headmouse.agent.jarvis

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

/**
 * Universal Android Application Launcher.
 * Maps common natural-language app names to their canonical Android package names,
 * and dynamically queries PackageManager to launch any installed app.
 */
object AppLauncher {

    private const val TAG = "AppLauncher"

    // Canonical package dictionary for popular apps
    private val KNOWN_APPS = mapOf(
        "youtube" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "chrome" to "com.android.chrome",
        "google chrome" to "com.android.chrome",
        "browser" to "com.android.chrome",
        "settings" to "com.android.settings",
        "camera" to "com.android.camera",
        "photos" to "com.google.android.apps.photos",
        "gallery" to "com.google.android.apps.photos",
        "maps" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "gmail" to "com.google.android.gm",
        "spotify" to "com.spotify.music",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "facebook" to "com.facebook.katana",
        "play store" to "com.android.vending",
        "files" to "com.google.android.documentsui",
        "calculator" to "com.google.android.calculator",
        "clock" to "com.google.android.deskclock",
        "insta" to "com.instagram.android",
        "telegram" to "org.telegram.messenger",
        "snapchat" to "com.snapchat.android",
        "dialer" to "com.google.android.dialer",
        "phone" to "com.google.android.dialer",
        "messages" to "com.google.android.apps.messaging",
        "contacts" to "com.google.android.contacts"
    )

    /**
     * Resolves an app query (friendly name or package) and launches it.
     * Returns true if successfully launched, false otherwise.
     */
    fun launchApp(context: Context, appQuery: String): Boolean {
        val cleanQuery = appQuery.trim().lowercase()
        val pm = context.packageManager

        // 1. Direct package check
        if (cleanQuery.contains(".")) {
            val intent = pm.getLaunchIntentForPackage(cleanQuery)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.i(TAG, "Launched via direct package: $cleanQuery")
                return true
            }
        }

        // 2. Known apps dictionary check
        for ((name, pkg) in KNOWN_APPS) {
            if (cleanQuery.contains(name)) {
                val intent = pm.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    Log.i(TAG, "Launched via dictionary match [$name]: $pkg")
                    return true
                }
            }
        }

        // 3. Special intent fallbacks (Camera, Settings)
        if (cleanQuery.contains("camera")) {
            try {
                val cameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(cameraIntent)
                return true
            } catch (_: Exception) {}
        }

        // 4. Dynamic search across installed apps
        try {
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (appInfo in installedApps) {
                val label = pm.getApplicationLabel(appInfo).toString().lowercase()
                if (label.isNotBlank() && (label == cleanQuery || cleanQuery.contains(label) || label.contains(cleanQuery))) {
                    val intent = pm.getLaunchIntentForPackage(appInfo.packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        Log.i(TAG, "Launched via dynamic PackageManager match [$label]: ${appInfo.packageName}")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed searching installed applications: ", e)
        }

        Log.w(TAG, "Could not resolve app for query: $appQuery")
        return false
    }

    /**
     * Checks whether an app is installed on the device.
     */
    fun isAppInstalled(context: Context, appQuery: String): Boolean {
        val clean = appQuery.trim().lowercase()
        val pkg = KNOWN_APPS[clean] ?: if (clean.contains(".")) clean else null
        if (pkg != null) {
            return try {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) { false }
        }
        return false
    }
}
