package com.edib.openwhispr

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Receives Android's native PROCESS_TEXT action from compatible text editors. */
class AddToDictionaryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addSelectedText(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        addSelectedText(intent)
    }

    private fun addSelectedText(source: Intent?) {
        val selected = source?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
        if (source?.action != Intent.ACTION_PROCESS_TEXT || selected.isBlank()) {
            Toast.makeText(this, R.string.dictionary_no_text, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val preferences = getSharedPreferences("openwhispr", MODE_PRIVATE)
        val words = Dictionary.load(preferences)
        Dictionary.save(preferences, Dictionary.withSelectedText(words, selected))
        Toast.makeText(this, R.string.dictionary_added, Toast.LENGTH_SHORT).show()
        finish()
    }
}
