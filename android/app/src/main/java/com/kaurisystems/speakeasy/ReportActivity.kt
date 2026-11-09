package com.kaurisystems.speakeasy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Report a bug or suggest an improvement. Reports go to the server and are worked through
 * with the /speakeasy-review skill; their status shows up here.
 */
class ReportActivity : Activity() {

    companion object {
        private const val ENTRY = "entry"

        /** Open the report screen with a History entry attached. */
        fun forEntry(context: Context, e: HistoryEntry): Intent =
            Intent(context, ReportActivity::class.java).putExtra(ENTRY, e.toJson().toString())
    }

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var api: Api
    private lateinit var reports: LinearLayout
    private var kind = "bug"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Report a bug or idea"
        api = Api(Prefs(this))
        val entry = intent.getStringExtra(ENTRY)?.let { HistoryEntry.fromJson(JSONObject(it)) }

        lateinit var input: EditText
        lateinit var status: TextView
        screen {
            addView(pageTitle("Report a bug or idea"))
            addView(note("Say what went wrong or what would make SpeakEasy better. You can dictate " +
                "this with SpeakEasy. Reports are reviewed and fixed in later updates."))

            val segs = LinearLayout(this@ReportActivity).apply { orientation = LinearLayout.HORIZONTAL }
            val kinds = listOf("bug" to "Something's wrong", "idea" to "Idea / improvement")
            val buttons = kinds.mapIndexed { i, (_, label) ->
                Button(this@ReportActivity).apply {
                    text = label; isAllCaps = false; textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginEnd = if (i == 0) dp(4) else 0 }
                }
            }
            fun select(k: String) {
                kind = k
                buttons.forEachIndexed { i, b -> markSegment(b, kinds[i].first == k) }
            }
            buttons.forEachIndexed { i, b -> b.setOnClickListener { select(kinds[i].first) }; segs.addView(b) }
            addView(segs)
            select("bug")

            input = EditText(this@ReportActivity).apply {
                hint = if (entry != null) "What should it have said?" else "What happened, or what would be better?"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                gravity = Gravity.TOP
                minLines = 5
            }
            addView(input)

            if (entry != null) {
                addView(note("Attached dictation:\nHeard: ${entry.raw}\nWrote: ${entry.text}"))
            }

            status = note("")
            addView(button("Send") {
                val body = input.text.toString().trim()
                if (body.isEmpty()) { status.text = "Write something first"; return@button }
                status.text = "Sending…"
                val version = Updater(this@ReportActivity).currentName
                worker.execute {
                    val r = runCatching { api.sendFeedback(kind, body, entry, version) }
                    runOnUiThread {
                        r.onSuccess { input.setText(""); status.text = "Sent ✓ thanks"; loadReports() }
                            .onFailure { status.text = "Couldn't send: ${it.message}" }
                    }
                }
            })
            addView(status)

            addView(heading("Your reports"))
            reports = LinearLayout(this@ReportActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(reports)
        }
        loadReports()
    }

    private fun loadReports() {
        worker.execute {
            val r = runCatching { api.reports() }
            runOnUiThread {
                reports.removeAllViews()
                r.onSuccess { list ->
                    if (list.isEmpty()) reports.addView(note("None yet."))
                    list.forEach { rep ->
                        val badge = when (rep.status) {
                            "fixed" -> "✓ Fixed" + if (rep.version.isNotEmpty()) " in ${rep.version}" else ""
                            "planned" -> "◷ Planned"
                            "wontfix" -> "– Not doing"
                            else -> "• Open"
                        }
                        val date = rep.at.take(10)
                        val extra = if (rep.note.isNotEmpty()) "\n→ ${rep.note}" else ""
                        reports.addView(note("$badge · $date · ${if (rep.kind == "idea") "Idea" else "Bug"}\n" +
                            rep.text.take(200) + extra))
                    }
                }.onFailure { reports.addView(note("Couldn't load: ${it.message}")) }
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
