package com.maya.assistant.service

import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.Build
import android.util.Log
import com.maya.assistant.ai.AIResponseManager
import com.maya.assistant.ai.DynamicDecisionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * NotificationListenerService — reads notifications from other apps.
 * When WhatsApp/SMS/other notifications arrive, MAYA can read them aloud.
 */
class NotificationReaderService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationReader"
        private var instance: NotificationReaderService? = null
        private var isActive = false
        private var lastNotifPackage: String? = null
        private var lastNotifTime = 0L
        private const val DEBOUNCE_MS = 3000L // Prevent duplicate reads

        fun isRunning() = isActive
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        isActive = true
        Log.i(TAG, "Notification Reader connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val prefs = getSharedPreferences("maya_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notification_read_enabled", true)) return

        // Debounce same notification
        val pkg = sbn.packageName
        val now = System.currentTimeMillis()
        if (pkg == lastNotifPackage && (now - lastNotifTime) < DEBOUNCE_MS) return

        // Skip our own notifications
        if (pkg == packageName) return

        // Skip system notifications
        if (pkg == "android" || pkg == "com.android.systemui") return

        lastNotifPackage = pkg
        lastNotifTime = now

        // Extract notification text
        val extras = sbn.notification.extras
        val title = extras.containsKey("android.title")
        val text = extras.getCharSequence("android.text")?.toString() ?: return

        if (text.isBlank()) return

        // Privacy safeguard: don't read OTP/verification-code notifications
        // aloud or broadcast them — these are exactly the messages that
        // shouldn't be spoken where a bystander could overhear them.
        if (looksLikeSensitiveCode(text)) {
            Log.d(TAG, "Skipping sensitive-looking notification from $pkg")
            return
        }

        val appName = getAppName(pkg)
        val notificationText = "🔔 $appName: $text"

        Log.d(TAG, "Notification from $appName: $text")

        // Broadcast to UI
        sendBroadcast(Intent("MAYA_NOTIFICATION").apply {
            putExtra("app_name", appName)
            putExtra("notification_text", text)
            putExtra("package", pkg)
        })

        // Read aloud if user has enabled
        if (prefs.getBoolean("notification_read_aloud", false)) {
            val responseText = "📢 $appName থেকে বার্তা: $text"
            // NOTE: previously this only sent a MAYA_RESPONSE broadcast,
            // which MainActivity displays as a chat bubble but never speaks.
            // Routing through sendTextToGemini() uses the same TTS path as
            // every other spoken reply, so "read aloud" actually reads aloud.
            val voiceService = com.maya.assistant.services.ForegroundVoiceService.instance
            if (voiceService != null) {
                voiceService.sendTextToGemini("এই নোটিফিকেশনটা সংক্ষেপে আমাকে পড়ে শোনাও: $responseText")
            } else {
                // Voice service not running — fall back to the text-only
                // broadcast so the notification is at least visible.
                sendBroadcast(Intent("MAYA_RESPONSE").apply {
                    putExtra("text", responseText)
                    putExtra("is_result", true)
                })
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        isActive = false
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    private fun looksLikeSensitiveCode(text: String): Boolean {
        val lower = text.lowercase()
        val keywords = listOf(
            "otp", "one-time password", "one time password", "verification code",
            "security code", "passcode", "pin code", "২fa", "2fa",
            "যাচাইকরণ কোড", "নিরাপত্তা কোড"
        )
        if (keywords.any { lower.contains(it) }) return true
        // A standalone 4-8 digit number alongside the message is also a
        // strong OTP signal (e.g. "123456 is your verification code").
        if (Regex("\\b\\d{4,8}\\b").containsMatchIn(text) &&
            (lower.contains("code") || lower.contains("otp") || lower.contains("verify"))) return true
        return false
    }
}
