package com.maya.assistant.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * SecurePrefs — Encrypted SharedPreferences for sensitive data (API keys, tokens)
 * Uses AndroidX security-crypto (AES-256 encryption via Android Keystore)
 */
object SecurePrefs {

    private const val PREFS_FILE = "maya_secure_prefs"

    private fun getEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveApiKey(context: Context, apiKey: String) {
        getEncryptedPrefs(context).edit().putString("api_key", apiKey).apply()
    }

    fun getApiKey(context: Context): String {
        return getEncryptedPrefs(context).getString("api_key", "") ?: ""
    }

    fun saveTtsApiKey(context: Context, apiKey: String) {
        getEncryptedPrefs(context).edit().putString("tts_api_key", apiKey).apply()
    }

    fun getTtsApiKey(context: Context): String {
        return getEncryptedPrefs(context).getString("tts_api_key", "") ?: ""
    }

    fun clearAll(context: Context) {
        getEncryptedPrefs(context).edit().clear().apply()
    }
}
