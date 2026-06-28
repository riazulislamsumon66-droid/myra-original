package com.maya.assistant.service

import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.Build
import android.util.Log
import com.maya.assistant.ai.AIResponseManager
import com.maya.assistant.ai.DynamicDecisionEngine
import com.maya.assistant.services.ForegroundVoiceService
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

        // VoIP call notification tracking — for FB/WA/IMO/Telegram incoming calls
        private var lastCallNotification: StatusBarNotification? = null
        private val VOIP_PACKAGES = setOf(
            "com.facebook.orca",          // Messenger
            "com.facebook.katana",        // Facebook
            "com.whatsapp",               // WhatsApp
            "com.whatsapp.w4b",           // WhatsApp Business
            "org.telegram.messenger",     // Telegram
            "com.imo.android.imoim",      // IMO
            "com.viber.voip",             // Viber
            "com.skype.raider",           // Skype
            "us.zoom.videomeetings"       // Zoom
        )
        private val CALL_NOTIFICATION_KEYWORDS = listOf(
            "incoming call", "video call", "voice call", "audio call",
            "is calling", "calling you", "wants to video chat",
            "কল আসছে", "ভিডিও কল", "অডিও কল"
        )

        /**
         * Returns the most recent VoIP incoming call notification.
         * Called by CallMonitorService to get the caller name for
         * Facebook/WhatsApp/IMO/Telegram calls that don't go through
         * the phone network (so have no phone number in CallLog).
         */
        fun getLastCallNotification(): StatusBarNotification? {
            val sbn = lastCallNotification ?: return null
            // Only return if it's recent (within last 10 seconds)
            val age = System.currentTimeMillis() - sbn.postTime
            return if (age < 10_000L) sbn else null
        }

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

        // Track VoIP call notifications for caller name resolution
        if (VOIP_PACKAGES.contains(pkg)) {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
            val combined = "$title $text".lowercase()
            if (CALL_NOTIFICATION_KEYWORDS.any { combined.contains(it) }) {
                Log.d(TAG, "VoIP call notification detected from $pkg: title='$title' text='$text'")
                lastCallNotification = sbn
            }
        }

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
            val voiceService = ForegroundVoiceService.instance
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
