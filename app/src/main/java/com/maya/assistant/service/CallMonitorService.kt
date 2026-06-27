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
import java.util.concurrent.Executors

class CallMonitorService : Service(), TextToSpeech.OnInitListener {

    private var telephonyManager: TelephonyManager? = null
    private var phoneListener: PhoneStateListener? = null
    private var telephonyCallback: android.telephony.TelephonyCallback? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var announced = false
    private var lastPhoneNumber: String? = null

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
        startForegroundService()
        tts = TextToSpeech(this, this)
        setupPhoneListener()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("bn", "BD"))
            ttsReady = (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED)
            if (!ttsReady) {
                tts?.setLanguage(Locale.ENGLISH)
                ttsReady = true
            }
        }
    }

    private fun setupPhoneListener() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val executor = Executors.newSingleThreadExecutor()
                val callback = object : android.telephony.TelephonyCallback(),
                    android.telephony.TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        if (state == TelephonyManager.CALL_STATE_RINGING) {
                            // Android 12+: EXTRA_INCOMING_NUMBER blocked by privacy policy.
                            // Strategy: wait 600ms for CallLog to update, then read number.
                            // For VoIP calls (FB/WA/IMO), CallLog won't have it — fall back
                            // to NotificationReaderService which captures the caller name
                            // from the incoming call notification.
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                val number = getLastIncomingNumber()
                                val callerInfo = if (number.isNullOrEmpty()) {
                                    // VoIP call (FB/WA/IMO) — try notification-based name
                                    getCallerFromNotification()
                                } else number
                                handleCallState(state, callerInfo)
                            }, 600)
                        } else {
                            handleCallState(state, null)
                        }
                    }
                }
                telephonyCallback = callback
                telephonyManager?.registerTelephonyCallback(executor, callback)
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
                    lastPhoneNumber = number
                    handleIncomingCall(number)
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                sendBroadcast(Intent(ACTION_CALL_ACTIVE))
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                announced = false
                lastPhoneNumber = null
                sendBroadcast(Intent(ACTION_CALL_ENDED))
            }
        }
        lastState = state
    }

    private fun handleIncomingCall(number: String?) {
        // number may be: a phone number, a display name (from notification), or null
        val finalNumber = when {
            !number.isNullOrEmpty() -> number
            else -> getLastIncomingNumber() ?: getCallerFromNotification()
        }

        val callerName = resolveCallerName(finalNumber)
        Log.d(TAG, "Incoming: $callerName from $finalNumber")

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

        val intent = Intent(this, CallAssistantActivity::class.java).apply {
            putExtra("CALLER_NAME", callerName)
            putExtra("PHONE_NUMBER", finalNumber ?: "")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    /**
     * Resolves a caller name from either:
     * - A phone number → looks up in Contacts
     * - A display name string (from VoIP notification) → returns as-is
     * - null/empty → returns "অজানা নম্বর"
     */
    private fun resolveCallerName(number: String?): String {
        if (number.isNullOrEmpty()) return "অজানা নম্বর"

        // If it looks like a display name (not a phone number), return directly
        // Phone numbers: digits, +, -, spaces only
        val isPhoneNumber = number.matches(Regex("[+\\d\\s\\-()]{5,20}"))
        if (!isPhoneNumber) return number  // Already a name from notification

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return number
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
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }

            // Also try: if number is saved without country code, try with +880
            if (!number.startsWith("+")) {
                val uri2 = android.net.Uri.withAppendedPath(
                    android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    android.net.Uri.encode("+880$number")
                )
                contentResolver.query(
                    uri2,
                    arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }

            number // Return number itself if no contact found
        } catch (e: Exception) {
            Log.e(TAG, "Contact lookup error: ${e.message}")
            number
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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
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
     * Reads the caller name from the most recent active call notification.
     * Works for Facebook, WhatsApp, IMO, Telegram calls — any VoIP app
     * that posts a "Incoming call from X" style notification.
     * Requires Notification Listener permission to be granted.
     */
    private fun getCallerFromNotification(): String? {
        return try {
            val sbn = com.maya.assistant.service.NotificationReaderService.getLastCallNotification()
            if (sbn != null) {
                val extras = sbn.notification.extras
                // Try title first (usually "Incoming call" or caller name)
                val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
                val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
                val bigText = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()

                // Find the actual caller name — remove generic phrases
                val candidates = listOf(title, text, bigText).filterNotNull()
                val callKeywords = listOf(
                    "incoming call", "video call", "voice call", "audio call",
                    "calling", "is calling", "wants to video chat", "wants to talk",
                    "incoming video call", "incoming voice call"
                )
                for (candidate in candidates) {
                    var clean = candidate
                    for (kw in callKeywords) {
                        clean = clean.replace(kw, "", ignoreCase = true).trim()
                    }
                    // Remove trailing punctuation
                    clean = clean.trimEnd('.', ',', ':', '-').trim()
                    if (clean.isNotEmpty() && clean.length < 50) {
                        Log.d(TAG, "Caller name from notification: $clean (from: $candidate)")
                        return clean
                    }
                }
                null
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "getCallerFromNotification error: ${e.message}")
            null
        }
    }

    private fun getLastIncomingNumber(): String? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            // First check: recent incoming calls (within last 60 seconds)
            val recentCutoff = System.currentTimeMillis() - 60_000
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                "${CallLog.Calls.TYPE} = ${CallLog.Calls.INCOMING_TYPE} AND ${CallLog.Calls.DATE} > ?",
                arrayOf(recentCutoff.toString()),
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
            telephonyCallback?.let {
                telephonyManager?.unregisterTelephonyCallback(it)
            }
        } else {
            @Suppress("DEPRECATION")
            telephonyManager?.listen(phoneListener, PhoneStateListener.LISTEN_NONE)
        }
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
