package com.maya.assistant.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.maya.assistant.R
import com.maya.assistant.voice.VoiceIdentifier

/**
 * VoiceSettingsActivity — Setup and manage 3 voice profiles.
 *
 * Slots:
 * 0 - Owner (Sumon): Full access, always recognized
 * 1 - Known Person (Tuha Moni): Recognized by name
 * 2 - Unknown: Not enrolled, app asks "Who are you?"
 */
class VoiceSettingsActivity : AppCompatActivity() {

    private lateinit var voiceIdentifier: VoiceIdentifier

    private lateinit var slot0Card: LinearLayout
    private lateinit var slot1Card: LinearLayout
    private lateinit var slot2Card: LinearLayout
    private lateinit var slot0Name: TextView
    private lateinit var slot1Name: TextView
    private lateinit var slot2Name: TextView
    private lateinit var slot0Status: TextView
    private lateinit var slot1Status: TextView
    private lateinit var slot2Status: TextView
    private lateinit var slot0Button: Button
    private lateinit var slot1Button: Button
    private lateinit var slot2Button: Button
    private lateinit var testButton: Button
    private lateinit var testResult: TextView

    private val PERMISSION_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_settings)

        voiceIdentifier = VoiceIdentifier(this)

        initViews()
        updateUI()
    }

    private fun initViews() {
        slot0Card = findViewById(R.id.slot0Card)
        slot1Card = findViewById(R.id.slot1Card)
        slot2Card = findViewById(R.id.slot2Card)
        slot0Name = findViewById(R.id.slot0Name)
        slot1Name = findViewById(R.id.slot1Name)
        slot2Name = findViewById(R.id.slot2Name)
        slot0Status = findViewById(R.id.slot0Status)
        slot1Status = findViewById(R.id.slot1Status)
        slot2Status = findViewById(R.id.slot2Status)
        slot0Button = findViewById(R.id.slot0Button)
        slot1Button = findViewById(R.id.slot1Button)
        slot2Button = findViewById(R.id.slot2Button)
        testButton = findViewById(R.id.testVoiceButton)
        testResult = findViewById(R.id.testResult)

        slot0Button.setOnClickListener { enrollVoice(0, "Sumon") }
        slot1Button.setOnClickListener { enrollVoice(1, "Tuha Moni") }
        slot2Button.setOnClickListener { enrollVoice(2, "Unknown Person") }

        testButton.setOnClickListener { testVoiceRecognition() }
    }

    private fun updateUI() {
        updateSlotUI(0, "Sumon", slot0Name, slot0Status, slot0Button)
        updateSlotUI(1, "Tuha Moni", slot1Name, slot1Status, slot1Button)
        updateSlotUI(2, "Unknown Person", slot2Name, slot2Status, slot2Button)
    }

    private fun updateSlotUI(
        slot: Int,
        defaultName: String,
        nameView: TextView,
        statusView: TextView,
        button: Button
    ) {
        val enrolled = voiceIdentifier.isVoiceEnrolled(slot)
        val name = voiceIdentifier.getVoiceName(slot)

        nameView.text = if (enrolled) "$name ✓" else "$defaultName (Not enrolled)"
        statusView.text = if (enrolled) "Voice enrolled" else "Tap to enroll"
        button.text = if (enrolled) "Re-enroll" else "Enroll"
    }

    private fun enrollVoice(slot: Int, name: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_CODE
            )
            return
        }

        Toast.makeText(this, "Recording... Speak now for 5 seconds", Toast.LENGTH_SHORT).show()

        voiceIdentifier.enrollVoice(
            slotIndex = slot,
            name = name,
            onProgress = { progress ->
                runOnUiThread {
                    testResult.text = "Enrolling ${name}: ${progress}%"
                }
            },
            onComplete = { success ->
                runOnUiThread {
                    if (success) {
                        Toast.makeText(this, "$name enrolled successfully!", Toast.LENGTH_SHORT).show()
                        testResult.text = "✅ $name enrolled!"
                    } else {
                        Toast.makeText(this, "Enrollment failed. Try again.", Toast.LENGTH_SHORT).show()
                        testResult.text = "❌ Enrollment failed"
                    }
                    updateUI()
                }
            }
        )
    }

    private fun testVoiceRecognition() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_CODE
            )
            return
        }

        testResult.text = "Listening... Speak now"

        voiceIdentifier.recognizeVoice { result ->
            runOnUiThread {
                val message = if (result.speakerName != "Unknown") {
                    "✅ Recognized: ${result.speakerName} (${(result.confidence * 100).toInt()}% confidence)"
                } else {
                    "❓ Unknown voice detected"
                }
                testResult.text = message
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Microphone permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Microphone permission required for voice ID", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceIdentifier.stopRecording()
    }
}
