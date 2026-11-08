package com.kaurisystems.speakeasy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Voice keyboard. One tap on the mic starts recording; it stops on a second tap or,
 * with auto-stop on, after a pause in speech. The cleaned text is typed at the cursor.
 */
class SpeakEasyIme : InputMethodService() {

    private enum class State { IDLE, RECORDING, BUSY }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private lateinit var api: Api
    private lateinit var recorder: Recorder

    private var status: TextView? = null
    private var mic: ImageButton? = null
    private var state = State.IDLE

    // Auto-stop: once speech is heard, stop after this much quiet.
    private val silenceMs = 2500L
    private val speechLevel = 2200
    private val quietLevel = 1300
    private val maxRecordMs = 5 * 60 * 1000L
    private var heardSpeech = false
    private var quietSince = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        api = Api(prefs)
        recorder = Recorder(this)
    }

    override fun onCreateInputView(): View {
        val v = layoutInflater.inflate(R.layout.keyboard, null)
        status = v.findViewById(R.id.status)
        mic = v.findViewById(R.id.mic)

        mic!!.setOnClickListener { it.haptic(); toggle() }
        v.findViewById<Button>(R.id.switch_kb).setOnClickListener { switchKeyboard() }
        v.findViewById<Button>(R.id.space).setOnClickListener { commit(" ") }
        v.findViewById<Button>(R.id.comma).setOnClickListener { commit(",") }
        v.findViewById<Button>(R.id.period).setOnClickListener { commit(".") }
        v.findViewById<Button>(R.id.enter).setOnClickListener { enter() }
        setupBackspace(v.findViewById(R.id.backspace))
        render()
        return v
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        render()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Keyboard hidden mid-recording: throw the audio away rather than type into a stale field.
        if (state == State.RECORDING) {
            recorder.cancel()
            main.removeCallbacks(meter)
            state = State.IDLE
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
            setStatus("Microphone not allowed - opening SpeakEasy settings")
            openSettings()
            return
        }
        if (prefs.token.isBlank()) {
            setStatus("No access token - opening SpeakEasy settings")
            openSettings()
            return
        }
        try {
            recorder.start()
        } catch (e: Exception) {
            setStatus("Couldn't start the mic: ${e.message}")
            return
        }
        state = State.RECORDING
        heardSpeech = false
        quietSince = 0L
        render()
        main.post(meter)
    }

    private val meter = object : Runnable {
        override fun run() {
            if (state != State.RECORDING) return
            val elapsed = recorder.elapsedMs
            val amp = recorder.amplitude()
            val now = System.currentTimeMillis()
            if (amp >= speechLevel) { heardSpeech = true; quietSince = 0L }
            else if (amp < quietLevel) { if (quietSince == 0L) quietSince = now }
            else quietSince = 0L

            val secs = elapsed / 1000
            setStatus("Listening  %d:%02d   (tap to finish)".format(secs / 60, secs % 60))

            val pauseOver = prefs.autoStop && heardSpeech && quietSince != 0L && now - quietSince >= silenceMs
            if (pauseOver || elapsed >= maxRecordMs) { stopAndSend(); return }
            main.postDelayed(this, 100)
        }
    }

    private fun stopAndSend() {
        main.removeCallbacks(meter)
        val ms = recorder.stop()
        if (ms < 400) {
            state = State.IDLE
            render()
            setStatus("Too short - tap and speak")
            return
        }
        state = State.BUSY
        render()
        setStatus("Writing…")
        val pkg = currentInputEditorInfo?.packageName ?: ""
        val audio = recorder.file
        worker.execute {
            val result = runCatching { api.dictate(audio, ms, pkg) }
            audio.delete()
            main.post {
                state = State.IDLE
                render()
                result.onSuccess { text ->
                    if (text.isBlank()) setStatus("Didn't catch anything")
                    else { insert(text); setStatus("Tap to talk") }
                }.onFailure { e ->
                    setStatus(e.message ?: "Network error")
                }
            }
        }
    }

    // ---- text editing ----------------------------------------------------

    private fun insert(text: String) {
        val ic = currentInputConnection
        if (ic == null) {
            // Field went away while we were waiting: don't lose the dictation.
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("SpeakEasy", text))
            return
        }
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
        val needsSpace = before.isNotEmpty() && !before.last().isWhitespace()
        ic.commitText(if (needsSpace) " $text" else text, 1)
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
        }
    }

    private fun setupBackspace(button: Button) {
        val repeat = object : Runnable {
            override fun run() {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                main.postDelayed(this, 60)
            }
        }
        button.setOnTouchListener { v, ev ->
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
    }

    private fun switchKeyboard() {
        if (!switchToPreviousInputMethod()) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
    }

    // ---- UI --------------------------------------------------------------

    private fun render() {
        val m = mic ?: return
        val color = when (state) {
            State.IDLE -> R.color.mic_idle
            State.RECORDING -> R.color.mic_rec
            State.BUSY -> R.color.mic_busy
        }
        m.backgroundTintList = ColorStateList.valueOf(getColor(color))
        m.setImageResource(if (state == State.RECORDING) R.drawable.ic_stop else R.drawable.ic_mic)
        m.isEnabled = state != State.BUSY
        if (state == State.IDLE && status?.text.isNullOrBlank()) setStatus("Tap to talk")
    }

    private fun setStatus(s: String) { status?.text = s }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun View.haptic() { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
}
