package com.kaurisystems.speakeasy

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/**
 * Past dictations. Tap one to copy it, fix it (which teaches SpeakEasy), or retry a failed one.
 */
class HistoryActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var history: History
    private lateinit var api: Api
    private lateinit var list: ListView
    private var entries: List<HistoryEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "History"
        history = History(this)
        api = Api(Prefs(this))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), 0)
        }
        col.addView(note("Tap an entry to copy it, or fix the wording so SpeakEasy learns how you " +
            "want it. Long-press to clear all history."))
        list = ListView(this)
        col.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(col)

        list.setOnItemClickListener { _, _, pos, _ -> showEntry(entries[pos]) }
        list.setOnItemLongClickListener { _, _, _, _ -> confirmClear(); true }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        entries = history.all()
        val rows = entries.map { e ->
            val time = DateUtils.getRelativeTimeSpanString(e.at).toString()
            val tag = when {
                e.error.isNotEmpty() -> "⚠ FAILED"
                e.mode == "braindump" -> "Brain dump"
                else -> appName(e.app)
            }
            val body = if (e.error.isNotEmpty()) "Tap to retry - ${e.error}" else e.text
            "$time · $tag\n${body.take(160)}"
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            rows.ifEmpty { listOf("Nothing yet. Dictate something with the SpeakEasy keyboard.") })
        list.isEnabled = entries.isNotEmpty()
    }

    private fun appName(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg.substringAfterLast('.').ifEmpty { "Unknown app" } }

    private fun showEntry(e: HistoryEntry) {
        if (e.error.isNotEmpty()) { retry(e); return }

        val pad = dp(16)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val edit = EditText(this).apply {
            setText(e.text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP
            minLines = 3
        }
        box.addView(edit)
        box.addView(TextView(this).apply {
            text = "What it heard:\n${e.raw}"; textSize = 13f; alpha = 0.7f; setPadding(0, pad / 2, 0, 0)
        })

        AlertDialog.Builder(this)
            .setTitle("Edit to teach SpeakEasy")
            .setView(box)
            .setPositiveButton("Save correction") { _, _ ->
                val fixed = edit.text.toString().trim()
                if (fixed.isNotEmpty() && fixed != e.text) sendCorrection(e, fixed)
            }
            .setNeutralButton("Copy") { _, _ -> copy(edit.text.toString()) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun sendCorrection(e: HistoryEntry, fixed: String) {
        history.replace(e, e.copy(text = fixed))
        refresh()
        copy(fixed)
        worker.execute {
            val r = runCatching { api.correction(e.raw, e.text, fixed, e.app) }
            runOnUiThread {
                val msg = r.fold(
                    { learned -> if (learned.isEmpty()) "Correction saved - SpeakEasy will learn from it"
                                 else "Learned: " + learned.joinToString(", ") },
                    { "Couldn't send correction: ${it.message}" })
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun retry(e: HistoryEntry) {
        val audio = File(e.audioPath)
        if (!audio.exists()) {
            Toast.makeText(this, "The recording is gone, sorry", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Retrying…", Toast.LENGTH_SHORT).show()
        val category = Category.of(e.app)
        val req = DictationRequest(audio, e.durationMs, e.app, category, Prefs(this).style(category),
            e.mode, "", "")
        worker.execute {
            val r = runCatching { api.dictate(req) }
            runOnUiThread {
                r.onSuccess { res ->
                    history.replace(e, e.copy(raw = res.raw, text = res.text, error = "", audioPath = ""))
                    audio.delete()
                    copy(res.text)
                    Toast.makeText(this, "Done - copied to clipboard", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this, "Still failing: ${it.message}", Toast.LENGTH_LONG).show()
                }
                refresh()
            }
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage("Clear all history on this phone?")
            .setPositiveButton("Clear") { _, _ -> history.clear(); refresh() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copy(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("SpeakEasy", text))
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
