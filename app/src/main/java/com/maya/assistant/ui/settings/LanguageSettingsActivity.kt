package com.maya.assistant.ui.settings

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.maya.assistant.R
import com.maya.assistant.utils.LanguageManager

class LanguageSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        LanguageManager.applyLanguage(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_language_settings)

        val listView = findViewById<ListView>(R.id.languageListView)
        val languages = LanguageManager.supportedLanguages
        val currentLang = LanguageManager.getSelectedLanguage(this)

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice,
            languages.map { "${it.nativeName} (${it.displayName})" })
        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_SINGLE

        val currentIndex = languages.indexOfFirst { it.code == currentLang.code }
        if (currentIndex >= 0) {
            listView.setItemChecked(currentIndex, true)
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val selected = languages[position]
            LanguageManager.setLanguage(this, selected)
            Toast.makeText(this, "Language: ${selected.nativeName}", Toast.LENGTH_SHORT).show()
            recreate()
        }
    }
}
