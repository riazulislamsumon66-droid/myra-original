package com.maya.assistant.overlay

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.maya.assistant.service.MayaOverlayService
import com.maya.assistant.utils.Constants

object FloatingAssistantController {

    fun show(context: Context) {
        if (!OverlayPermissionManager.hasPermission(context)) {
            OverlayPermissionManager.requestPermission(context)
            return
        }
        val i = Intent(context, MayaOverlayService::class.java).apply {
            action = Constants.ACTION_SHOW_OVERLAY
        }
        ContextCompat.startForegroundService(context, i)
    }

    fun hide(context: Context) {
        context.startService(Intent(context, MayaOverlayService::class.java).apply {
            action = Constants.ACTION_HIDE_OVERLAY
        })
    }

    fun toggle(context: Context) {
        val i = Intent(context, MayaOverlayService::class.java).apply {
            action = Constants.ACTION_TOGGLE_OVERLAY
        }
        ContextCompat.startForegroundService(context, i)
    }

    fun isRunning() = MayaOverlayService.isRunning
}
