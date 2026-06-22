package com.maya.assistant.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.provider.CallLog
import com.maya.assistant.R
import com.maya.assistant.ui.main.CallAssistantActivity
import java.util.*

class CallMonitorService : Service(), TextToSpeech.OnInitListener {

    private var telephonyManager: TelephonyManager? = null
    private var phoneListener: PhoneStateListener? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var announced = false

    companion object {
        const val ACTION_CALL_ACTIVE = "com.maya.assistant.CALL_ACTIVE"
        const val ACTION_CALL_ENDED = "com.maya.assistant.CALL_ENDED"
        const val ACTION_CALL_RINGING = "com.maya.assistant.CALL_RINGING"
        private const val CHANNEL_ID = "maya_call_channel"
        private const val TAG = "MAYA_CALL"
        private const val NOTIF_ID = 1003
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Check required permissions before starting foreground
        if (!hasCallPermissions()) {
            Log.e(TAG, "Missing FOREGROUND_SERVICE_PHONE_CALL or MANAGE_OWN_CALLS permission — stopping service")
            stopSelf()
            return
        }
        startForegroundService()
        tts = TextToSpeech(this, this)
        setupPhoneListener()
    }

    private fun hasCallPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.MANAGE_OWN_CALLS) == PackageManager.PERMISSION_GRANTED
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("bn", "BD"))
            ttsReady = (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED)
            if (!ttsReady) {
                // Fallback to English
                tts?.setLanguage(Locale.ENGLISH)
                ttsReady = true
            }
        }
    }

    private fun setupPhoneListener() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ — use TelephonyCallback
            try {
                val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                telephonyManager?.registerTelephonyCallback(
                    executor,
                    object : android.telephony.TelephonyCallback(),
                        android.telephony.TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(state: Int) {
                            // Android 12+: number not in callback, fetch from call log
                            val number = if (state == TelephonyManager.CALL_STATE_RINGING) {
                                getLastIncomingNumber()
                            } else null
                            handleCallState(state, number)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "TelephonyCallback failed, using legacy: ${e.message}")
                setupLegacyPhoneListener()
            }
        } else {
            setupLegacyPhoneListener()
        }
    }

    private fun setupLegacyPhoneListener() {
        phoneListener = object : PhoneStateListener() {
            @Suppress("DEPRECATION")
            override fun onCallStateChanged(state: Int, number: String?) {
                handleCallState(state, number)
            }
        }
        try {
            @Suppress("DEPRECATION")
            telephonyManager?.listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for PhoneStateListener: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup legacy phone listener: ${e.message}")
        }
    }

    private fun handleCallState(state: Int, number: String?) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                if (!announced) {
                    announced = true
                    handleIncomingCall(number)
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                sendBroadcast(Intent(ACTION_CALL_ACTIVE))
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                announced = false
                sendBroadcast(Intent(ACTION_CALL_ENDED))
            }
        }
        lastState = state
    }

    private fun handleIncomingCall(number: String?) {
        val callerName = resolveCallerName(number)
        Log.d(TAG, "Incoming: $callerName from $number")

        // Announce via TTS (check user preference)
        val announceOn = getSharedPreferences("maya_prefs", MODE_PRIVATE)
            .getBoolean("call_announce_enabled", true)
        if (ttsReady && announceOn) {
            val announceText = "কল আসছে: $callerName"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts?.speak(announceText, TextToSpeech.QUEUE_FLUSH, null, "call_announce")
            } else {
                @Suppress("DEPRECATION")
                tts?.speak(announceText, TextToSpeech.QUEUE_FLUSH, null)
            }
        }

        // Open CallAssistantActivity
        val intent = Intent(this, CallAssistantActivity::class.java).apply {
            putExtra("CALLER_NAME", callerName)
            putExtra("PHONE_NUMBER", number ?: "")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    private fun resolveCallerName(number: String?): String {
        if (number.isNullOrEmpty()) return "অজানা নম্বর"

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return "অজানা নম্বর"
        }

        return try {
            val uri = android.net.Uri.withAppendedPath(
                android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(number)
            )

            contentResolver.query(
                uri,
                arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0) ?: "অজানা নম্বর"
                } else {
                    "অজানা নম্বর"
                }
            } ?: "অজানা নম্বর"

        } catch (e: Exception) {
            Log.e(TAG, "Contact lookup error: ${e.message}")
            "অজানা নম্বর"
        }
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MAYA Call Monitor")
            .setContentText("কল মনিটরিং সক্রিয়")
            .setSmallIcon(R.mipmap.img)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MAYA Call Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null


    /**
     * Android 12+ এ TelephonyCallback এ phone number আসে না।
     * Call log থেকে সর্বশেষ incoming number পড়ে।
     */
    private fun getLastIncomingNumber(): String? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED) return null
        return try {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE),
                "${CallLog.Calls.TYPE} = ${CallLog.Calls.INCOMING_TYPE}",
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getLastIncomingNumber error: ${e.message}")
            null
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // TelephonyCallback is unregistered automatically
        } else {
            @Suppress("DEPRECATION")
            telephonyManager?.listen(phoneListener, PhoneStateListener.LISTEN_NONE)
        }
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
