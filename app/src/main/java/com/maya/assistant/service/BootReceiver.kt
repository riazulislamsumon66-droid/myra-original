package com.maya.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.maya.assistant.services.ForegroundVoiceService
import com.maya.assistant.utils.Constants
import com.maya.assistant.utils.PermissionUtils
import com.maya.assistant.security.SecurePrefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            // Try encrypted prefs first, then plain text as fallback
            val apiKey = SecurePrefs.getApiKey(context).ifEmpty {
                context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(Constants.KEY_API_KEY, "") ?: ""
            }
            if (apiKey.isNotEmpty()) {
                // Check mic permission before starting voice service
                if (PermissionUtils.hasMicPermission(context)) {
                    ContextCompat.startForegroundService(context, Intent(context, ForegroundVoiceService::class.java))
                }
            }
            // Also start call monitoring on boot
            if (android.Manifest.permission.MANAGE_OWN_CALLS.let {
                    androidx.core.content.ContextCompat.checkSelfPermission(context, it)
                } == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try {
                    ContextCompat.startForegroundService(context, Intent(context, CallMonitorService::class.java))
                } catch (e: Exception) {
                    android.util.Log.e("BOOT", "Failed to start CallMonitorService: ${e.message}")
                }
            } else {
                android.util.Log.w("BOOT", "MANAGE_OWN_CALLS not granted, CallMonitorService not started")
            }
        }
    }
}
