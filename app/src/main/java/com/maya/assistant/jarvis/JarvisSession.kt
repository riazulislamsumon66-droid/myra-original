package com.maya.assistant.jarvis

import android.content.Context
import android.content.SharedPreferences
import com.maya.assistant.utils.Logger

/**
 * JarvisSession — remembers conversation context across sessions.
 * Stores: user preferences, conversation history, face/voice profiles,
 * and proactive suggestion state.
 */
object JarvisSession {
    private const val TAG = "JarvisSession"
    private const val PREFS_NAME = "jarvis_session"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_LAST_FACE_ID = "last_face_id"
    private const val KEY_LAST_VOICE_ID = "last_voice_id"
    private const val KEY_INTERACTION_COUNT = "interaction_count"
    private const val KEY_LAST_INTERACTION_TIME = "last_interaction_time"
    private const val KEY_KNOWN_FACES = "known_faces"
    private const val KEY_KNOWN_VOICES = "known_voices"
    private const val KEY_HABITS = "habits"
    private const val KEY_PREFERENCES = "preferences"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        incrementInteraction()
        Logger.d(TAG, "JarvisSession initialized — interactions: ${getInteractionCount()}")
    }

    // ── User Identity ──────────────────────────────────────────────
    var userName: String
        get() = prefs.getString(KEY_USER_NAME, "Sir") ?: "Sir"
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()

    // ── Face Recognition State ─────────────────────────────────────
    var lastRecognizedFaceId: String?
        get() = prefs.getString(KEY_LAST_FACE_ID, null)
        set(value) = prefs.edit().putString(KEY_LAST_FACE_ID, value).apply()

    fun getKnownFaces(): List<String> {
        return prefs.getStringSet(KEY_KNOWN_FACES, emptySet())?.toList() ?: emptyList()
    }

    fun addKnownFace(name: String) {
        val faces = getKnownFaces().toMutableSet()
        faces.add(name)
        prefs.edit().putStringSet(KEY_KNOWN_FACES, faces).apply()
        Logger.d(TAG, "Face added to session: $name")
    }

    // ── Voice Identification State ─────────────────────────────────
    var lastRecognizedVoiceId: String?
        get() = prefs.getString(KEY_LAST_VOICE_ID, null)
        set(value) = prefs.edit().putString(KEY_LAST_VOICE_ID, value).apply()

    fun getKnownVoices(): List<String> {
        return prefs.getStringSet(KEY_KNOWN_VOICES, emptySet())?.toList() ?: emptyList()
    }

    fun addKnownVoice(name: String) {
        val voices = getKnownVoices().toMutableSet()
        voices.add(name)
        prefs.edit().putStringSet(KEY_KNOWN_VOICES, voices).apply()
        Logger.d(TAG, "Voice added to session: $name")
    }

    // ── Interaction Tracking ───────────────────────────────────────
    fun getInteractionCount(): Int {
        return prefs.getInt(KEY_INTERACTION_COUNT, 0)
    }

    private fun incrementInteraction() {
        prefs.edit()
            .putInt(KEY_INTERACTION_COUNT, getInteractionCount() + 1)
            .putLong(KEY_LAST_INTERACTION_TIME, System.currentTimeMillis())
            .apply()
    }

    fun getLastInteractionTime(): Long {
        return prefs.getLong(KEY_LAST_INTERACTION_TIME, 0L)
    }

    // ── Habit Learning ─────────────────────────────────────────────
    fun getHabit(key: String): Int {
        return prefs.getInt("habit_$key", 0)
    }

    fun incrementHabit(key: String) {
        prefs.edit().putInt("habit_$key", getHabit(key) + 1).apply()
    }

    fun getTopHabits(limit: Int = 5): List<Pair<String, Int>> {
        val allHabits = prefs.all
            .filterKeys { it.startsWith("habit_") }
            .map { it.key.removePrefix("habit_") to (it.value as Int) }
            .sortedByDescending { it.second }
        return allHabits.take(limit)
    }

    // ── Preferences ────────────────────────────────────────────────
    fun setPreference(key: String, value: String) {
        prefs.edit().putString("pref_$key", value).apply()
    }

    fun getPreference(key: String, default: String = ""): String {
        return prefs.getString("pref_$key", default) ?: default
    }

    // ── Context Generation for AI ──────────────────────────────────
    fun buildContext(): String {
        val sb = StringBuilder()
        sb.appendLine("User: ${userName}")
        sb.appendLine("Time: ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")

        val faces = getKnownFaces()
        if (faces.isNotEmpty()) sb.appendLine("Known faces: ${faces.joinToString(", ")}")

        val voices = getKnownVoices()
        if (voices.isNotEmpty()) sb.appendLine("Known voices: ${voices.joinToString(", ")}")

        val topHabits = getTopHabits(3)
        if (topHabits.isNotEmpty()) {
            sb.appendLine("Frequent actions: ${topHabits.joinToString(", ") { "${it.first}(${it.second}x)" }}")
        }

        return sb.toString()
    }
}
