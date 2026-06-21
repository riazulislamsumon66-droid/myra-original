package com.maya.assistant.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.maya.assistant.accessibility.NodeFinder
import com.maya.assistant.automation.AppDetector
import com.maya.assistant.automation.SmartAutomationAgent
import com.maya.assistant.automation.ActionExecutor
import com.maya.assistant.service.ScreenVisionAnalyzer

object SmartAccessibilityEngine {

    private const val TAG = "MYRA_SMART"

    var service: AccessibilityService? = null

    data class Result(
        val success: Boolean,
        val message: String
    )

    fun execute(rawCommand: String): Result {

        val cmd = cleanCommand(rawCommand)

        Log.d(TAG, "EXECUTE -> $cmd")

        val success = when {

            cmd.startsWith("OPEN_APP", true) ->
                handleOpenApp(cmd)

            cmd.startsWith("PLAY_MUSIC", true) ->
                handlePlayMusic(cmd)

            cmd.startsWith("CLICK", true) ->
                genericClick(
                    cmd.removePrefix("CLICK").trim()
                )

            cmd.startsWith("SEARCH", true) ->
                genericSearch(
                    cmd.removePrefix("SEARCH").trim()
                )

            cmd.startsWith("VOLUME_UP", true) ->
                volumeUp()

            cmd.startsWith("VOLUME_DOWN", true) ->
                volumeDown()

            cmd.startsWith("SCREENSHOT", true) ->
                takeScreenshot()

            cmd.startsWith("SCROLL_UP", true) ->
                scrollUp()

            cmd.startsWith("SCROLL_DOWN", true) ->
                scrollDown()

            cmd.equals("BACK", true) ->
                goBack()

            cmd.equals("HOME", true) ->
                goHome()

            cmd.equals("NOTIFICATION", true) ->
                openNotification()

            else ->
                runSmartAutomation(cmd)
        }

        return Result(
            success,
            if (success) "Done" else "Failed"
        )
    }

    private fun cleanCommand(text: String): String {
        return text
            .replace("`", "")
            .trim()
            .lines()
            .firstOrNull()
            ?: ""
    }

    // ============================
    // SMART AI AUTOMATION
    // ============================

    private fun runSmartAutomation(
        command: String
    ): Boolean {

        val svc = service ?: return false

        Log.d(TAG, "SMART MODE -> $command")

        return SmartAutomationAgent.run(
            svc,
            command
        )
    }

    // ============================
    // DYNAMIC APP OPEN
    // ============================

    private fun handleOpenApp(
        command: String
    ): Boolean {

        val appName = command
            .removePrefix("OPEN_APP")
            .removePrefix(":")
            .trim()

        val intent =
            findLaunchIntent(appName)
                ?: return false

        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
        )

        service?.startActivity(intent)

        Log.d(TAG, "OPENED -> $appName")
        return true
    }

    private fun handlePlayMusic(
        command: String
    ): Boolean {

        val musicKeywords = listOf(
            "music",
            "spotify",
            "gaana",
            "saavn",
            "youtube music",
            "amazon music"
        )

        val svc = service ?: return false
        val app = AppDetector.findAppByKeywords(svc, musicKeywords)
            ?: return false

        return launchPackage(app.packageName, "PLAY_MUSIC")
    }

    private fun findLaunchIntent(
        appName: String
    ): Intent? {

        val svc = service ?: return null
        val app = AppDetector.findAppByName(svc, appName) ?: return null
        return svc.packageManager.getLaunchIntentForPackage(app.packageName)
    }

    private fun launchPackage(
        packageName: String,
        tag: String
    ): Boolean {
        val svc = service ?: return false
        val intent = svc.packageManager.getLaunchIntentForPackage(packageName)
            ?: return false

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        svc.startActivity(intent)
        Log.d(TAG, "Opened $tag -> $packageName")
        return true
    }

    // ============================
    // GENERIC CLICK
    // ============================

    fun click(
        text: String? = null,
        contentDesc: String? = null,
        id: String? = null
    ): Boolean {

        val root =
            service?.rootInActiveWindow
                ?: return false

        val nodes = when {
            id != null ->
                root.findAccessibilityNodeInfosByViewId(id)

            text != null ->
                root.findAccessibilityNodeInfosByText(text)

            contentDesc != null ->
                root.findAccessibilityNodeInfosByText(contentDesc)

            else -> return false
        }

        if (nodes.isEmpty()) return false

        for (node in nodes) {

            var current:
                    AccessibilityNodeInfo? = node

            while (current != null) {

                if (current.isClickable) {

                    return current.performAction(
                        AccessibilityNodeInfo.ACTION_CLICK
                    )
                }

                current = current.parent
            }
        }

        return false
    }

    private fun genericClick(
        text: String
    ): Boolean {
        return click(text = text)
    }

    // ============================
    // GENERIC SEARCH
    // ============================

    private fun genericSearch(
        query: String
    ): Boolean {

        val svc = service ?: return false
        val root = svc.rootInActiveWindow ?: return false

        val searchClicked = ActionExecutor.clickByIntention(svc, "search")
        if (!searchClicked) {
            NodeFinder.findClickable(root, "search")?.let { node ->
                var current: AccessibilityNodeInfo? = node
                while (current != null) {
                    if (current.isClickable) {
                        current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        break
                    }
                    current = current.parent
                }
            }
        }

        val updatedRoot = svc.rootInActiveWindow ?: root
        val editable = findEditableNode(updatedRoot) ?: findEditableNode(root) ?: return false

        val args = android.os.Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                query
            )
        }

        return editable.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            args
        )
    }

    private fun findEditableNode(
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {

        if (node.isEditable)
            return node

        for (i in 0 until node.childCount) {

            val child = node.getChild(i)

            if (child != null) {

                val result =
                    findEditableNode(child)

                if (result != null)
                    return result
            }
        }

        return null
    }

    // ============================
    // VOLUME
    // ============================

    private fun volumeUp(): Boolean {

        val audio =
            service?.getSystemService(
                AudioManager::class.java
            ) ?: return false

        audio.adjustVolume(
            AudioManager.ADJUST_RAISE,
            AudioManager.FLAG_SHOW_UI
        )

        return true
    }

    private fun volumeDown(): Boolean {

        val audio =
            service?.getSystemService(
                AudioManager::class.java
            ) ?: return false

        audio.adjustVolume(
            AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )

        return true
    }

    // ============================
    // SCREENSHOT
    // ============================

    private fun takeScreenshot(): Boolean {
        return try {
            val svc = service ?: return false
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                // Use MediaProjection API for Android 11+
                // For now, try accessibility screenshot
                val root = svc.rootInActiveWindow ?: return false
                // Take screenshot via accessibility
                takeScreenshotLegacy()
            } else {
                takeScreenshotLegacy()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot failed: ${e.message}")
            false
        }
    }

    private fun takeScreenshotLegacy(): Boolean {
        return try {
            val svc = service ?: return false
            // Use global action for screenshot
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
        } catch (e: Exception) {
            Log.e(TAG, "Legacy screenshot failed: ${e.message}")
            false
        }
    }

    // ============================
    // SCROLL
    // ============================

    private fun scrollUp(): Boolean {
        return try {
            val svc = service ?: return false
            val root = svc.rootInActiveWindow ?: return false
            // Find a scrollable node and scroll up
            val scrollable = findScrollableNode(root)
            if (scrollable != null) {
                scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            } else {
                // Fallback: gesture scroll up
                val displayMetrics = svc.resources.displayMetrics
                val x = displayMetrics.widthPixels / 2
                val startY = displayMetrics.heightPixels * 3 / 4
                val endY = displayMetrics.heightPixels / 4
                ActionExecutor.swipe(svc, x, startY, x, endY, 300)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Scroll up failed: ${e.message}")
            false
        }
    }

    private fun scrollDown(): Boolean {
        return try {
            val svc = service ?: return false
            val root = svc.rootInActiveWindow ?: return false
            val scrollable = findScrollableNode(root)
            if (scrollable != null) {
                scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            } else {
                // Fallback: gesture scroll down
                val displayMetrics = svc.resources.displayMetrics
                val x = displayMetrics.widthPixels / 2
                val startY = displayMetrics.heightPixels / 4
                val endY = displayMetrics.heightPixels * 3 / 4
                ActionExecutor.swipe(svc, x, startY, x, endY, 300)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Scroll down failed: ${e.message}")
            false
        }
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findScrollableNode(child)
            if (result != null) return result
        }
        return null
    }

    // ============================
    // NAVIGATION
    // ============================

    private fun goBack(): Boolean {
        return try {
            val svc = service ?: return false
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        } catch (e: Exception) {
            Log.e(TAG, "Back failed: ${e.message}")
            false
        }
    }

    private fun goHome(): Boolean {
        return try {
            val svc = service ?: return false
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        } catch (e: Exception) {
            Log.e(TAG, "Home failed: ${e.message}")
            false
        }
    }

    private fun openNotification(): Boolean {
        return try {
            val svc = service ?: return false
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        } catch (e: Exception) {
            Log.e(TAG, "Notification failed: ${e.message}")
            false
        }
    }
}