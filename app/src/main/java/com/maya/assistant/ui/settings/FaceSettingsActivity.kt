package com.maya.assistant.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.maya.assistant.R
import com.maya.assistant.utils.FaceRecognitionHelper

/**
 * FaceSettingsActivity — manage enrolled faces.
 * Allows users to register new faces and delete existing ones.
 */
class FaceSettingsActivity : AppCompatActivity() {

    private lateinit var faceHelper: FaceRecognitionHelper
    private lateinit var enrolledList: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var enrollBtn: Button
    private lateinit var nameInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_settings) // Reuse layout for simplicity

        faceHelper = FaceRecognitionHelper.getInstance(this)
        enrolledList = findViewById(R.id.voice_slots_container)
        statusText = findViewById(R.id.voice_status_text)
        enrollBtn = findViewById(R.id.voice_enroll_btn)
        nameInput = findViewById(R.id.voice_name_input)

        // Update title
        findViewById<TextView>(android.R.id.text1)?.text = "চেহরা সংরক্ষণ"

        refreshEnrolledList()

        enrollBtn.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "নাম লিখো", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            faceHelper.enrollFace(name) { success ->
                runOnUiThread {
                    if (success) {
                        Toast.makeText(this, "$name সংরক্ষিত হয়েছে ✅", Toast.LENGTH_SHORT).show()
                        nameInput.text.clear()
                        refreshEnrolledList()
                    } else {
                        Toast.makeText(this, "সংরক্ষণ ব্যর্থ 😢", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun refreshEnrolledList() {
        enrolledList.removeAllViews()
        val profiles = faceHelper.getEnrolledProfiles()

        if (profiles.isEmpty()) {
            statusText.text = "কোনো চেহরা সংরক্ষিত নেই।"
            enrollBtn.isEnabled = true
        } else {
            statusText.text = "সংরক্ষিত চেহরা (${profiles.size}/10):"
            enrollBtn.isEnabled = profiles.size < 10

            for ((name, slot) in profiles) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(16, 8, 16, 8)
                }

                val nameText = TextView(this).apply {
                    text = "• $name"
                    textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val deleteBtn = Button(this).apply {
                    text = "মুছো"
                    textSize = 12f
                    setOnClickListener {
                        faceHelper.deleteFace(slot)
                        refreshEnrolledList()
                    }
                }

                row.addView(nameText)
                row.addView(deleteBtn)
                enrolledList.addView(row)
            }
        }
    }
}
