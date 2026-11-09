package com.kaurisystems.speakeasy

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.concurrent.Executors

/** Small helpers for building screens in code (no AndroidX, no XML per screen). */
fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Activity.heading(t: String) = TextView(this).apply {
    text = t; textSize = 18f; setPadding(0, dp(20), 0, dp(4))
}

fun Activity.note(t: String) = TextView(this).apply {
    text = t; textSize = 14f; alpha = 0.75f; setPadding(0, dp(2), 0, dp(6))
}

fun Activity.button(t: String, onClick: () -> Unit) = Button(this).apply {
    text = t; isAllCaps = false; setOnClickListener { onClick() }
}

fun Activity.screen(build: LinearLayout.() -> Unit): LinearLayout {
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(32))
        build()
    }
    setContentView(ScrollView(this).apply { addView(col) })
    return col
}

/** Home screen: setup, styles, and links to Dictionary / Snippets / History. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var steps: TextView
    private lateinit var result: TextView
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        screen {
            addView(TextView(this@SettingsActivity).apply { text = "SpeakEasy"; textSize = 26f })
            steps = note("")
            addView(steps)

            addView(heading("Your stuff"))
            addView(button("History") { open(HistoryActivity::class.java) })
            addView(button("Dictionary") { openEditor(EditorActivity.DICTIONARY) })
            addView(button("Snippets") { openEditor(EditorActivity.SNIPPETS) })

            addView(heading("Style"))
            addView(note("How polished the text is, by the kind of app you're typing in."))
            for (c in Category.values()) addView(styleRow(c))

            addView(heading("Options"))
            addView(CheckBox(this@SettingsActivity).apply {
                text = "Stop automatically when I pause (one tap to dictate)"
                isChecked = prefs.autoStop
                setOnCheckedChangeListener { _, on -> prefs.autoStop = on }
            })
            addView(CheckBox(this@SettingsActivity).apply {
                text = "Use the text already in the field for context (better names and continuations)"
                isChecked = prefs.sendContext
                setOnCheckedChangeListener { _, on -> prefs.sendContext = on }
            })

            addView(heading("Setup"))
            addView(button("1. Allow microphone") {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            })
            addView(button("2. Turn on the SpeakEasy keyboard") {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            })
            addView(button("3. Switch to SpeakEasy now") {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            })

            addView(note("Server"))
            val server = EditText(this@SettingsActivity).apply {
                setText(prefs.server); inputType = InputType.TYPE_TEXT_VARIATION_URI; isSingleLine = true
            }
            addView(server)
            addView(note("Access token"))
            val token = EditText(this@SettingsActivity).apply {
                setText(prefs.token)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                isSingleLine = true
                hint = "paste token"
            }
            addView(token)
            result = note("")
            addView(button("Save and test connection") {
                prefs.server = server.text.toString().ifBlank { DEFAULT_SERVER }
                prefs.token = token.text.toString()
                server.setText(prefs.server)
                testConnection()
            })
            addView(result)

            addView(heading("Try it here"))
            addView(EditText(this@SettingsActivity).apply {
                hint = "Tap here, switch to SpeakEasy, tap the mic"
                minLines = 4
            })
        }
    }

    private fun styleRow(c: Category): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply {
            text = c.label; textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, STYLE_LABELS)
        spinner.setSelection(STYLE_KEYS.indexOf(prefs.style(c)).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.setStyle(c, STYLE_KEYS[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        row.addView(spinner)
        return row
    }

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))

    private fun openEditor(kind: String) =
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.KIND, kind))

    override fun onResume() {
        super.onResume()
        refreshSteps()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, perms, grants)
        refreshSteps()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun refreshSteps() {
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val tokenOk = prefs.token.isNotBlank()
        fun tick(ok: Boolean) = if (ok) "✓" else "✗"
        steps.text = "${tick(micOk)} Microphone allowed   ${tick(enabled)} Keyboard on   ${tick(tokenOk)} Token saved"
    }

    private fun testConnection() {
        refreshSteps()
        result.text = "Testing…"
        val api = Api(prefs)
        worker.execute {
            val msg = try { api.check() } catch (e: Exception) { "Can't reach server: ${e.message}" }
            main.post { result.text = msg }
        }
    }
}
