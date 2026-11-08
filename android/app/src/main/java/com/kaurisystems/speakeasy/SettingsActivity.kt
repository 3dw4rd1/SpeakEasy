package com.kaurisystems.speakeasy

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/** One-screen setup: mic permission, enable + pick the keyboard, server token, test box. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var steps: TextView
    private lateinit var result: TextView
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(t: String, size: Float = 15f) = TextView(this).apply {
            text = t; textSize = size; setPadding(0, pad, 0, pad / 3)
        }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setOnClickListener { onClick() }
        }

        col.addView(label("SpeakEasy", 24f))
        steps = label("")
        col.addView(steps)

        col.addView(button("1. Allow microphone") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        })
        col.addView(button("2. Turn on the SpeakEasy keyboard") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        col.addView(button("3. Switch to SpeakEasy now") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })

        col.addView(label("Server"))
        val server = EditText(this).apply {
            setText(prefs.server)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        col.addView(server)

        col.addView(label("Access token (SPEAKEASY_TOKEN from the server .env)"))
        val token = EditText(this).apply {
            setText(prefs.token)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = true
            hint = "paste token"
        }
        col.addView(token)

        val autoStop = CheckBox(this).apply {
            text = "Stop automatically when I pause (one tap to dictate)"
            isChecked = prefs.autoStop
            setOnCheckedChangeListener { _, on -> prefs.autoStop = on }
        }
        col.addView(autoStop)

        result = label("")
        col.addView(button("Save and test connection") {
            prefs.server = server.text.toString().ifBlank { DEFAULT_SERVER }
            prefs.token = token.text.toString()
            server.setText(prefs.server)
            testConnection()
        })
        col.addView(result)

        col.addView(label("Try it here"))
        col.addView(EditText(this).apply {
            hint = "Tap here, switch to SpeakEasy, tap the mic"
            minLines = 4
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

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
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val tokenOk = prefs.token.isNotBlank()
        fun tick(ok: Boolean) = if (ok) "✓" else "✗"
        steps.text = "${tick(micOk)} Microphone allowed\n" +
            "${tick(enabled)} Keyboard turned on\n" +
            "${tick(tokenOk)} Access token saved"
    }

    private fun testConnection() {
        refreshSteps()
        result.text = "Testing…"
        val api = Api(prefs)
        worker.execute {
            val msg = try {
                api.check()
            } catch (e: Exception) {
                "Can't reach server: ${e.message}"
            }
            main.post { result.text = msg }
        }
    }
}
