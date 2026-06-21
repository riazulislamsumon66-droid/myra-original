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
            // First try: find by foreground task
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                val appTasks = am.appTasks
                for (task in appTasks) {
                    val taskPkg = task.taskInfo?.baseActivity?.packageName
                        ?: task.taskInfo?.baseIntent?.component?.packageName
                        ?: continue
                    val taskLabel = try {
                        val pkgInfo = context.packageManager.getApplicationInfo(taskPkg, 0)
                        context.packageManager.getApplicationLabel(pkgInfo).toString().lowercase()
                    } catch (e: Exception) { taskPkg.lowercase() }

                    if (taskLabel.contains(appName.lowercase()) || taskPkg.lowercase().contains(appName.lowercase())) {
                        // Found matching foreground task — bring to front then simulate back
                        task.moveToFront()
                        // Simulate back press after brief delay to close
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            // Force stop via kill background
                            am.killBackgroundProcesses(taskPkg)
                            // Remove from recents
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                try { task.finishAndRemoveTask() } catch (_: Exception) {}
                            }
                        }, 100)
                        Logger.d(TAG, "Closing: $taskPkg ($taskLabel)")
                        return true
                    }
                }
            }

            // Second try: kill all matching packages
            val packages = context.packageManager.getInstalledApplications(0)
            for (pkg in packages) {
                val label = pkg.loadLabel(context.packageManager).toString().lowercase()
                if (label.contains(appName.lowercase()) || pkg.packageName.lowercase().contains(appName.lowercase())) {
                    am.killBackgroundProcesses(pkg.packageName)
                    // Force stop (requires FORCE_STOP_PACKAGES permission or device admin)
                    try {
                        val method = am::class.java.getMethod("forceStopPackage", String::class.java)
                        method.invoke(am, pkg.packageName)
                    } catch (_: Exception) {
                        // Fallback: kill background is best we can do without device admin
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
