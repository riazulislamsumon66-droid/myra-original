package com.maya.assistant.apps

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import com.maya.assistant.utils.Logger

object AppLauncher {
    private val TAG = "LAUNCHER"

    fun launch(context: Context, appName: String): Boolean {
        val pm = context.packageManager
        // Try direct package name first
        var intent = pm.getLaunchIntentForPackage(appName.lowercase())
        // Try fuzzy match via AppDetector if accessibility service is available
        if (intent == null) {
            val svc = com.maya.assistant.service.SmartAccessibilityEngine.service
            if (svc != null) {
                val app = com.maya.assistant.automation.AppDetector.findAppByName(svc, appName)
                if (app != null) {
                    intent = pm.getLaunchIntentForPackage(app.packageName)
                }
            }
        }
        if (intent == null) {
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

    fun closeViaRecents(context: Context, appName: String): Boolean {
        return try {
            val svc = com.maya.assistant.service.SmartAccessibilityEngine.service
            if (svc == null) {
                Logger.w(TAG, "Accessibility service not available for recents swipe")
                return false
            }
            // Step 1: Open recent apps (global action)
            svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
            Thread.sleep(1500)
            // Step 2: Find the app card in recents and swipe it away
            val root = svc.rootInActiveWindow ?: return false
            val appCard = findAppCardInRecents(root, appName)
            if (appCard != null) {
                // Swipe up to dismiss
                val bounds = android.graphics.Rect()
                appCard.getBoundsInScreen(bounds)
                val centerX = bounds.centerX()
                val centerY = bounds.centerY()
                val endY = centerY - 800 // Swipe up
                com.maya.assistant.automation.ActionExecutor.swipe(svc, centerX, centerY, centerX, Math.max(endY, 100), 300)
                Logger.d(TAG, "Swiped away $appName from recents")
                true
            } else {
                Logger.w(TAG, "App card not found in recents: $appName")
                false
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Close via recents failed: ${e.message}")
            false
        }
    }

    private fun findAppCardInRecents(node: android.view.accessibility.AccessibilityNodeInfo, appName: String): android.view.accessibility.AccessibilityNodeInfo? {
        if (node.text != null && node.text.toString().contains(appName, ignoreCase = true)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findAppCardInRecents(child, appName)
            if (result != null) return result
        }
        return null
    }
}
