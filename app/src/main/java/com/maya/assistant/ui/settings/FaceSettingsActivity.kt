package com.maya.assistant.ui.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.maya.assistant.R
import com.maya.assistant.utils.FaceRecognitionHelper
import java.io.File

/**
 * FaceSettingsActivity — manage enrolled faces.
 * Allows users to register new faces and delete existing ones.
 */
class FaceSettingsActivity : AppCompatActivity() {

    private lateinit var faceHelper: FaceRecognitionHelper
    private lateinit var container: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var enrollBtn: Button
    private lateinit var nameInput: EditText
    private var currentPhotoPath: String? = null
    private var pendingName: String? = null

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            val path = currentPhotoPath ?: return@registerForActivityResult
            val bitmap = BitmapFactory.decodeFile(path)
            if (bitmap != null) {
                val name = pendingName ?: return@registerForActivityResult
                faceHelper.enrollFace(name, bitmap) { result ->
                    runOnUiThread {
                        enrollBtn.isEnabled = true
                        if (result) {
                            Toast.makeText(this, "✅ $name সংরক্ষিত হয়েছে", Toast.LENGTH_SHORT).show()
                            nameInput.text.clear()
                            refreshEnrolledList()
                        } else {
                            Toast.makeText(this, "❌ সংরক্ষণ ব্যর্থ - মুখ পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                enrollBtn.isEnabled = true
                Toast.makeText(this, "❌ ছবি লোড হয়নি", Toast.LENGTH_SHORT).show()
            }
        } else {
            enrollBtn.isEnabled = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create UI programmatically (no XML layout dependency)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }

        // Title
        val title = TextView(this).apply {
            text = "👤 চেহরা সংরক্ষণ"
            textSize = 22f
            setPadding(0, 0, 0, 24)
        }
        root.addView(title)

        // Status
        statusText = TextView(this).apply {
            text = "লোড হচ্ছে..."
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        root.addView(statusText)

        // Name input
        nameInput = EditText(this).apply {
            hint = "নাম লিখো (যেমন: Sumon)"
            textSize = 16f
            setPadding(32, 16, 32, 16)
            setBackgroundResource(android.R.drawable.edit_text)
        }
        root.addView(nameInput)

        // Enroll button
        enrollBtn = Button(this).apply {
            text = "📷 ছবি তুলে সংরক্ষণ করো"
            textSize = 16f
            setPadding(32, 16, 32, 16)
        }
        root.addView(enrollBtn)

        // Separator
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(0xFFCCCCCC.toInt())
        })
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 16)
        })

        // Enrolled faces container
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(container)

        setContentView(root)

        faceHelper = FaceRecognitionHelper.getInstance(this)
        refreshEnrolledList()

        enrollBtn.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "নাম লিখো", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Create file for photo
            val photoFile = File(
                getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "face_${System.currentTimeMillis()}.jpg"
            )
            currentPhotoPath = photoFile.absolutePath
            pendingName = name

            val photoUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                photoFile
            )

            enrollBtn.isEnabled = false
            takePictureLauncher.launch(photoUri)
        }
    }

    private fun refreshEnrolledList() {
        container.removeAllViews()
        val profiles = faceHelper.getEnrolledProfiles()

        if (profiles.isEmpty()) {
            statusText.text = "কোনো চেহরা সংরক্ষিত নেই। উপরে নাম লিখে সংরক্ষণ করো।"
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
                container.addView(row)
            }
        }
    }
}
