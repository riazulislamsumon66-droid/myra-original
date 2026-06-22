package com.maya.assistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.maya.assistant.service.CallMonitorService

class CallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CALL_RCVR"
        private var lastState = TelephonyManager.CALL_STATE_IDLE
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        Log.d(TAG, "Phone state changed: $stateStr, number: $number")

        when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                // Incoming call ringing
                Log.d(TAG, "Incoming call from: $number")
                // Start CallMonitorService to handle the call
                val serviceIntent = Intent(context, CallMonitorService::class.java).apply {
                    putExtra("CALL_STATE", "RINGING")
                    putExtra("PHONE_NUMBER", number ?: "")
                }
                try {
                    context.startForegroundService(serviceIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start CallMonitorService: ${e.message}")
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                // Call answered (incoming or outgoing)
                Log.d(TAG, "Call answered")
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                // Call ended or rejected
                Log.d(TAG, "Call ended")
            }
        }
    }
}
