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

        val appName = getAppName(pkg)
        val notificationText = "🔔 $appId: $text"

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
            sendBroadcast(Intent("MAYA_RESPONSE").apply {
                putExtra("text", responseText)
                putExtra("is_result", true)
            })
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
}
