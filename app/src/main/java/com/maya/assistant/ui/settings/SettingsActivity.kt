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
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("maya_prefs", Context.MODE_PRIVATE)
        apiKeyInput.setText(SecurePrefs.getApiKey(this))
        ttsApiKeyInput.setText(SecurePrefs.getTtsApiKey(this))
        userNameInput.setText(prefs.getString("user_name", "Sir"))
        primeNameInput.setText(prefs.getString("prime_name", ""))
        primeNumberInput.setText(prefs.getString("prime_number", ""))

        JarvisSession.userName = userNameInput.text.toString()

        val personality = prefs.getString("personality_mode", "gf") ?: "gf"
        JarvisSession.setPreference("personality", personality)

        val voiceEngine = prefs.getString("voice_engine", "system") ?: "system"
        when (voiceEngine) {
            "elevenlabs" -> findViewById<RadioButton>(R.id.radioEleven).isChecked = true
            else -> findViewById<RadioButton>(R.id.radioSystem).isChecked = true
        }

        liveModeSwitch.isChecked = prefs.getBoolean("live_mode_enabled", false)

        val announceEnabled = prefs.getBoolean("call_announce_enabled", true)
        callAnnounceSwitch.isChecked = announceEnabled
        updateCallAnnounceStatus(announceEnabled)

        val screenVisionEnabled = prefs.getBoolean("screen_vision_enabled", false)
        screenVisionSwitch.isChecked = screenVisionEnabled
        updateScreenVisionStatus(screenVisionEnabled)

        updateLanguageButton()
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
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<View>(R.id.deviceAdminCard).setOnClickListener {
            if (!devicePolicyManager.isAdminActive(componentName)) {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "MAYA needs admin to control system.")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Admin is already active ✅", Toast.LENGTH_SHORT).show()
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
            checkAndRequestPermissions()
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
        val missing = allPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSIONS_REQUEST_CODE)
        } else {
            Toast.makeText(this, "All permissions already granted! ✅", Toast.LENGTH_SHORT).show()
            updatePermissionsStatus()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            val granted = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
            val total = permissions.size

            if (granted == total) {
                Toast.makeText(this, "All permissions granted! ✅", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "$granted/$total permissions granted ⚠️", Toast.LENGTH_LONG).show()
            }
            updatePermissionsStatus()
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
    }

    private fun handleContactResult(uri: Uri) {
        val cursor = contentResolver.query(uri, null, null, null, null)
        if (cursor?.moveToFirst() == true) {
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            primeNameInput.setText(cursor.getString(nameIndex))
            primeNumberInput.setText(cursor.getString(numIndex).replace(" ", ""))
        }
        cursor?.close()
    }

    private fun savePreferences() {
        val prefs = getSharedPreferences("maya_prefs", Context.MODE_PRIVATE).edit()

        SecurePrefs.saveApiKey(this, apiKeyInput.text.toString().trim())
        SecurePrefs.saveTtsApiKey(this, ttsApiKeyInput.text.toString().trim())

        val userName = userNameInput.text.toString().trim()
        prefs.putString("user_name", userName)
        prefs.putString("prime_name", primeNameInput.text.toString().trim())
        prefs.putString("prime_number", primeNumberInput.text.toString().trim())

        JarvisSession.userName = userName

        val personality = JarvisSession.getPreference("personality", "gf")
        prefs.putString("personality_mode", personality)

        val voiceEngine = when (voiceTypeGroup.checkedRadioButtonId) {
            R.id.radioEleven -> "elevenlabs"
            else -> "system"
        }
        prefs.putString("voice_engine", voiceEngine)

        prefs.putBoolean("live_mode_enabled", liveModeSwitch.isChecked)
        prefs.putBoolean("call_announce_enabled", callAnnounceSwitch.isChecked)
        prefs.putBoolean("screen_vision_enabled", screenVisionSwitch.isChecked)

        prefs.apply()
        Toast.makeText(this, "Settings Saved! ✅", Toast.LENGTH_SHORT).show()

        JarvisSession.setPreference("gemini_api_key_set", apiKeyInput.text.toString().isNotEmpty().toString())
        JarvisSession.setPreference("tts_api_key_set", ttsApiKeyInput.text.toString().isNotEmpty().toString())
    }

    private fun updateStatus() {
        val enabled = AccessibilityHelperService.isEnabled(this)
        accessibilityStatus.text = if (enabled) "✅ Enabled" else "❌ Disabled"
        accessibilityStatus.setTextColor(if (enabled) 0xFF00E676.toInt() else 0xFFFF1744.toInt())

        val adminActive = devicePolicyManager.isAdminActive(componentName)
        adminStatusText.text = if (adminActive) "✅ Active" else "❌ Inactive"
        adminStatusText.setTextColor(if (adminActive) 0xFF00E676.toInt() else 0xFFFF1744.toInt())

        updatePermissionsStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 200
    }
}
