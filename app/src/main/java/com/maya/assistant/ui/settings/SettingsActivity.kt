package com.maya.assistant.ui.settings

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.view.View
import android.util.Log
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.maya.assistant.R
import com.maya.assistant.jarvis.JarvisSession
import com.maya.assistant.service.AccessibilityHelperService
import com.maya.assistant.service.MayaDeviceAdminReceiver
import com.maya.assistant.security.SecurePrefs
import com.maya.assistant.utils.LanguageManager

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
        private const val PERMISSIONS_REQUEST_CODE = 200
    }

    private lateinit var apiKeyInput: EditText
    private lateinit var ttsApiKeyInput: EditText
    private lateinit var userNameInput: EditText
    private lateinit var primeNameInput: EditText
    private lateinit var primeNumberInput: EditText
    private lateinit var voiceTypeGroup: RadioGroup
    private lateinit var liveModeSwitch: Switch
    private lateinit var saveBtn: Button
    private lateinit var accessibilityStatus: TextView
    private lateinit var adminStatusText: TextView
    private lateinit var pickContactBtn: ImageButton
    private lateinit var callAnnounceSwitch: Switch
    private lateinit var callAnnounceStatusText: TextView
    private lateinit var grantPermissionsBtn: Button
    private lateinit var permissionsStatusText: TextView
    private lateinit var screenVisionSwitch: Switch
    private lateinit var screenVisionStatusText: TextView
    private lateinit var languageBtn: Button

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var componentName: ComponentName

    private val contactPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val contactUri = result.data?.data ?: return@registerForActivityResult
            handleContactResult(contactUri)
        }
    }

    private val allPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.CAMERA
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        componentName = ComponentName(this, MayaDeviceAdminReceiver::class.java)

        initViews()
        loadPreferences()
        setupListeners()
        updateStatus()
    }

    private fun initViews() {
        try {
            apiKeyInput = findViewById(R.id.apiKeyInput)
            ttsApiKeyInput = findViewById(R.id.ttsApiKeyInput)
            userNameInput = findViewById(R.id.userNameInput)
            primeNameInput = findViewById(R.id.primeNameInput)
            primeNumberInput = findViewById(R.id.primeNumberInput)
            voiceTypeGroup = findViewById(R.id.voiceTypeGroup)
            liveModeSwitch = findViewById(R.id.liveModeSwitch)
            saveBtn = findViewById(R.id.saveBtn)
            accessibilityStatus = findViewById(R.id.accessibilityStatus)
            adminStatusText = findViewById(R.id.adminStatusText)
            pickContactBtn = findViewById(R.id.pickContactBtn)
            callAnnounceSwitch = findViewById(R.id.callAnnounceSwitch)
            callAnnounceStatusText = findViewById(R.id.callAnnounceStatusText)
            grantPermissionsBtn = findViewById(R.id.grantPermissionsBtn)
            permissionsStatusText = findViewById(R.id.permissionsStatusText)
            screenVisionSwitch = findViewById(R.id.screenVisionSwitch)
            screenVisionStatusText = findViewById(R.id.screenVisionStatusText)
            languageBtn = findViewById(R.id.languageBtn)
        } catch (e: Exception) {
            Log.e(TAG, "initViews: Failed to initialize views", e)
        }
    }

    private fun loadPreferences() {
        try {
            val prefs = getSharedPreferences("maya_prefs", Context.MODE_PRIVATE)

            apiKeyInput.setText(SecurePrefs.getApiKey(this))
            ttsApiKeyInput.setText(SecurePrefs.getTtsApiKey(this))
            userNameInput.setText(prefs.getString("user_name", "Sir"))
            primeNameInput.setText(prefs.getString("prime_name", ""))
            primeNumberInput.setText(prefs.getString("prime_number", ""))

            try {
                JarvisSession.userName = userNameInput.text.toString()
            } catch (e: Exception) {
                Log.e(TAG, "loadPreferences: Failed to set JarvisSession userName", e)
            }

            val personality = prefs.getString("personality_mode", "gf") ?: "gf"
            try {
                JarvisSession.setPreference("personality", personality)
            } catch (e: Exception) {
                Log.e(TAG, "loadPreferences: Failed to set JarvisSession preference", e)
            }

            val voiceEngine = prefs.getString("voice_engine", "system") ?: "system"
            try {
                when (voiceEngine) {
                    "elevenlabs" -> findViewById<RadioButton>(R.id.radioEleven).isChecked = true
                    else -> findViewById<RadioButton>(R.id.radioSystem).isChecked = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadPreferences: Failed to set voice engine radio button", e)
            }

            liveModeSwitch.isChecked = prefs.getBoolean("live_mode_enabled", false)

            val announceEnabled = prefs.getBoolean("call_announce_enabled", true)
            callAnnounceSwitch.isChecked = announceEnabled
            updateCallAnnounceStatus(announceEnabled)

            val screenVisionEnabled = prefs.getBoolean("screen_vision_enabled", false)
            screenVisionSwitch.isChecked = screenVisionEnabled
            updateScreenVisionStatus(screenVisionEnabled)

            updateLanguageButton()
        } catch (e: Exception) {
            Log.e(TAG, "loadPreferences: Failed to load preferences", e)
        }
    }

    private fun updateLanguageButton() {
        val prefs = getSharedPreferences("maya_prefs", Context.MODE_PRIVATE)
        val lang = prefs.getString("language", "banglish") ?: "banglish"
        val langName = when (lang) {
            "bangla" -> "🇧🇩 Bangla"
            "banglish" -> "🇧🇩 Banglish"
            "hindi" -> "🇮🇳 Hindi"
            "hinglish" -> "🇮🇳 Hinglish"
            "english" -> "🇬🇧 English"
            "creole" -> "🇸🇨 Seychellois Creole"
            else -> "🇧🇩 Banglish"
        }
        languageBtn.text = "$langName ▾"
    }

    private fun setupListeners() {
        saveBtn.setOnClickListener { savePreferences() }

        pickContactBtn.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            contactPickerLauncher.launch(intent)
        }

        findViewById<View>(R.id.accessibilityCard).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Settings → Accessibility → MAYA → ON করো", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<View>(R.id.deviceAdminCard).setOnClickListener {
            try {
                if (!devicePolicyManager.isAdminActive(componentName)) {
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "MAYA needs device admin for app lock, screen capture, and call control.")
                    }
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "Device Admin is already active ✅", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Settings → Security → Device Admin → MAYA → Activate", Toast.LENGTH_LONG).show()
            }
        }

        callAnnounceSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateCallAnnounceStatus(isChecked)
        }

        screenVisionSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (com.maya.assistant.screenvision.ScreenCaptureService.isRunning()) {
                    updateScreenVisionStatus(true)
                } else {
                    screenVisionSwitch.isChecked = false
                    Toast.makeText(this, "প্রথমে MainActivity তে গিয়ে Screen Vision enable করো", Toast.LENGTH_LONG).show()
                }
            } else {
                com.maya.assistant.screenvision.ScreenCaptureService.stop(this)
                updateScreenVisionStatus(false)
            }
        }

        grantPermissionsBtn.setOnClickListener {
            // Show helpful info first
            val runtimeCount = arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.CAMERA,
                Manifest.permission.SEND_SMS
            ).count {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            val restrictedCount = arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.ANSWER_PHONE_CALLS
            ).count {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }

            val msg = "📋 Permissions needed:\n" +
                "• Runtime: $runtimeCount remaining\n" +
                "• Restricted: $restrictedCount (needs Settings)\n\n" +
                "Runtime permissions আগে নেবে, তারপর restricted জন্য Settings এ যেতে হবে।"

            AlertDialog.Builder(this)
                .setTitle("🔓 Grant Permissions")
                .setMessage(msg)
                .setPositiveButton("Start") { _, _ -> checkAndRequestPermissions() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        languageBtn.setOnClickListener {
            showLanguagePicker()
        }
    }

    private fun showLanguagePicker() {
        val languages = arrayOf("Bangla", "Banglish", "Hindi", "Hinglish", "English", "Seychellois Creole")
        val langCodes = arrayOf("bangla", "banglish", "hindi", "hinglish", "english", "creole")

        AlertDialog.Builder(this)
            .setTitle("🌐 Select Language")
            .setItems(languages) { _, which ->
                val code = langCodes[which]
                getSharedPreferences("maya_prefs", Context.MODE_PRIVATE).edit()
                    .putString("language", code)
                    .putString("selected_language", code)
                    .apply()
                LanguageManager.applyLanguage(this@SettingsActivity)
                updateLanguageButton()
                Toast.makeText(this, "Language set to ${languages[which]} ✅", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun checkAndRequestPermissions() {
        try {
            // Runtime permissions that CAN be requested via dialog
            val runtimePermissions = arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.CAMERA,
                Manifest.permission.SEND_SMS
            )

            // Restricted permissions — need Settings page
            val restrictedPermissions = arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.ANSWER_PHONE_CALLS
            )

            val missingRuntime = runtimePermissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }

            val missingRestricted = restrictedPermissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }

            if (missingRuntime.isNotEmpty()) {
                // Show runtime permission dialog
                ActivityCompat.requestPermissions(
                    this,
                    missingRuntime.toTypedArray(),
                    PERMISSIONS_REQUEST_CODE
                )
            } else if (missingRestricted.isNotEmpty()) {
                // Guide user to Settings for restricted permissions
                showRestrictedPermissionGuide(missingRestricted)
            } else {
                Toast.makeText(this, "সব Permission আগে থেকেই Granted! ✅", Toast.LENGTH_SHORT).show()
                updatePermissionsStatus()
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkAndRequestPermissions: Error requesting permissions", e)
            Toast.makeText(this, "Permission request failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showRestrictedPermissionGuide(permissions: List<String>) {
        val names = permissions.map { perm ->
            when (perm) {
                Manifest.permission.READ_PHONE_STATE -> "📞 Phone State"
                Manifest.permission.READ_CALL_LOG -> "📋 Call Log"
                Manifest.permission.ANSWER_PHONE_CALLS -> "📱 Answer Calls"
                else -> perm
            }
        }.joinToString("\n")

        AlertDialog.Builder(this)
            .setTitle("⚠️ Restricted Permissions Required")
            .setMessage(
                "এই permissions গুলো manually enable করতে হবে:\n\n$names\n\n" +
                "Settings → Apps → MAYA → Permissions → Allow করো"
            )
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Settings open করতে পারিনি", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            try {
                if (grantResults.isEmpty()) {
                    Toast.makeText(this, "Permission request cancelled", Toast.LENGTH_SHORT).show()
                } else {
                    val granted = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
                    val total = permissions.size

                    if (granted == total) {
                        Toast.makeText(this, "All permissions granted! ✅", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "$granted/$total permissions granted ⚠️", Toast.LENGTH_LONG).show()
                    }
                }
                updatePermissionsStatus()
            } catch (e: Exception) {
                Log.e(TAG, "onRequestPermissionsResult: Error handling permission result", e)
            }
        }
    }

    private fun updateCallAnnounceStatus(enabled: Boolean) {
        callAnnounceStatusText.text = if (enabled) {
            "📢 Call aane pe MAYA naam bolegi"
        } else {
            "🔇 Call announce band hai"
        }
        callAnnounceStatusText.setTextColor(
            if (enabled) 0xFF00E676.toInt() else 0xFFFF1744.toInt()
        )
    }

    private fun updateScreenVisionStatus(enabled: Boolean) {
        screenVisionStatusText.text = if (enabled) {
            "✅ Screen Vision চালু আছে — OCR fallback active"
        } else {
            "❌ Screen Vision বন্ধ আছে"
        }
        screenVisionStatusText.setTextColor(
            if (enabled) 0xFF00E676.toInt() else 0xFFFF1744.toInt()
        )
    }

    private fun updatePermissionsStatus() {
        try {
            val missing = allPermissions.count {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            permissionsStatusText.text = when {
                missing == 0 -> "✅ All Permissions Granted"
                missing <= 2 -> "⚠️ $missing permissions pending"
                else -> "❌ $missing permissions missing"
            }
            permissionsStatusText.setTextColor(when {
                missing == 0 -> 0xFF00E676.toInt()
                missing <= 2 -> 0xFFFFA726.toInt()
                else -> 0xFFFF1744.toInt()
            })
        } catch (e: Exception) {
            Log.e(TAG, "updatePermissionsStatus: Error updating permissions status", e)
        }
    }

    private fun handleContactResult(uri: Uri) {
        try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            if (cursor?.moveToFirst() == true) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (nameIndex >= 0) {
                    primeNameInput.setText(cursor.getString(nameIndex))
                }
                if (numIndex >= 0) {
                    primeNumberInput.setText(cursor.getString(numIndex)?.replace(" ", "") ?: "")
                }
            }
            cursor?.close()
        } catch (e: Exception) {
            Log.e(TAG, "handleContactResult: Failed to process contact", e)
        }
    }

    private fun savePreferences() {
        try {
            val prefs = getSharedPreferences("maya_prefs", Context.MODE_PRIVATE).edit()

            try {
                SecurePrefs.saveApiKey(this, apiKeyInput.text.toString().trim())
            } catch (e: Exception) {
                Log.e(TAG, "savePreferences: Failed to save API key", e)
            }

            try {
                SecurePrefs.saveTtsApiKey(this, ttsApiKeyInput.text.toString().trim())
            } catch (e: Exception) {
                Log.e(TAG, "savePreferences: Failed to save TTS API key", e)
            }

            val userName = userNameInput.text.toString().trim()
            prefs.putString("user_name", userName)
            prefs.putString("prime_name", primeNameInput.text.toString().trim())
            prefs.putString("prime_number", primeNumberInput.text.toString().trim())

            try {
                JarvisSession.userName = userName
            } catch (e: Exception) {
                Log.e(TAG, "savePreferences: Failed to set JarvisSession userName", e)
            }

            try {
                val personality = JarvisSession.getPreference("personality", "gf")
                prefs.putString("personality_mode", personality)
            } catch (e: Exception) {
                Log.e(TAG, "savePreferences: Failed to get JarvisSession preference, using default", e)
                prefs.putString("personality_mode", "gf")
            }

            val voiceEngine = try {
                when (voiceTypeGroup.checkedRadioButtonId) {
                    R.id.radioEleven -> "elevenlabs"
                    else -> "system"
                }
            } catch (e: Exception) {
                Log.e(TAG, "savePreferences: Failed to get voice engine, using default", e)
                "system"
            }
            prefs.putString("voice_engine", voiceEngine)

            prefs.putBoolean("live_mode_enabled", liveModeSwitch.isChecked)
            prefs.putBoolean("call_announce_enabled", callAnnounceSwitch.isChecked)
            prefs.putBoolean("screen_vision_enabled", screenVisionSwitch.isChecked)

            prefs.apply()
            Toast.makeText(this, "Settings Saved! ✅", Toast.LENGTH_SHORT).show()

            try {
                JarvisSession.setPreference("gemini_api_key_set", apiKeyInput.text.toString().isNotEmpty().toString())
                JarvisSession.setPreference("tts_api_key_set", ttsApiKeyInput.text.toString().isNotEmpty().toString())
            } catch (e: Exception) {
                Log.e(TAG, "savePreferences: Failed to set JarvisSession key preferences", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "savePreferences: Unexpected error during save", e)
            Toast.makeText(this, "Error saving settings: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateStatus() {
        try {
            val enabled = try {
                AccessibilityHelperService.isEnabled(this)
            } catch (e: Exception) {
                Log.e(TAG, "updateStatus: Failed to check accessibility status", e)
                false
            }
            accessibilityStatus.text = if (enabled) "✅ Enabled" else "❌ Disabled"
            accessibilityStatus.setTextColor(if (enabled) 0xFF00E676.toInt() else 0xFFFF1744.toInt())
        } catch (e: Exception) {
            Log.e(TAG, "updateStatus: Failed to update accessibility status view", e)
        }

        try {
            val adminActive = devicePolicyManager.isAdminActive(componentName)
            adminStatusText.text = if (adminActive) "✅ Active" else "❌ Inactive"
            adminStatusText.setTextColor(if (adminActive) 0xFF00E676.toInt() else 0xFFFF1744.toInt())
        } catch (e: Exception) {
            Log.e(TAG, "updateStatus: Failed to update admin status", e)
        }

        updatePermissionsStatus()
    }

    override fun onResume() {
        super.onResume()
        try {
            updateStatus()
        } catch (e: Exception) {
            Log.e(TAG, "onResume: Failed to update status", e)
        }
    }

}
