package com.kaurisystems.speakeasy

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.TextView
import java.util.concurrent.Executors

/** Edits a server-side text file: the dictionary or the snippets. */
class EditorActivity : Activity() {

    companion object {
        const val KIND = "kind"
        const val DICTIONARY = "dictionary"
        const val SNIPPETS = "snippets"
    }

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var api: Api
    private lateinit var editor: EditText
    private lateinit var status: TextView
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = Api(Prefs(this))
        val kind = intent.getStringExtra(KIND) ?: DICTIONARY
        val path = if (kind == SNIPPETS) "/v1/snippets" else "/v1/dictionary"

        screen {
            if (kind == SNIPPETS) {
                title = "Snippets"
                addView(heading("Snippets"))
                addView(note(
                    "Say \"insert <trigger>\" (or just the trigger where it fits) and the saved text " +
                    "goes in exactly as written.\n\nFormat: trigger on the first line, the text " +
                    "below it, and --- between snippets:\n\nmy address\n41 Example Street\nMelbourne VIC 3000\n---\n" +
                    "booking link\nhttps://…"))
            } else {
                title = "Dictionary"
                addView(heading("Dictionary"))
                addView(note(
                    "Names and words SpeakEasy should always spell your way, one per line.\n\n" +
                    "If it keeps mishearing something, add a rule:  misheard -> Correct\n" +
                    "e.g.  steel master -> SteelMaster\n\nLines starting with # are notes. " +
                    "Words learned from your History corrections are added at the bottom."))
            }
            status = note("Loading…")
            addView(status)
            editor = EditText(this@EditorActivity).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                gravity = Gravity.TOP
                minLines = 12
                typeface = Typeface.MONOSPACE
                textSize = 14f
                isEnabled = false
            }
            addView(editor)
            addView(button("Save") { save(path) })
        }
        load(path)
    }

    private fun load(path: String) {
        worker.execute {
            val r = runCatching { api.getText(path) }
            runOnUiThread {
                r.onSuccess {
                    editor.setText(it); editor.isEnabled = true; loaded = true
                    status.text = "Saved on your server. Changes apply to your next dictation."
                }.onFailure { status.text = "Couldn't load: ${it.message}" }
            }
        }
    }

    private fun save(path: String) {
        if (!loaded) return
        status.text = "Saving…"
        val text = editor.text.toString()
        worker.execute {
            val r = runCatching { api.putText(path, text) }
            runOnUiThread {
                status.text = r.fold({ "Saved ✓" }, { "Couldn't save: ${it.message}" })
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
