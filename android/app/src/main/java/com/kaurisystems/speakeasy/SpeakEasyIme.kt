package com.kaurisystems.speakeasy

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/**
 * QWERTY keyboard with a one-tap voice bar on top.
 *
 * Tap the mic bar: it records, stops by itself when you pause (or on a second tap), and types
 * the cleaned text at the cursor. "Dump" switches to brain-dump mode: ramble, tap to finish,
 * and an organised note goes in.
 */
class SpeakEasyIme : InputMethodService() {

    private enum class State { IDLE, RECORDING, BUSY }
    private enum class Shift { OFF, ONCE, LOCK }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private lateinit var api: Api
    private lateinit var recorder: Recorder
    private lateinit var history: History

    private var root: LinearLayout? = null
    private var micBar: LinearLayout? = null
    private var micIcon: ImageView? = null
    private var status: TextView? = null
    private var dumpKey: TextView? = null
    private var keysArea: LinearLayout? = null
    private val letterKeys = mutableListOf<TextView>()
    private var shiftKey: TextView? = null
    private var settingsKey: TextView? = null
    private var updateAvailable = false

    private var state = State.IDLE
    private var brainDump = false
    private var shift = Shift.OFF
    private var lastShiftTap = 0L
    private var symbols = false
    private var idleText = "Tap to talk"

    // Auto-stop, adaptive to the room and the phone's mic level (they vary a lot by device).
    private val silenceMs = 2000L
    private val maxRecordMs = 10 * 60 * 1000L
    private var noiseFloor = 0.0
    private var speechLevel = 0.0
    private var heardSpeech = false
    private var quietSince = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        api = Api(prefs)
        recorder = Recorder(this)
        history = History(this)
    }

    // ---- layout ------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 8) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }

    override fun onCreateInputView(): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.kb_bg))
            setPadding(dp(4), dp(6), dp(4), dp(8))
        }
        r.addView(toolbar())
        keysArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        r.addView(keysArea)
        root = r
        buildKeys()
        render()
        return r
    }

    private fun toolbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        dumpKey = key("Dump", 0f, dp(64), textSize = 14f) {
            if (state == State.IDLE) {
                brainDump = !brainDump
                idleText = if (brainDump) "Brain dump: tap, ramble, tap to finish" else "Tap to talk"
                render()
            }
        }.also { it.layoutParams = LinearLayout.LayoutParams(dp(64), dp(46)).apply { marginEnd = dp(6) } }
        bar.addView(dumpKey)

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            isClickable = true
            setOnClickListener { it.haptic(); toggle() }
        }
        micIcon = ImageView(this).apply { setImageResource(R.drawable.ic_mic) }
        pill.addView(micIcon, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(8) })
        status = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt()); textSize = 15f; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        pill.addView(status)
        micBar = pill
        bar.addView(pill, LinearLayout.LayoutParams(0, dp(46), 1f))

        val gear = key("⚙", 0f, dp(46), textSize = 20f) { openSettings() }
        settingsKey = gear
        bar.addView(gear, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(6) })

        val switchKb = key("⌨", 0f, dp(46), textSize = 20f) { switchKeyboard() }
        bar.addView(switchKb, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(4) })
        return bar
    }

    /** Check GitHub for a newer version at most every 6 hours; show a dot on ⚙ if there is one. */
    private fun checkForUpdateQuietly() {
        val sp = getSharedPreferences("speakeasy", MODE_PRIVATE)
        val updater = Updater(this)
        val now = System.currentTimeMillis()
        if (now - sp.getLong("update_checked_at", 0) > 6 * 60 * 60 * 1000L) {
            sp.edit().putLong("update_checked_at", now).apply()
            worker.execute {
                val latest = runCatching { updater.latest()?.code }.getOrNull() ?: return@execute
                sp.edit().putLong("latest_code", latest).apply()
                main.post { render() }
            }
        }
        updateAvailable = sp.getLong("latest_code", 0) > updater.currentCode
    }

    /** A key: TextView on a rounded background. width 0 + weight, or fixed width with weight 0. */
    private fun key(label: String, weight: Float, width: Int = 0, textSize: Float = 20f,
                    onTap: (() -> Unit)?): TextView = TextView(this).apply {
        text = label
        this.textSize = textSize
        gravity = Gravity.CENTER
        setTextColor(getColor(R.color.key_text))
        background = rounded(getColor(R.color.key_bg))
        layoutParams = LinearLayout.LayoutParams(width, dp(48), weight).apply {
            setMargins(dp(3), dp(4), dp(3), dp(4))
        }
        if (onTap != null) setOnClickListener { it.haptic(); onTap() }
    }

    private fun row(vararg views: View, sidePad: Float = 0f): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            if (sidePad > 0) addView(View(this@SpeakEasyIme), LinearLayout.LayoutParams(0, 1, sidePad))
            views.forEach { addView(it) }
            if (sidePad > 0) addView(View(this@SpeakEasyIme), LinearLayout.LayoutParams(0, 1, sidePad))
        }

    private fun buildKeys() {
        val area = keysArea ?: return
        area.removeAllViews()
        letterKeys.clear()
        shiftKey = null
        val special = getColor(R.color.key_special)

        if (!symbols) {
            area.addView(row(*"qwertyuiop".map { letter(it) }.toTypedArray()))
            area.addView(row(*"asdfghjkl".map { letter(it) }.toTypedArray(), sidePad = 0.5f))
            val sh = key("⇧", 1.5f) { onShift() }.apply { background = rounded(special) }
            shiftKey = sh
            area.addView(row(sh, *"zxcvbnm".map { letter(it) }.toTypedArray(), backspaceKey()))
        } else {
            area.addView(row(*"1234567890".map { char(it.toString()) }.toTypedArray()))
            area.addView(row(*listOf("@", "#", "$", "&", "-", "+", "(", ")", "/").map { char(it) }.toTypedArray(),
                sidePad = 0.5f))
            area.addView(row(*listOf("=", "*", "\"", "'", ":", ";", "!", "?").map { punct(it) }.toTypedArray(),
                backspaceKey()))
        }

        val toggle = key(if (symbols) "ABC" else "?123", 1.5f, textSize = 15f) {
            symbols = !symbols; buildKeys(); render()
        }.apply { background = rounded(special) }
        val space = key("space", 5f, textSize = 15f) { onSpace() }
        val enter = key("⏎", 1.5f) { enter() }.apply { background = rounded(special) }
        area.addView(row(toggle, punct(","), space, punct("."), enter))
    }

    private fun letter(c: Char): TextView = key(c.toString(), 1f) {
        val upper = shift != Shift.OFF
        commit(if (upper) c.uppercase() else c.toString())
        if (shift == Shift.ONCE) { shift = Shift.OFF; render() }
    }.also { letterKeys.add(it) }

    private fun char(s: String): TextView = key(s, 1f) { commit(s) }

    /** . , ? ! etc: no space before, one space after (like phone keyboards). */
    private fun punct(s: String): TextView = key(s, 1f) {
        val ic = currentInputConnection ?: return@key
        val autoSpace = s in listOf(".", ",", "?", "!", ":", ";")
        if (autoSpace) {
            if (ic.getTextBeforeCursor(1, 0)?.toString() == " ") ic.deleteSurroundingText(1, 0)
            ic.commitText("$s ", 1)
            if (s in listOf(".", "?", "!")) autoCaps()
        } else ic.commitText(s, 1)
    }

    private fun backspaceKey(): TextView {
        val k = key("⌫", 1.5f, onTap = null).apply { background = rounded(getColor(R.color.key_special)) }
        val repeat = object : Runnable {
            override fun run() {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                main.postDelayed(this, 60)
            }
        }
        k.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.haptic()
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                    main.postDelayed(repeat, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> main.removeCallbacks(repeat)
            }
            true
        }
        return k
    }

    private fun onShift() {
        val now = System.currentTimeMillis()
        shift = when {
            shift == Shift.ONCE && now - lastShiftTap < 400 -> Shift.LOCK   // double tap = caps lock
            shift == Shift.OFF -> Shift.ONCE
            else -> Shift.OFF
        }
        lastShiftTap = now
        render()
    }

    private fun onSpace() {
        val ic = currentInputConnection ?: return
        // A punctuation key already added the space: don't double it.
        val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
        if (before.length == 2 && before[1] == ' ' && before[0] in ".,?!:;") return
        ic.commitText(" ", 1)
    }

    /** Turn on one-shot shift where the field expects a capital (start of a sentence). */
    private fun autoCaps() {
        val ic = currentInputConnection ?: return
        val type = currentInputEditorInfo?.inputType ?: 0
        if (shift != Shift.LOCK) {
            shift = if (type != 0 && ic.getCursorCapsMode(type) != 0) Shift.ONCE else Shift.OFF
            render()
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        symbols = false
        buildKeys()
        autoCaps()
        checkForUpdateQuietly()
        render()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Keyboard hidden mid-recording: don't lose a brain dump; drop a short dictation.
        if (state == State.RECORDING) {
            if (brainDump) stopAndSend() else {
                recorder.cancel()
                main.removeCallbacks(meter)
                state = State.IDLE
            }
        }
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        recorder.cancel()
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---- dictation -------------------------------------------------------

    private fun toggle() {
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopAndSend()
            State.BUSY -> Unit
        }
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            flash("Microphone not allowed - opening settings")
            openSettings()
            return
        }
        if (prefs.token.isBlank()) {
            flash("No access token - opening settings")
            openSettings()
            return
        }
        try {
            recorder.start()
        } catch (e: Exception) {
            flash("Couldn't start the mic: ${e.message}")
            return
        }
        state = State.RECORDING
        heardSpeech = false
        quietSince = 0L
        noiseFloor = 0.0
        speechLevel = 0.0
        render()
        main.postDelayed(meter, 100)
    }

    private val meter = object : Runnable {
        override fun run() {
            if (state != State.RECORDING) return
            val elapsed = recorder.elapsedMs
            val amp = recorder.amplitude().toDouble()
            val now = System.currentTimeMillis()

            if (amp > 0) {
                // Noise floor: follows quiet readings down fast, creeps up slowly.
                noiseFloor = when {
                    noiseFloor == 0.0 -> amp
                    amp < noiseFloor -> noiseFloor * 0.6 + amp * 0.4
                    else -> noiseFloor * 1.01
                }
                val speechThreshold = maxOf(noiseFloor * 3, 300.0)
                if (amp > speechThreshold && elapsed > 300) {
                    heardSpeech = true
                    speechLevel = if (speechLevel == 0.0) amp else speechLevel * 0.8 + amp * 0.2
                    quietSince = 0L
                } else {
                    val quietThreshold = maxOf(noiseFloor * 2, speechLevel * 0.15)
                    if (amp < quietThreshold) { if (quietSince == 0L) quietSince = now }
                    else quietSince = 0L
                }
            }

            val secs = elapsed / 1000
            val label = if (brainDump) "Brain dump" else "Listening"
            status?.text = "%s  %d:%02d · tap to finish".format(label, secs / 60, secs % 60)

            // Brain dumps have long thinking pauses: only stop on a tap.
            val pauseOver = !brainDump && prefs.autoStop && heardSpeech &&
                quietSince != 0L && now - quietSince >= silenceMs
            if (pauseOver || elapsed >= maxRecordMs) { stopAndSend(); return }
            main.postDelayed(this, 100)
        }
    }

    private fun stopAndSend() {
        main.removeCallbacks(meter)
        val ms = recorder.stop()
        if (ms < 400) {
            state = State.IDLE
            flash("Too short - tap and speak")
            return
        }
        state = State.BUSY
        render()

        val info = currentInputEditorInfo
        val pkg = info?.packageName ?: ""
        val category = Category.of(pkg)
        val mode = if (brainDump) "braindump" else "dictate"
        val context = if (prefs.sendContext) {
            currentInputConnection?.getTextBeforeCursor(1500, 0)?.toString() ?: ""
        } else ""
        // Move the audio out of the cache slot so the next recording can't overwrite it.
        val audio = File(cacheDir, "sending-${System.currentTimeMillis()}.m4a")
        recorder.file.renameTo(audio)
        val req = DictationRequest(
            audio = audio, durationMs = ms, app = pkg, category = category,
            style = prefs.style(category), mode = mode, context = context,
            hint = info?.hintText?.toString() ?: "",
        )

        worker.execute {
            val result = runCatching { api.dictate(req) }
            result.onSuccess { r ->
                audio.delete()
                if (r.text.isNotBlank()) {
                    history.add(HistoryEntry(System.currentTimeMillis(), pkg, mode, r.raw, r.text, durationMs = ms))
                }
            }.onFailure { e ->
                // Keep the audio so it can be retried from History.
                val kept = File(history.audioDir, audio.name)
                audio.renameTo(kept)
                history.add(HistoryEntry(System.currentTimeMillis(), pkg, mode, "", "",
                    error = e.message ?: "Network error", audioPath = kept.absolutePath, durationMs = ms))
            }
            main.post {
                state = State.IDLE
                if (brainDump) { brainDump = false; idleText = "Tap to talk" }
                result.onSuccess { r ->
                    if (r.text.isBlank()) flash("Didn't catch anything")
                    else { insert(r.text); render() }
                }.onFailure { e ->
                    flash((e.message ?: "Network error") + " - saved in History")
                }
            }
        }
    }

    /** Show a message in the bar for a few seconds, then go back to "Tap to talk". */
    private fun flash(msg: String) {
        render()
        status?.text = msg
        main.postDelayed({ if (state == State.IDLE) render() }, 4000)
    }

    // ---- text editing ----------------------------------------------------

    private fun insert(text: String) {
        val ic = currentInputConnection
        if (ic == null) {
            // Field went away while we were waiting: don't lose the dictation.
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("SpeakEasy", text))
            return
        }
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
        val needsSpace = before.isNotEmpty() && !before.last().isWhitespace() && !text.first().isWhitespace()
        ic.commitText(if (needsSpace) " $text" else text, 1)
        autoCaps()
    }

    private fun commit(s: String) { currentInputConnection?.commitText(s, 1) }

    private fun enter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val multiLine = ((info?.inputType ?: 0) and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val noEnterAction = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!multiLine && !noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            autoCaps()
        }
    }

    private fun switchKeyboard() {
        if (!switchToPreviousInputMethod()) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
    }

    // ---- UI --------------------------------------------------------------

    private fun render() {
        val bar = micBar ?: return
        val color = when {
            state == State.RECORDING -> R.color.mic_rec
            state == State.BUSY -> R.color.mic_busy
            brainDump -> R.color.mic_dump
            else -> R.color.mic_idle
        }
        bar.background = rounded(getColor(color), 23)
        micIcon?.setImageResource(if (state == State.RECORDING) R.drawable.ic_stop else R.drawable.ic_mic)
        bar.isEnabled = state != State.BUSY
        when (state) {
            State.IDLE -> status?.text = idleText
            State.BUSY -> status?.text = if (brainDump) "Organising your thoughts…" else "Writing…"
            State.RECORDING -> Unit  // the meter updates it
        }
        dumpKey?.background = rounded(getColor(if (brainDump) R.color.key_on else R.color.key_special))
        val sp = getSharedPreferences("speakeasy", MODE_PRIVATE)
        updateAvailable = sp.getLong("latest_code", 0) > Updater(this).currentCode
        settingsKey?.apply {
            // A dot on the gear when a new version is out; tapping it opens Settings → Update.
            text = if (updateAvailable) "⚙•" else "⚙"
            background = rounded(getColor(if (updateAvailable) R.color.mic_idle else R.color.key_special))
        }

        val upper = shift != Shift.OFF
        letterKeys.forEach { k -> k.text = if (upper) k.text.toString().uppercase() else k.text.toString().lowercase() }
        shiftKey?.apply {
            text = if (shift == Shift.LOCK) "⇪" else "⇧"
            background = rounded(getColor(if (shift != Shift.OFF) R.color.key_on else R.color.key_special))
            setTypeface(null, if (shift != Shift.OFF) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun View.haptic() { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
}
