package com.maya.assistant.models

data class VoiceCommand(
    val raw: String,
    val type: CommandType,
    val args: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

enum class CommandType {
    OPEN_APP, CLOSE_APP, CALL, WHATSAPP_CALL, WHATSAPP_MSG,
    SMS, YOUTUBE_PLAY, SPOTIFY_PLAY, MUSIC_PLAY,
    FLASHLIGHT_ON, FLASHLIGHT_OFF,
    VOLUME_UP, VOLUME_DOWN,
    SCREENSHOT, SCROLL_UP, SCROLL_DOWN,
    CLICK, SEARCH, TYPE_TEXT, READ_SCREEN,
    BATTERY_CHECK, SETTINGS_OPEN,
    SETTINGS_WIFI_ON, SETTINGS_WIFI_OFF,
    SETTINGS_BLUETOOTH_ON, SETTINGS_BLUETOOTH_OFF,
    SETTINGS_BRIGHTNESS,
    IMO_CALL, MESSENGER_CALL, TELEGRAM_CALL,
    CALENDAR_TODAY, CALENDAR_UPCOMING, CALENDAR_CREATE,
    REGISTER_FACE, RECOGNIZE_FACE, IDENTIFY_SPEAKER,
    SCREEN_ACTION, NAVIGATE, CONVERSATION,
    UNKNOWN
}

data class AppModel(
    val name: String,
    val packageName: String,
    val label: String
)

data class ScreenNodeModel(
    val text: String?,
    val contentDesc: String?,
    val className: String?,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val bounds: android.graphics.Rect?,
    val viewId: String?
)

data class ActionModel(
    val type: ActionType,
    val target: String = "",
    val value: String = "",
    val x: Int = -1,
    val y: Int = -1
)

enum class ActionType {
    CLICK_TEXT, CLICK_DESC, CLICK_COORDS,
    TYPE_TEXT, SCROLL_DOWN, SCROLL_UP,
    BACK, HOME, RECENT_APPS,
    LAUNCH_APP
}

data class AIStateModel(
    val state: String = "IDLE",
    val statusMessage: String = "SYSTEM READY",
    val isConnected: Boolean = false,
    val isMicActive: Boolean = false,
    val amplitude: Float = 0f
)
