package com.maya.assistant.voice

import android.content.Context
import android.util.Log

/**
 * VoiceAuthManager — Manages voice authentication for command execution.
 *
 * Rules:
 * - Voice matched (Owner/Known) → commands CAN execute (phone control, app open, call, etc.)
 * - Voice not matched (Unknown) → conversation ONLY, no device commands
 * - No voice auth (microphone tap mode) → conversation ONLY, no device commands
 */
class VoiceAuthManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceAuth"
        private const val SIMILARITY_THRESHOLD = 0.75f // Minimum to consider a match

        // Command types that require voice auth
        private val PROTECTED_COMMANDS = setOf(
            "OPEN_APP", "CLOSE_APP", "CALL", "WHATSAPP_CALL", "WHATSAPP_MSG",
            "FLASHLIGHT_ON", "FLASHLIGHT_OFF", "VOLUME_UP", "VOLUME_DOWN",
            "SCREENSHOT", "SMS", "CLICK", "TYPE_TEXT", "BACK", "HOME",
            "SETTINGS_OPEN", "SETTINGS_WIFI_ON", "SETTINGS_WIFI_OFF",
            "SETTINGS_BLUETOOTH_ON", "SETTINGS_BLUETOOTH_OFF", "SETTINGS_BRIGHTNESS",
            "IMO_CALL", "MESSENGER_CALL", "TELEGRAM_CALL", "SEARCH"
        )
    }

    private val voiceIdentifier = VoiceIdentifier(context)
    private var lastRecognizedSlot = -1
    private var lastRecognizedName = "Unknown"

    /**
     * Check if the current user is authorized to execute device commands.
     */
    fun isAuthorizedForCommands(): Boolean {
        return lastRecognizedSlot == 0 || lastRecognizedSlot == 1 // Owner or Known
    }

    /**
     * Check if a specific command is protected (requires voice auth).
     */
    fun isProtectedCommand(commandType: String): Boolean {
        return commandType.uppercase() in PROTECTED_COMMANDS
    }

    /**
     * Try to authenticate via voice.
     * Returns true if voice matched (authorized), false if unknown.
     */
    fun authenticateVoice(onResult: (Boolean, String) -> Unit) {
        Log.d(TAG, "authenticateVoice started...")
        if (!voiceIdentifier.isVoiceEnrolled(0) && !voiceIdentifier.isVoiceEnrolled(1)) {
            Log.w(TAG, "No voice profiles enrolled — allowing all commands (first-time user)")
            lastRecognizedSlot = 0
            lastRecognizedName = "Owner"
            onResult(true, "Owner")
            return
        }

        voiceIdentifier.recognizeVoice { result ->
            lastRecognizedSlot = result.slotIndex
            lastRecognizedName = result.speakerName

            val isAuthorized = result.slotIndex in 0..1 && result.confidence >= SIMILARITY_THRESHOLD
            Log.d(TAG, "Voice auth complete: speaker=${result.speakerName}, slot=${result.slotIndex}, confidence=${"%.2f".format(result.confidence)}, authorized=$isAuthorized")
            onResult(isAuthorized, result.speakerName)
        }
    }

    fun getLastRecognizedName(): String = lastRecognizedName
    fun getLastRecognizedSlot(): Int = lastRecognizedSlot

    fun isVoiceEnrolled(slotIndex: Int): Boolean = voiceIdentifier.isVoiceEnrolled(slotIndex)
}
