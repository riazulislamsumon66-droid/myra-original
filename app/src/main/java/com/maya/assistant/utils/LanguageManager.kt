package com.maya.assistant.utils

import android.app.Activity
import android.content.Context
import java.util.Locale

/**
 * Language Manager for MAYA Assistant.
 * Supports: Bangla, Banglish, Hindi, Hinglish, English, Seychellois Creole
 */
object LanguageManager {

    private const val PREF_LANGUAGE = "selected_language"

    data class Language(val code: String, val displayName: String, val nativeName: String)

    val supportedLanguages = listOf(
        Language("bn", "Bangla", "বাংলা"),
        Language("banglish", "Banglish", "Banglish"),
        Language("hi", "Hindi", "हिन्दी"),
        Language("hinglish", "Hinglish", "Hinglish"),
        Language("en", "English", "English"),
        Language("crl", "Seychellois Creole", "Kreol Seselwa")
    )

    /** Returns the Android resource qualifier for the language, or null for programmatic-only */
    fun getResourceQualifier(code: String): String? {
        return when (code) {
            "bn" -> "bn"
            "hi" -> "hi"
            "en" -> "en"
            "crl" -> "crl"
            else -> null // Banglish, Hinglish — use default strings.xml
        }
    }

    fun getSelectedLanguage(context: Context): Language {
        val code = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_LANGUAGE, "banglish") ?: "banglish"
        return supportedLanguages.find { it.code == code } ?: supportedLanguages[1]
    }

    fun setLanguage(context: Context, language: Language) {
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LANGUAGE, language.code)
            .apply()
    }

    fun applyLanguage(context: Context): Context {
        val lang = getSelectedLanguage(context)
        val locale = when (lang.code) {
            "banglish" -> Locale("bn") // Fallback to Bangla locale
            "hinglish" -> Locale("hi") // Fallback to Hindi locale
            else -> Locale(lang.code)
        }
        Locale.setDefault(locale)
        val config = context.resources.configuration
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    fun applyLanguage(activity: Activity) {
        val lang = getSelectedLanguage(activity)
        val locale = when (lang.code) {
            "banglish" -> Locale("bn")
            "hinglish" -> Locale("hi")
            else -> Locale(lang.code)
        }
        Locale.setDefault(locale)
        val config = activity.resources.configuration
        config.setLocale(locale)
        activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
    }

    fun getSystemPromptLanguageInstruction(context: Context): String {
        val lang = getSelectedLanguage(context)
        return when (lang.code) {
            "bn" -> "বাংলায় উত্তর দাও। বাংলা ও ইংরেজি মিশ্রণ করো না।"
            "banglish" -> "Reply in Banglish (Bangla written in English script). Do not use pure Hindi or formal Bangla."
            "hi" -> "हिंदी में जवाब दो। अंग्रेजी मिक्स न करो।"
            "hinglish" -> "Reply in Hinglish (Hindi + English mix). Use natural conversational style."
            "en" -> "Reply in English only."
            "crl" -> "Reply in Seychellois Creole (Kreol Seselwa). Do not mix English unless necessary."
            else -> "Reply in Banglish."
        }
    }
}
