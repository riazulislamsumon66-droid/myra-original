package com.maya.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.maya.assistant.services.ForegroundVoiceService
import com.maya.assistant.utils.Constants
import com.maya.assistant.utils.PermissionUtils

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val apiKey = prefs.getString(Constants.KEY_API_KEY, "") ?: ""
            if (apiKey.isNotEmpty()) {
                // Check mic permission before starting voice service
                if (PermissionUtils.hasMicPermission(context)) {
                    ContextCompat.startForegroundService(context, Intent(context, ForegroundVoiceService::class.java))
                }
            }
            // Also start call monitoring on boot
            try {
                ContextCompat.startForegroundService(context, Intent(context, CallMonitorService::class.java))
            } catch (e: Exception) {
                android.util.Log.e("BOOT", "Failed to start CallMonitorService: ${e.message}")
            }
        }
    }
}
