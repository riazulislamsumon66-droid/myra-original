package com.maya.assistant.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import com.maya.assistant.apps.AppLauncher
import com.maya.assistant.models.CommandType
import com.maya.assistant.models.VoiceCommand
import com.maya.assistant.service.SmartAccessibilityEngine
import com.maya.assistant.utils.Logger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo

object DynamicDecisionEngine {
    private val TAG = "DECISION"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    suspend fun execute(context: Context, command: VoiceCommand): String {
        Logger.d(TAG, "Executing: ${command.type} - ${command.raw}")

        return when (command.type) {
            CommandType.OPEN_APP -> {
                val app = command.args["app"] ?: ""
                if (AppLauncher.launch(context, app)) "খুলে দিলাম: $app"
                else "অ্যাপ পাই নাই: $app"
            }

            CommandType.CLOSE_APP -> {
                val app = command.args["app"] ?: ""
                if (AppLauncher.close(context, app)) "বন্ধ করে দিলাম: $app"
                else {
                    // Fallback: try recents swipe gesture
                    if (AppLauncher.closeViaRecents(context, app)) "বন্ধ করে দিলাম (recents): $app"
                    else "বন্ধ করতে পারি নাই: $app"
                }
            }

            CommandType.CALL -> {
                val name = command.args["name"] ?: ""
                val number = findContactNumber(context, name)
                if (number.isNotEmpty()) {
                    val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(intent)
                        "কল করছি: $name"
                    } catch (e: Exception) {
                        "কল করতে সমস্যা: ${e.message}"
                    }
                } else {
                    "কন্টাক্ট পাই নাই: $name"
                }
            }

            CommandType.WHATSAPP_CALL -> {
                val name = command.args["name"] ?: ""
                // Open WhatsApp chat with contact
                try {
                    val number = findContactNumber(context, name)
                    if (number.isNotEmpty()) {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number"))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        "WhatsApp কল করছি: $name"
                    } else {
                        // Try opening WhatsApp directly
                        AppLauncher.launch(context, "whatsapp")
                        "WhatsApp খুলে দিলাম"
                    }
                } catch (e: Exception) {
                    "WhatsApp কল করতে সমস্যা"
                }
            }

            CommandType.WHATSAPP_MSG -> {
                val name = command.args["name"] ?: ""
                val msg = command.args["message"] ?: ""
                try {
                    val number = findContactNumber(context, name)
                    if (number.isNotEmpty()) {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number?text=${Uri.encode(msg)}"))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        "WhatsApp মেসেজ পাঠাচ্ছি: $name"
                    } else {
                        AppLauncher.launch(context, "whatsapp")
                        "WhatsApp খুলে দিলাম"
                    }
                } catch (e: Exception) {
                    "WhatsApp মেসেজ পাঠাতে সমস্যা"
                }
            }

            CommandType.YOUTUBE_PLAY -> {
                val query = command.args["query"] ?: ""
                try {
                    // Launch YouTube app with search query directly
                    val ytSearchIntent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
                        setPackage("com.google.android.youtube")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(ytSearchIntent)
                    // Auto-click first result after delay using accessibility
                    scope.launch {
                        kotlinx.coroutines.delay(5000)
                        try {
                            val svc = SmartAccessibilityEngine.service
                            if (svc != null) {
                                val root = svc.rootInActiveWindow
                                if (root != null) {
                                    val videoNode = findFirstVideoResult(root, 0)
                                    if (videoNode != null) {
                                        videoNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                        Logger.d(TAG, "Auto-clicked first YouTube result")
                                    } else {
                                        Logger.d(TAG, "No video result found in accessibility tree")
                                    }
                                    root.recycle()
                                }
                            }
                        } catch (ex: Exception) {
                            Logger.e(TAG, "Auto-click failed: ${ex.message}")
                        }
                    }
                    "YouTube এ চালাচ্ছি: $query"
                } catch (e: android.content.ActivityNotFoundException) {
                    // YouTube app not installed — fallback to browser
                    try {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}"))
                        browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(browserIntent)
                        "YouTube ওয়েব এ খুঁজছি: $query"
                    } catch (e2: Exception) {
                        "YouTube খুলতে সমস্যা — অ্যাপ বা ব্রাউজার পাওয়া যায়নি"
                    }
                } catch (e: Exception) {
                    "YouTube খুলতে সমস্যা: ${e.message}"
                }
            }

            CommandType.SPOTIFY_PLAY -> {
                val query = command.args["query"] ?: ""
                if (AppLauncher.launch(context, "spotify")) {
                    "Spotify খুলে দিলাম"
                } else {
                    // Fallback: open web player
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/$query"))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        "Spotify ওয়েব খুলে দিলাম"
                    } catch (e: Exception) {
                        "Spotify খুলতে সমস্যা"
                    }
                }
            }

            CommandType.SMS -> {
                val name = command.args["name"] ?: ""
                val msg = command.args["message"] ?: ""
                val number = findContactNumber(context, name)
                if (number.isNotEmpty()) {
                    try {
                        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number"))
                        intent.putExtra("sms_body", msg)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        "SMS পাঠাচ্ছি: $name"
                    } catch (e: Exception) {
                        "SMS পাঠাতে সমস্যা"
                    }
                } else {
                    "কন্টাক্ট পাই নাই: $name"
                }
            }

            CommandType.VOLUME_UP -> {
                SmartAccessibilityEngine.execute("VOLUME_UP")
                "ভলিউম বাড়িয়ে দিলাম"
            }

            CommandType.VOLUME_DOWN -> {
                SmartAccessibilityEngine.execute("VOLUME_DOWN")
                "ভলিউম কমিয়ে দিলাম"
            }

            CommandType.FLASHLIGHT_ON -> {
                toggleFlashlight(context, true)
                "ফ্ল্যাশলাইট চালু করে দিলাম"
            }

            CommandType.FLASHLIGHT_OFF -> {
                toggleFlashlight(context, false)
                "ফ্ল্যাশলাইট বন্ধ করে দিলাম"
            }

            CommandType.SCREENSHOT -> {
                if (SmartAccessibilityEngine.execute("SCREENSHOT").success) "স্ক্রিনশট নিলাম"
                else "স্ক্রিনশট নিতে পারি নাই"
            }

            CommandType.SCROLL_UP -> {
                if (SmartAccessibilityEngine.execute("SCROLL_UP").success) "উপরে স্ক্রল করলাম"
                else "স্ক্রল করতে পারি নাই"
            }

            CommandType.SCROLL_DOWN -> {
                if (SmartAccessibilityEngine.execute("SCROLL_DOWN").success) "নিচে স্ক্রল করলাম"
                else "স্ক্রল করতে পারি নাই"
            }

            CommandType.NAVIGATE -> {
                val action = command.args["action"] ?: ""
                when (action) {
                    "back" -> { SmartAccessibilityEngine.execute("BACK"); "পেছনে গেলাম" }
                    "home" -> { SmartAccessibilityEngine.execute("HOME"); "হোমে গেলাম" }
                    "notification" -> { SmartAccessibilityEngine.execute("NOTIFICATION"); "নোটিফিকেশন খুললাম" }
                    else -> ""
                }
            }

            CommandType.CLICK -> {
                val target = command.args["target"] ?: ""
                if (SmartAccessibilityEngine.execute("CLICK $target").success) "ক্লিক করলাম: $target"
                else "ক্লিক করতে পারি নাই: $target"
            }

            CommandType.SEARCH -> {
                val query = command.args["query"] ?: ""
                if (SmartAccessibilityEngine.execute("SEARCH $query").success) "সার্চ করলাম: $query"
                else "সার্চ করতে পারি নাই: $query"
            }

            CommandType.TYPE_TEXT -> {
                val text = command.args["text"] ?: ""
                if (SmartAccessibilityEngine.execute("TYPE_TEXT $text").success) "লিখে দিলাম: $text"
                else "লিখতে পারি নাই"
            }

            else -> ""
        }
    }

    private fun findContactNumber(context: Context, name: String): String {
        if (name.isEmpty()) return ""
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    return it.getString(0).replace(" ", "").replace("-", "")
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Contact lookup failed: ${e.message}")
        }
        return ""
    }

    private fun toggleFlashlight(context: Context, on: Boolean) {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cm.cameraIdList.firstOrNull() ?: return
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                cm.setTorchMode(cameraId, on)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Flashlight error: ${e.message}")
        }
    }

    private fun findFirstVideoResult(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        // Guard against deep recursion (YouTube tree can be huge)
        if (depth > 15) return null
        if (node.isClickable && node.text != null) {
            val text = node.text.toString()
            if (text.length > 10 && !text.startsWith("http")) {
                return node
            }
        }
        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstVideoResult(child, depth + 1)
            if (result != null) {
                // Don't recycle the result node — caller will use it
                // But recycle remaining siblings
                for (j in i + 1 until node.childCount) {
                    node.getChild(j)?.recycle()
                }
                return result
            }
            child.recycle()
        }
        return null
    }
}
