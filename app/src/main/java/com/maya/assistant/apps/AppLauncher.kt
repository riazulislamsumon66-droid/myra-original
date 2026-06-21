package com.maya.assistant.apps

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import com.maya.assistant.utils.Logger

object AppLauncher {
    private val TAG = "LAUNCHER"

    fun launch(context: Context, appName: String): Boolean {
        val intent = InstalledAppsManager.getLaunchIntent(context, appName) ?: run {
            Logger.w(TAG, "App not found: $appName")
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            Logger.d(TAG, "Launched: $appName")
            return true
        } catch (e: Exception) {
            Logger.e(TAG, "Launch failed: ${e.message}")
            return false
        }
    }

    fun close(context: Context, appName: String): Boolean {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            // Try to kill the app by package name
            val packages = context.packageManager.getInstalledApplications(0)
            for (pkg in packages) {
                if (pkg.loadLabel(context.packageManager).toString().lowercase().contains(appName.lowercase())) {
                    am.killBackgroundProcesses(pkg.packageName)
                    Logger.d(TAG, "Closed: ${pkg.packageName}")
                    return true
                }
            }
            Logger.w(TAG, "App not found for closing: $appName")
            return false
        } catch (e: Exception) {
            Logger.e(TAG, "Close failed: ${e.message}")
            return false
        }
    }
}
