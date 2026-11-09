package com.kaurisystems.speakeasy

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
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
import android.widget.Toast
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
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button
    private lateinit var updater: Updater
    private var release: Updater.Release? = null
    private var checkedForUpdate = false
    private var installing = false
    private var wantsInstall = false
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    // ---- self-update -------------------------------------------------------

    private fun checkForUpdate() {
        updateStatus.text = "Version ${updater.currentName} · checking for updates…"
        worker.execute {
            val r = runCatching { updater.latest() }
            main.post {
                r.onSuccess { rel ->
                    // Shared with the keyboard's ⚙ update dot.
                    if (rel != null) getSharedPreferences("speakeasy", MODE_PRIVATE).edit()
                        .putLong("latest_code", rel.code)
                        .putLong("update_checked_at", System.currentTimeMillis()).apply()
                    if (rel != null && rel.code > updater.currentCode) {
                        release = rel
                        updateStatus.text = "Version ${updater.currentName} · update ${rel.name} available"
                        updateButton.text = "Update to ${rel.name}"
                        updateButton.visibility = View.VISIBLE
                    } else {
                        updateStatus.text = "Version ${updater.currentName} · up to date"
                        updateButton.visibility = View.GONE
                    }
                }.onFailure {
                    updateStatus.text = "Version ${updater.currentName} · couldn't check for updates"
                }
            }
        }
    }

    private fun promptUpdate() {
        val rel = release ?: return
        AlertDialog.Builder(this)
            .setTitle("SpeakEasy ${rel.name}")
            .setMessage(rel.notes.ifBlank { "A new version is ready." })
            .setPositiveButton("Install") { _, _ -> installUpdate(rel) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun installUpdate(rel: Updater.Release) {
        if (installing) return
        if (!updater.canInstall()) {
            // One-off: Android needs SpeakEasy to be allowed to install apps.
            wantsInstall = true
            Toast.makeText(this, "Allow SpeakEasy to install updates, then press back", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        installing = true
        updateButton.isEnabled = false
        updateStatus.text = "Downloading ${rel.name}…"
        worker.execute {
            val r = runCatching {
                updater.install(rel) { pct -> main.post { updateStatus.text = "Downloading ${rel.name}… $pct%" } }
            }
            main.post {
                installing = false
                updateButton.isEnabled = true
                updateStatus.text = r.fold({ "Confirm the install on the next screen" },
                                           { "Update failed: ${it.message}" })
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        updater = Updater(this)

        screen {
            addView(TextView(this@SettingsActivity).apply { text = "SpeakEasy"; textSize = 26f })
            steps = note("")
            addView(steps)
            updateStatus = note("")
            addView(updateStatus)
            updateButton = button("Update") { promptUpdate() }.apply { visibility = View.GONE }
            addView(updateButton)

            addView(heading("Your stuff"))
            addView(button("History") { open(HistoryActivity::class.java) })
            addView(button("Dictionary") { openEditor(EditorActivity.DICTIONARY) })
            addView(button("Snippets") { openEditor(EditorActivity.SNIPPETS) })
            addView(button("Report a bug or idea") { open(ReportActivity::class.java) })

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

    /** App type label, a full-width segmented Formal | Casual | Very casual control, and an example. */
    private fun styleRow(c: Category): View {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(4))
        }
        block.addView(TextView(this).apply { text = c.label; textSize = 15f })

        val example = TextView(this).apply { textSize = 13f; alpha = 0.65f; setPadding(dp(2), dp(2), 0, 0) }
        val segments = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = STYLE_LABELS.mapIndexed { i, label ->
            Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 13f
                minWidth = 0
                minimumWidth = 0
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = if (i < STYLE_LABELS.size - 1) dp(4) else 0 }
            }
        }
        val normalText = buttons[0].textColors
        fun select(key: String) {
            prefs.setStyle(c, key)
            buttons.forEachIndexed { i, b ->
                val on = STYLE_KEYS[i] == key
                b.backgroundTintList = if (on) ColorStateList.valueOf(getColor(R.color.mic_idle)) else null
                if (on) b.setTextColor(Color.WHITE) else b.setTextColor(normalText)
            }
            example.text = STYLE_EXAMPLES[STYLE_KEYS.indexOf(key)]
        }
        buttons.forEachIndexed { i, b ->
            b.setOnClickListener { select(STYLE_KEYS[i]) }
            segments.addView(b)
        }
        block.addView(segments)
        block.addView(example)
        select(prefs.style(c))
        return block
    }

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))

    private fun openEditor(kind: String) =
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.KIND, kind))

    override fun onResume() {
        super.onResume()
        refreshSteps()
        val rel = release
        if (wantsInstall && rel != null && updater.canInstall()) {
            // Back from granting "install unknown apps": carry on with the update.
            wantsInstall = false
            installUpdate(rel)
        } else if (!checkedForUpdate) {
            checkedForUpdate = true
            checkForUpdate()
        }
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
