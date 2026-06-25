package com.maya.assistant.utils

object Constants {
    // Gemini Audio Streaming Models (native audio for voice conversation)
    const val GEMINI_MODEL = "models/gemini-2.5-flash-native-audio"
    const val GEMINI_MODEL_FALLBACK_1 = "models/gemini-2.5-flash"
    const val GEMINI_MODEL_FALLBACK_2 = "models/gemini-2.0-flash-live-001"
    val GEMINI_FALLBACK_MODELS = listOf(GEMINI_MODEL, GEMINI_MODEL_FALLBACK_1, GEMINI_MODEL_FALLBACK_2)
    const val GEMINI_WS_BASE = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    const val GEMINI_VOICE = "Aoede"

    // Prefs
    const val PREFS_NAME = "maya_prefs"
    const val KEY_API_KEY = "api_key"
    const val KEY_USER_NAME = "user_name"
    const val KEY_PERSONALITY = "personality_mode"
    const val KEY_VOICE_TYPE = "voice_type"
    const val KEY_LIVE_MODE = "live_mode"
    const val KEY_PRIME_NAME = "prime_name"
    const val KEY_PRIME_NUMBER = "prime_number"
    const val KEY_CALL_ANNOUNCE = "call_announce"
    const val KEY_WAKE_WORD = "wake_word"
    const val KEY_LANGUAGE = "language"

    // Notifications
    const val NOTIF_CHANNEL_OVERLAY = "maya_overlay_channel"
    const val NOTIF_CHANNEL_VOICE = "maya_voice_channel"
    const val NOTIF_ID_OVERLAY = 1001
    const val NOTIF_ID_VOICE = 1002

    // Audio
    const val SAMPLE_RATE_IN = 16000
    const val SAMPLE_RATE_OUT = 24000

    // Actions
    const val ACTION_SHOW_OVERLAY = "SHOW_OVERLAY"
    const val ACTION_HIDE_OVERLAY = "HIDE_OVERLAY"
    const val ACTION_TOGGLE_OVERLAY = "TOGGLE_OVERLAY"
    const val ACTION_START_LISTENING = "START_LISTENING"
    const val ACTION_STOP_LISTENING = "STOP_LISTENING"

    // Voice States
    const val STATE_IDLE = "IDLE"
    const val STATE_LISTENING = "LISTENING"
    const val STATE_THINKING = "THINKING"
    const val STATE_SPEAKING = "SPEAKING"
}
