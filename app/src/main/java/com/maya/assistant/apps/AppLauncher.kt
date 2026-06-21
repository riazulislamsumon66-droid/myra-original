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
            val packages = context.packageManager.getInstalledApplications(0)
            for (pkg in packages) {
                val label = pkg.loadLabel(context.packageManager).toString().lowercase()
                if (label.contains(appName.lowercase()) || pkg.packageName.lowercase().contains(appName.lowercase())) {
                    // Kill background processes
                    am.killBackgroundProcesses(pkg.packageName)
                    // Also try to remove recent task
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        am.appTasks.forEach { task ->
                            if (task.taskInfo?.baseActivity?.packageName == pkg.packageName) {
                                task.finishAndRemoveTask()
                            }
                        }
                    }
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
