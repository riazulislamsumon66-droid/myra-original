package com.maya.assistant.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import android.os.BatteryManager
import android.net.wifi.WifiManager
import android.bluetooth.BluetoothAdapter
import android.media.AudioManager
import android.view.WindowManager
import com.maya.assistant.apps.AppLauncher
import com.maya.assistant.models.CommandType
import com.maya.assistant.models.VoiceCommand
import com.maya.assistant.screenvision.OCRProcessor
import com.maya.assistant.screenvision.ScreenCaptureManager
import com.maya.assistant.screenvision.ScreenCaptureService
import com.maya.assistant.screenvision.VisionDecisionEngine
import com.maya.assistant.automation.UiTreeSerializer
import com.maya.assistant.service.SmartAccessibilityEngine
import com.maya.assistant.utils.CalendarManager
import com.maya.assistant.utils.Logger
import com.maya.assistant.utils.PermissionUtils

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

    /** Phrases that indicate failure inside MAYA's own Bangla result strings.
     *  Used to classify success/failure for logging, since branches return
     *  human-readable text rather than a structured result type. */
    private val FAILURE_MARKERS = listOf(
        "সমস্যা", "পাই নাই", "পারি নাই", "পারলাম না", "বন্ধ আছে",
        "নেই", "যায়নি", "করতে পারি নাই"
    )

    suspend fun execute(context: Context, command: VoiceCommand): String {
        val entry = CommandLogger.start(
            commandType = command.type.name,
            rawText = command.raw,
            args = command.args
        )
        return try {
            val result = executeInternal(context, command)
            val looksLikeFailure = FAILURE_MARKERS.any { result.contains(it) }
            if (looksLikeFailure) {
                CommandLogger.failure(entry, result)
            } else {
                CommandLogger.success(entry, result)
            }
            result
        } catch (e: Exception) {
            val msg = "${command.type} চালাতে সমস্যা: ${e.message}"
            Logger.e(TAG, "Unhandled exception executing ${command.type}: ${e.message}", e)
            CommandLogger.failure(entry, e.message ?: e.javaClass.simpleName, e)
            msg
        }
    }

    private suspend fun executeInternal(context: Context, command: VoiceCommand): String {
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
                var name = command.args["name"] ?: ""
                // Clean filler words from contact name
                val nameFillers = listOf("call", "কল", "phone", "ফোন", "dial", "করো", "karo", "koro", "কে", "ke", "to", "the", "a ", "an ")
                for (f in nameFillers) {
                    name = name.replace(f, "", ignoreCase = true)
                }
                name = name.trim().replace(Regex("\\s+"), " ")
                if (name.isEmpty()) {
                    "কন্টাক্ট নাম বলো"
                } else {
                    val number = findContactNumber(context, name)
                    if (number.isNotEmpty()) {
                        // Use CallAutomation for proper permission check + dialer fallback
                        if (com.maya.assistant.automation.CallAutomation.makeCall(context, number)) {
                            "কল করছি: $name"
                        } else {
                            // Fallback: open dialer
                            if (com.maya.assistant.automation.CallAutomation.openDialer(context, number)) {
                                "ডায়ালার খুলে দিলাম: $name"
                            } else {
                                "কল করতে সমস্যা: $name"
                            }
                        }
                    } else {
                        "কন্টাক্ট পাই নাই: $name"
                    }
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
                var query = command.args["query"] ?: ""
                query = cleanMusicQuery(query)
                if (query.isEmpty()) query = command.raw.replace("YOUTUBE_PLAY", "", ignoreCase = true).trim()
                // Final safety: if query still contains command-like words, use raw text
                val dangerousWords = listOf("youtube", "play", "চালাও", "চালো", "play karo", "play koro")
                for (dw in dangerousWords) {
                    query = query.replace(dw, "", ignoreCase = true)
                }
                query = query.trim().replace(Regex("\\s+"), " ")
                if (query.isEmpty()) query = "music"
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
                if (toggleFlashlight(context, true)) "ফ্ল্যাশলাইট চালু করে দিলাম"
                else "ফ্ল্যাশলাইট চালু করতে পারি নাই — Camera permission দরকার"
            }

            CommandType.FLASHLIGHT_OFF -> {
                if (toggleFlashlight(context, false)) "ফ্ল্যাশলাইট বন্ধ করে দিলাম"
                else "ফ্ল্যাশলাইট বন্ধ করতে পারি নাই"
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
                val clicked = SmartAccessibilityEngine.execute("CLICK $target").success
                if (clicked) {
                    "ক্লিক করলাম: $target"
                } else {
                    // OCR fallback: capture screen and find text position
                    val ocrResult = tryOcrClick(target)
                    if (ocrResult) "ক্লিক করলাম (OCR): $target"
                    else "ক্লিক করতে পারি নাই: $target"
                }
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

            CommandType.BATTERY_CHECK -> {
                try {
                    val bm = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                    val level = bm?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = bm?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                    val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                    if (pct >= 0) "ব্যাটারি এখন $pct% আছে"
                    else "ব্যাটারি তথ্য পাওয়া যায়নি"
                } catch (e: Exception) { "ব্যাটারি চেক করতে সমস্যা" }
            }

            CommandType.SETTINGS_OPEN -> {
                try {
                    val intent = Intent(Settings.ACTION_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    "Settings খুলে দিলাম"
                } catch (e: Exception) { "Settings খুলতে সমস্যা" }
            }

            CommandType.SETTINGS_WIFI_ON -> {
                try {
                    val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    "WiFi Settings খুলে দিলাম — চালু করো"
                } catch (e: Exception) { "WiFi Settings খুলতে সমস্যা" }
            }

            CommandType.SETTINGS_WIFI_OFF -> {
                try {
                    val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    "WiFi Settings খুলে দিলাম — বন্ধ করো"
                } catch (e: Exception) { "WiFi Settings খুলতে সমস্যা" }
            }

            CommandType.SETTINGS_BLUETOOTH_ON -> {
                try {
                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    "Bluetooth Settings খুলে দিলাম — চালু করো"
                } catch (e: Exception) { "Bluetooth Settings খুলতে সমস্যা" }
            }

            CommandType.SETTINGS_BLUETOOTH_OFF -> {
                try {
                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    "Bluetooth Settings খুলে দিলাম — বন্ধ করো"
                } catch (e: Exception) { "Bluetooth Settings খুলতে সমস্যা" }
            }

            CommandType.SETTINGS_BRIGHTNESS -> {
                try {
                    val level = command.args["level"] ?: "50"
                    if (level == "up") {
                        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                        val cur = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
                        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (cur + 50).coerceAtMost(255))
                        "ব্রাইটনেস বাড়িয়ে দিলাম"
                    } else if (level == "down") {
                        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                        val cur = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
                        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (cur - 50).coerceAtLeast(10))
                        "ব্রাইটনেস কমিয়ে দিলাম"
                    } else {
                        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (level.toIntOrNull() ?: 50).coerceIn(10, 255))
                        "ব্রাইটনেস সেট করলাম"
                    }
                } catch (e: Exception) { "ব্রাইটনেস পরিবর্তন করতে সমস্যা — WRITE_SETTINGS permission দরকার" }
            }

            CommandType.WHATSAPP_CALL -> {
                val name = command.args["name"] ?: ""
                // Open WhatsApp, find contact, use accessibility to click call button
                if (AppLauncher.launch(context, "whatsapp")) {
                    scope.launch {
                        kotlinx.coroutines.delay(2000)
                        SmartAccessibilityEngine.execute("SEARCH $name")
                        kotlinx.coroutines.delay(1500)
                        SmartAccessibilityEngine.execute("CLICK call")
                    }
                    "WhatsApp এ কল করছি: $name"
                } else {
                    "WhatsApp খুলতে পারি নাই"
                }
            }

            CommandType.IMO_CALL -> {
                val name = command.args["name"] ?: ""
                if (AppLauncher.launch(context, "imo")) {
                    "IMO খুলে দিলাম — $name কে করো"
                } else {
                    "IMO অ্যাপ পাওয়া যায়নি"
                }
            }

            CommandType.MESSENGER_CALL -> {
                val name = command.args["name"] ?: ""
                if (AppLauncher.launch(context, "messenger")) {
                    "Messenger খুলে দিলাম — $name কে করো"
                } else {
                    "Messenger অ্যাপ পাওয়া যায়নি"
                }
            }

            CommandType.TELEGRAM_CALL -> {
                val name = command.args["name"] ?: ""
                if (AppLauncher.launch(context, "telegram")) {
                    "Telegram খুলে দিলাম — $name কে করো"
                } else {
                    "Telegram অ্যাপ পাওয়া যায়নি"
                }
            }

            CommandType.MUSIC_PLAY -> {
                var query = command.args["query"] ?: ""
                query = cleanMusicQuery(query)
                if (query.isEmpty()) query = command.raw.replace("MUSIC_PLAY", "", ignoreCase = true).trim()
                // Try Spotify deep link first, fallback to YouTube
                try {
                    val spotifyIntent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$query"))
                    spotifyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(spotifyIntent)
                    "Spotify তে চালাচ্ছি: $query"
                } catch (e: Exception) {
                    // Fallback to YouTube
                    try {
                        val ytIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}"))
                        ytIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(ytIntent)
                        "YouTube তে চালাচ্ছি: $query"
                    } catch (e2: Exception) {
                        "মিউজিক চালাতে সমস্যা"
                    }
                }
            }

            CommandType.SPOTIFY_PLAY -> {
                var query = command.args["query"] ?: ""
                query = cleanMusicQuery(query)
                if (query.isEmpty()) query = command.raw.replace("SPOTIFY_PLAY", "", ignoreCase = true).trim()
                // Use Spotify deep link with search query
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$query"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    "Spotify তে চালাচ্ছি: $query"
                } catch (_: Exception) {
                    // Fallback: open Spotify app with query in search
                    if (AppLauncher.launch(context, "spotify")) {
                        scope.launch {
                            kotlinx.coroutines.delay(1500)
                            SmartAccessibilityEngine.execute("SEARCH $query")
                        }
                        "Spotify খুলে দিলাম — খুঁজো: $query"
                    } else {
                        "Spotify খুলতে পারি নাই"
                    }
                }
            }

            CommandType.READ_SCREEN -> {
                // ১. Try accessibility tree first
                val service = SmartAccessibilityEngine.service
                val root = service?.rootInActiveWindow
                if (root != null) {
                    val text = UiTreeSerializer.extractAllText(root)
                    if (text.isNotBlank()) {
                        "স্ক্রিনে আছে:\n$text"
                    } else {
                        // ২. OCR fallback
                        val captureMgr = ScreenCaptureService.captureManager
                        if (captureMgr != null) {
                            val bitmap = captureMgr.captureFrame()
                            if (bitmap != null) {
                                val ocrText = OCRProcessor.extractTextSuspend(bitmap)
                                if (ocrText.isNotBlank()) "স্ক্রিনে লেখা আছে (OCR):\n$ocrText"
                                else "স্ক্রিনে কোনো লেখা পাওয়া যায়নি"
                            } else "Screen Capture চালু নেই — স্ক্রিন পড়তে পারি নাই"
                        } else "Screen Vision বন্ধ আছে — Settings থেকে চালু করো"
                    }
                } else {
                    // ৩. No accessibility — try OCR directly
                    val captureMgr = ScreenCaptureService.captureManager
                    if (captureMgr != null) {
                        val bitmap = captureMgr.captureFrame()
                        if (bitmap != null) {
                            val ocrText = OCRProcessor.extractTextSuspend(bitmap)
                            if (ocrText.isNotBlank()) "স্ক্রিনে লেখা আছে (OCR):\n$ocrText"
                            else "স্ক্রিনে কোনো লেখা পাওয়া যায়নি"
                        } else "Screen Capture চালু নেই"
                    } else "Accessibility Service বা Screen Vision চালু নেই"
                }
            }

            // Calendar commands
            CommandType.CALENDAR_TODAY -> {
                val events = CalendarManager.getTodayEvents(context)
                CalendarManager.formatEventsBangla(events, "আজ")
            }

            CommandType.CALENDAR_UPCOMING -> {
                val events = CalendarManager.getUpcomingEvents(context, 7)
                CalendarManager.formatEventsBangla(events, "আগামী ৭ দিন")
            }

            CommandType.CALENDAR_CREATE -> {
                // Try to parse "reminder X at HH:MM" or just create a reminder
                val reminderResult = CalendarManager.createReminder(context, command.raw, 30)
                if (reminderResult) "⏰ রিমাইন্ডার সেট হয়েছে (৩০ মিনিট পর)"
                else "রিমাইন্ডার সেট করতে সমস্যা"
            }

            // Face recognition commands
            CommandType.REGISTER_FACE -> {
                "মুখ সংরক্ষণের জন্য Settings → Face Recognition এ যাও। সেখানে 'নতুন মুখ যোগ করো' বাটনে ক্লিক করো।"
            }

            CommandType.RECOGNIZE_FACE -> {
                "চেহরা শনাক্ত করার জন্য Settings → Face Recognition → স্ক্যান করো। সম্পূর্ণ ফিচার শীঘ্রই আসছে!"
            }

            CommandType.IDENTIFY_SPEAKER -> {
                // Trigger voice identification
                val voiceId = com.maya.assistant.voice.VoiceIdentifier(context)
                val enrolled = voiceId.getEnrolledProfiles()
                if (enrolled.isNotEmpty()) {
                    voiceId.recognizeVoice { result ->
                        // Result handled via broadcast from ForegroundVoiceService
                    }
                    "বক্তা শনাক্ত করছি..."
                } else {
                    "কোনো কণ্ঠ সংরক্ষিত নেই। Settings → Voice Enrollment এ যাও।"
                }
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

    private fun toggleFlashlight(context: Context, on: Boolean): Boolean {
        // Check CAMERA permission first
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Logger.e(TAG, "CAMERA permission not granted for flashlight")
            return false
        }
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            // Find the actual rear/torch-capable camera — not just the first one
            // Samsung S21 Ultra has multiple cameras; first ID may not support torch
            var torchCameraId: String? = null
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                val hasFlash = chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE)
                if (hasFlash == true) {
                    torchCameraId = id
                    break
                }
            }
            if (torchCameraId == null) {
                Logger.e(TAG, "No torch-capable camera found")
                return false
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                cm.setTorchMode(torchCameraId, on)
                Logger.d(TAG, "Flashlight ${if (on) "ON" else "OFF"} — camera: $torchCameraId")
                return true
            }
            return false
        } catch (e: Exception) {
            Logger.e(TAG, "Flashlight error: ${e.message}")
            return false
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

    /** OCR fallback: capture screen frame, run OCR, find target text, click by coordinates */
    private fun tryOcrClick(target: String): Boolean {
        return try {
            val captureMgr = ScreenCaptureService.captureManager ?: return false
            val bitmap = captureMgr.captureFrame() ?: return false
            // Use VisionDecisionEngine which parses screen hierarchy and finds clickable targets
            VisionDecisionEngine.executeVisualAction(target)
        } catch (e: Exception) {
            Logger.e(TAG, "OCR click failed: ${e.message}")
            false
        }
    }

    /** Clean filler words from music/video query */
    private fun cleanMusicQuery(query: String): String {
        var result = query
        val fillers = listOf(
            "youtube এ", "youtube-এ", "youtube te", "ইউটিউবে", "ইউটিউব",
            "spotify এ", "spotify-এ", "spotify te",
            "play করো", "চালাও", "চালো", "দেখাও", "play karo", "play koro",
            "একটা", "একটি", "ekta",
            "দাও", "show", "search", "find", "bajao", "বাজাও"
        )
        for (f in fillers) {
            result = result.replace(f, "", ignoreCase = true)
        }
        return result.trim().replace(Regex("\\s+"), " ")
    }
}
