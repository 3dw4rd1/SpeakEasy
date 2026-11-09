package com.kaurisystems.speakeasy

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/**
 * QWERTY keyboard with a one-tap voice bar on top and a suggestion strip.
 *
 * - Tap the mic bar: it records, stops by itself when you pause (or on a second tap), and types
 *   the cleaned text at the cursor.
 * - Select text first and the mic bar edits it instead: say "make this more formal" etc.
 * - "Dump" switches to brain-dump mode: ramble, tap to finish, and an organised note goes in.
 * - The strip shows word suggestions while typing, and ↶ Undo right after a dictation or edit.
 */
class SpeakEasyIme : InputMethodService() {

    private enum class State { IDLE, RECORDING, BUSY }
    private enum class Shift { OFF, ONCE, LOCK }

    /** Text we put in, and what it replaced (for an edit), so it can be undone. */
    private data class Insertion(val text: String, val original: String, val until: Long)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private lateinit var api: Api
    private lateinit var recorder: Recorder
    private lateinit var history: History
    private lateinit var assist: TypingAssist

    private var root: LinearLayout? = null
    private var micBar: LinearLayout? = null
    private var micIcon: ImageView? = null
    private var status: TextView? = null
    private var dumpKey: TextView? = null
    private var strip: LinearLayout? = null
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
    private var emojiMode = false
    private var emojiTab = 0
    private var idleText = "Tap to talk"

    // Field and selection state.
    private var suggestionsOn = true
    private var autocorrectOn = true
    private var passwordField = false
    private var privateField = false   // incognito tab etc.: dictation works, nothing is saved
    private var hasSelection = false
    private var editSelection: String? = null       // the selection being voice-edited
    private var lastAutocorrect: Pair<String, String>? = null   // typed -> corrected
    private var lastInsert: Insertion? = null

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
        assist = TypingAssist(this) { word, sugs -> showSuggestions(word, sugs) }
        assist.setVocab(sp().getString("vocab_cache", "") ?: "")
    }

    private fun sp() = getSharedPreferences("speakeasy", MODE_PRIVATE)

    // ---- layout ------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 8) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }

    /**
     * Key background: the rounded key is drawn inset inside its cell, so the gaps you see
     * between keys still belong to a key. No dead spots between keys (like Samsung's keyboard).
     */
    private fun keyBg(color: Int) = InsetDrawable(rounded(color), dp(3), dp(4), dp(3), dp(4))

    override fun onCreateInputView(): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.kb_bg))
            setPadding(dp(4), dp(6), dp(4), dp(8))
        }
        r.addView(toolbar())
        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        r.addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))
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
            setPadding(0, 0, 0, dp(2))
        }
        dumpKey = key("Dump", 0f, dp(64), textSize = 14f) {
            if (state == State.IDLE) {
                brainDump = !brainDump
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
            ellipsize = TextUtils.TruncateAt.END
        }
        pill.addView(status)
        micBar = pill
        bar.addView(pill, LinearLayout.LayoutParams(0, dp(46), 1f))

        val gear = key("⚙", 0f, dp(46), textSize = 20f) { openSettings() }
        settingsKey = gear
        bar.addView(gear, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(6) })

        val switchKb = key("⌨", 0f, dp(46), textSize = 20f) { switchKeyboard() }
            .apply { background = rounded(getColor(R.color.key_special)) }
        bar.addView(switchKb, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(4) })
        return bar
    }

    /** Check GitHub for a newer version at most every 6 hours; show a dot on ⚙ if there is one. */
    private fun checkForUpdateQuietly() {
        val updater = Updater(this)
        val now = System.currentTimeMillis()
        if (now - sp().getLong("update_checked_at", 0) > 6 * 60 * 60 * 1000L) {
            sp().edit().putLong("update_checked_at", now).apply()
            worker.execute {
                val latest = runCatching { updater.latest()?.code }.getOrNull() ?: return@execute
                sp().edit().putLong("latest_code", latest).apply()
                main.post { render() }
            }
        }
    }

    /** Keep a copy of the SpeakEasy dictionary for suggestions; refresh at most hourly. */
    private fun refreshVocabQuietly() {
        val now = System.currentTimeMillis()
        if (prefs.token.isBlank() || now - sp().getLong("vocab_fetched_at", 0) < 60 * 60 * 1000L) return
        sp().edit().putLong("vocab_fetched_at", now).apply()
        worker.execute {
            val text = runCatching { api.getText("/v1/dictionary") }.getOrNull() ?: return@execute
            sp().edit().putString("vocab_cache", text).apply()
            main.post { assist.setVocab(text) }
        }
    }

    /** A key: TextView on a rounded background. width 0 + weight, or fixed width with weight 0. */
    private fun key(label: String, weight: Float, width: Int = 0, textSize: Float = 20f,
                    onTap: (() -> Unit)?): TextView = TextView(this).apply {
        text = label
        this.textSize = textSize
        gravity = Gravity.CENTER
        setTextColor(getColor(R.color.key_text))
        background = keyBg(getColor(R.color.key_bg))
        layoutParams = LinearLayout.LayoutParams(width, dp(56), weight)
        if (onTap != null) setOnClickListener { it.haptic(); onTap() }
    }

    /**
     * A row of keys. sidePad indents the row (the a–l row is half a key in), and taps in that
     * indent go to the nearest key, so reaching for "a" at the edge still types "a".
     */
    private fun row(vararg views: View, sidePad: Float = 0f): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            fun edge(target: View) = View(this@SpeakEasyIme).apply {
                setOnClickListener { target.performClick() }
            }
            if (sidePad > 0) addView(edge(views.first()), LinearLayout.LayoutParams(0, dp(56), sidePad))
            views.forEach { addView(it) }
            if (sidePad > 0) addView(edge(views.last()), LinearLayout.LayoutParams(0, dp(56), sidePad))
        }

    private fun buildKeys() {
        val area = keysArea ?: return
        area.removeAllViews()
        letterKeys.clear()
        shiftKey = null
        val special = getColor(R.color.key_special)

        if (emojiMode) { buildEmoji(area); return }

        if (!symbols) {
            area.addView(row(*"qwertyuiop".map { letter(it) }.toTypedArray()))
            area.addView(row(*"asdfghjkl".map { letter(it) }.toTypedArray(), sidePad = 0.5f))
            val sh = key("⇧", 1.5f) { onShift() }.apply { background = keyBg(special) }
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
        }.apply { background = keyBg(special) }
        val emoji = key("😊", 1f) {
            emojiMode = true
            emojiTab = if (recentEmoji().isNotEmpty()) 0 else 1   // open on Recent once it has emojis
            buildKeys()
        }.apply { background = keyBg(special) }
        val space = key("space", 4f, textSize = 15f) { onSpace() }
        val enter = key("⏎", 1.5f) { enter() }.apply { background = keyBg(special) }
        area.addView(row(toggle, emoji, punct(","), space, punct("."), enter))
    }

    // ---- emoji panel -------------------------------------------------------

    private fun recentEmoji(): List<String> =
        (sp().getString("emoji_recent", "") ?: "").split(" ").filter { it.isNotBlank() }

    private fun addRecent(e: String) {
        if (privateField) return
        val list = (listOf(e) + recentEmoji().filter { it != e }).take(36)
        sp().edit().putString("emoji_recent", list.joinToString(" ")).apply()
    }

    /** Same height as the letter keys: category tabs, a scrolling grid, then ABC / space / ⌫. */
    private fun buildEmoji(area: LinearLayout) {
        val recent = recentEmoji()
        val tabs = listOf("🕘" to recent) + Emoji.categories   // Recent is always the first tab
        if (emojiTab >= tabs.size) emojiTab = 0

        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.forEachIndexed { i, (icon, _) ->
            tabRow.addView(TextView(this).apply {
                text = icon; textSize = 20f; gravity = Gravity.CENTER
                if (i == emojiTab) background = rounded(getColor(R.color.key_on))
                setOnClickListener { it.haptic(); emojiTab = i; buildKeys() }
            }, LinearLayout.LayoutParams(dp(44), dp(40)))
        }
        area.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))

        val items = tabs[emojiTab].second
        if (items.isEmpty()) {
            area.addView(TextView(this).apply {
                text = "Emojis you use will appear here"; textSize = 14f; gravity = Gravity.CENTER
                setTextColor(getColor(R.color.status))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(124)))
        } else {
            val grid = GridView(this).apply {
                numColumns = 8
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                isVerticalScrollBarEnabled = false
                adapter = object : BaseAdapter() {
                    override fun getCount() = items.size
                    override fun getItem(p: Int) = items[p]
                    override fun getItemId(p: Int) = p.toLong()
                    override fun getView(p: Int, convert: View?, parent: ViewGroup?): View =
                        ((convert as? TextView) ?: TextView(this@SpeakEasyIme).apply {
                            textSize = 26f; gravity = Gravity.CENTER
                            layoutParams = android.widget.AbsListView.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
                        }).apply { text = items[p] }
                }
                setOnItemClickListener { _, v, p, _ ->
                    v.haptic()
                    commit(items[p])
                    addRecent(items[p])
                }
            }
            area.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(124)))
        }

        val special = getColor(R.color.key_special)
        val abc = key("ABC", 1.5f, textSize = 15f) { emojiMode = false; symbols = false; buildKeys(); render() }
            .apply { background = keyBg(special) }
        val space = key("space", 5f, textSize = 15f) { onSpace() }
        area.addView(row(abc, space, backspaceKey()))
    }

    // ---- keys ----------------------------------------------------------------

    private fun letter(c: Char): TextView {
        val k = key(c.toString(), 1f) { typeLetter(c.toString()) }
        letterKeys.add(k)
        val accents = Accents.forLetter(c)
        if (accents.isNotEmpty()) holdForAccents(k, accents)
        return k
    }

    /** Types a letter (or accented letter) in the current shift state. */
    private fun typeLetter(s: String) {
        lastAutocorrect = null
        lastInsert = null
        val upper = shift != Shift.OFF
        // ß has no single-letter capital in Kotlin ("SS"), so it stays as it is.
        commit(if (upper && s.uppercase().length == s.length) s.uppercase() else s)
        if (shift == Shift.ONCE) { shift = Shift.OFF; render() }
    }

    // ---- accents (hold a letter) ---------------------------------------------

    /** The accents row on screen: which cell the finger is over, and where the cells are. */
    private class AccentRow(
        val window: PopupWindow,
        val cells: List<TextView>,
        val items: List<String>,
        val leftOnScreen: Int,
        val cellWidth: Int,
        val startX: Float,
    ) {
        var selected = 0
        var slid = false   // a small wobble as it opens shouldn't move off the first accent
    }

    private var accentRow: AccentRow? = null
    private val accentHoldMs = 350L
    private var fingerX = 0f

    /**
     * Hold a letter: a row of accents pops up above it. Slide along to pick one and let go to
     * type it; let go without sliding to type the first (highlighted) one. Like Samsung Keyboard.
     */
    private fun holdForAccents(k: TextView, accents: List<String>) {
        val show = Runnable {
            k.isPressed = false   // so the normal tap doesn't also fire on release
            k.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showAccents(k, accents)
        }
        k.setOnTouchListener { _, ev ->
            fingerX = ev.rawX
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { main.postDelayed(show, accentHoldMs); false }
                MotionEvent.ACTION_MOVE -> {
                    val row = accentRow ?: return@setOnTouchListener false
                    selectAccent(row, ev.rawX)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(show)
                    val row = accentRow ?: return@setOnTouchListener false
                    dismissAccents()
                    typeLetter(row.items[row.selected])
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(show)
                    dismissAccents()
                    false
                }
                else -> accentRow != null
            }
        }
    }

    private fun showAccents(k: TextView, accents: List<String>) {
        dismissAccents()
        val r = root ?: return
        val upper = shift != Shift.OFF
        val keyWin = IntArray(2).also { k.getLocationInWindow(it) }
        val keyScreen = IntArray(2).also { k.getLocationOnScreen(it) }
        val rootWin = IntArray(2).also { r.getLocationInWindow(it) }

        // On the right half of the keyboard the row grows leftwards (and is reversed), so the
        // first accent is always right above your finger.
        val rightSide = keyWin[0] + k.width / 2 > rootWin[0] + r.width / 2
        val items = if (rightSide) accents.reversed() else accents
        val cellW = minOf(dp(40), r.width / items.size)
        val cellH = dp(52)
        val width = cellW * items.size

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(getColor(R.color.key_special), 10)
            elevation = dp(6).toFloat()
        }
        val cells = items.map { a ->
            TextView(this).apply {
                text = if (upper && a.uppercase().length == a.length) a.uppercase() else a
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.key_text))
            }.also { bar.addView(it, LinearLayout.LayoutParams(cellW, cellH)) }
        }

        var left = if (rightSide) keyWin[0] + k.width - width else keyWin[0]
        left = left.coerceIn(rootWin[0], rootWin[0] + r.width - width)
        val top = keyWin[1] - cellH + dp(4)

        val window = PopupWindow(bar, width, cellH).apply {
            isClippingEnabled = false
            isTouchable = false        // the finger stays on the key; we track it from there
            setBackgroundDrawable(null)
        }
        window.showAtLocation(r, Gravity.NO_GRAVITY, left, top)

        val row = AccentRow(window, cells, items, left + (keyScreen[0] - keyWin[0]), cellW, fingerX)
        row.selected = if (rightSide) items.size - 1 else 0
        accentRow = row
        highlightAccent(row)
    }

    private fun selectAccent(row: AccentRow, rawX: Float) {
        if (!row.slid && Math.abs(rawX - row.startX) < dp(10)) return
        row.slid = true
        val i = ((rawX - row.leftOnScreen) / row.cellWidth).toInt().coerceIn(0, row.items.size - 1)
        if (i != row.selected) {
            row.selected = i
            highlightAccent(row)
        }
    }

    private fun highlightAccent(row: AccentRow) {
        row.cells.forEachIndexed { i, cell ->
            cell.background = if (i == row.selected) rounded(getColor(R.color.mic_idle), 8) else null
        }
    }

    private fun dismissAccents() {
        accentRow?.window?.dismiss()
        accentRow = null
    }

    private fun char(s: String): TextView = key(s, 1f) { lastAutocorrect = null; commit(s) }

    /** . , ? ! etc: autocorrect the word, no space before, one space after. */
    private fun punct(s: String): TextView = key(s, 1f) {
        val ic = currentInputConnection ?: return@key
        val autoSpace = s in listOf(".", ",", "?", "!", ":", ";")
        if (autoSpace) {
            if (!finishWord()) lastAutocorrect = null
            if (ic.getTextBeforeCursor(1, 0)?.toString() == " ") ic.deleteSurroundingText(1, 0)
            ic.commitText("$s ", 1)
            if (s in listOf(".", "?", "!")) autoCaps()
        } else {
            lastAutocorrect = null
            ic.commitText(s, 1)
        }
    }

    private fun backspaceKey(): TextView {
        val k = key("⌫", 1.5f, onTap = null).apply { background = keyBg(getColor(R.color.key_special)) }
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
                    // Backspace straight after an autocorrect puts back what you typed.
                    if (!revertAutocorrect()) {
                        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                        main.postDelayed(repeat, 400)
                    }
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
        if (!finishWord()) lastAutocorrect = null
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

    // ---- suggestions, autocorrect, undo -------------------------------------

    /** The word right before the cursor ("" if the cursor follows a space or punctuation). */
    private fun currentWord(): String {
        val before = currentInputConnection?.getTextBeforeCursor(48, 0)?.toString() ?: return ""
        return Regex("[\\p{L}\\p{N}'’-]+$").find(before)?.value ?: ""
    }

    private fun refreshWord() {
        if (!suggestionsOn || hasSelection || emojiMode) { showSuggestions("", emptyList()); return }
        assist.update(currentWord())
    }

    /** Autocorrect the word before the cursor if it's an obvious typo. Returns true if it did. */
    private fun finishWord(): Boolean {
        if (!autocorrectOn) return false
        val ic = currentInputConnection ?: return false
        val word = currentWord()
        // An accent you picked on purpose (Māori, café) is never "corrected" back to plain letters.
        if (word.any { it.isLetter() && it.code > 127 }) return false
        val fix = assist.autocorrectFor(word) ?: return false
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText(fix, 1)
        lastAutocorrect = word to fix
        return true
    }

    private fun revertAutocorrect(): Boolean {
        val (typed, fixed) = lastAutocorrect ?: return false
        lastAutocorrect = null
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(fixed.length + 2, 0)?.toString() ?: return false
        val tail = when {
            before.endsWith("$fixed ") -> ""
            before.length >= 2 && before.endsWith(" ") && before.dropLast(2).endsWith(fixed) -> before[before.length - 2].toString()
            else -> return false
        }
        ic.deleteSurroundingText(fixed.length + tail.length + 1, 0)
        ic.commitText(typed + tail, 1)
        return true
    }

    private fun pickSuggestion(word: String, choice: String) {
        val ic = currentInputConnection ?: return
        lastAutocorrect = null
        if (currentWord() == word) ic.deleteSurroundingText(word.length, 0)
        ic.commitText("$choice ", 1)
    }

    private fun showSuggestions(word: String, suggestions: List<String>) {
        val undo = lastInsert
        if (undo != null && System.currentTimeMillis() < undo.until) {
            fillStrip(listOf("↶ Undo" to { undoLast() }), bold = 0)
            return
        }
        if (word.isEmpty() || suggestions.isEmpty()) { fillStrip(emptyList(), -1); return }
        // Bold the word that space will put in (the autocorrection, if there is one).
        val fix = if (autocorrectOn) assist.autocorrectFor(word) else null
        val bold = suggestions.indexOfFirst { it == (fix ?: word) }
        fillStrip(suggestions.map { s -> s to { pickSuggestion(word, s) } }, bold)
    }

    private fun fillStrip(items: List<Pair<String, () -> Unit>>, bold: Int) {
        val s = strip ?: return
        s.removeAllViews()
        items.forEachIndexed { i, (label, action) ->
            if (i > 0) s.addView(View(this).apply { setBackgroundColor(getColor(R.color.key_bg)) },
                LinearLayout.LayoutParams(dp(1), dp(22)))
            s.addView(TextView(this).apply {
                text = label; textSize = 16f; gravity = Gravity.CENTER; maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(getColor(R.color.key_text))
                if (i == bold) setTypeface(null, Typeface.BOLD)
                setOnClickListener { it.haptic(); action() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun showUndo(text: String, original: String) {
        lastInsert = Insertion(text, original, System.currentTimeMillis() + 8000)
        showSuggestions("", emptyList())
        main.postDelayed({ refreshWord() }, 8100)
    }

    private fun undoLast() {
        val ins = lastInsert ?: return
        lastInsert = null
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(ins.text.length, 0)?.toString()
        if (before == ins.text) {
            ic.deleteSurroundingText(ins.text.length, 0)
            if (ins.original.isNotEmpty()) ic.commitText(ins.original, 1)
            flash(if (ins.original.isNotEmpty()) "Edit undone" else "Dictation removed")
        } else {
            flash("Can't undo - the text has changed")
        }
        refreshWord()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
                                   candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val sel = newSelEnd > newSelStart
        if (sel != hasSelection) {
            hasSelection = sel
            if (state == State.IDLE) render()
        }
        refreshWord()
    }

    // ---- field lifecycle -------------------------------------------------

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val type = info?.inputType ?: 0
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        passwordField = cls == InputType.TYPE_CLASS_TEXT && variation in listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        // Apps set this flag for incognito/private fields: the keyboard mustn't remember what's said.
        privateField = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        val noSuggestions = (type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        val addressy = variation in listOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI)
        suggestionsOn = cls == InputType.TYPE_CLASS_TEXT && !passwordField && !noSuggestions
        autocorrectOn = suggestionsOn && !addressy
        hasSelection = false
        lastAutocorrect = null
        lastInsert = null

        symbols = false
        emojiMode = false
        buildKeys()
        autoCaps()
        if (suggestionsOn) assist.start()
        checkForUpdateQuietly()
        refreshVocabQuietly()
        refreshWord()
        render()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        dismissAccents()
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
        assist.stop()
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
        if (passwordField) {
            flash("Voice is off in password fields")
            return
        }
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
        // With text selected, this recording is an instruction for how to change it.
        editSelection = if (brainDump) null
            else currentInputConnection?.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }
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
            val label = when {
                editSelection != null -> "Say the change"
                brainDump -> "Brain dump"
                else -> "Listening"
            }
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
            editSelection = null
            flash("Too short - tap and speak")
            return
        }
        state = State.BUSY
        render()

        val info = currentInputEditorInfo
        val pkg = info?.packageName ?: ""
        val category = Category.of(pkg)
        val selection = editSelection
        val mode = when {
            selection != null -> "edit"
            brainDump -> "braindump"
            else -> "dictate"
        }
        val context = if (prefs.sendContext) {
            currentInputConnection?.getTextBeforeCursor(1500, 0)?.toString() ?: ""
        } else ""
        // Move the audio out of the cache slot so the next recording can't overwrite it.
        val audio = File(cacheDir, "sending-${System.currentTimeMillis()}.m4a")
        recorder.file.renameTo(audio)
        val req = DictationRequest(
            audio = audio, durationMs = ms, app = pkg, category = category,
            style = prefs.style(category), mode = mode, context = context,
            hint = info?.hintText?.toString() ?: "", selection = selection ?: "",
            privateField = privateField,
        )
        val saveHistory = !privateField

        worker.execute {
            val result = runCatching { api.dictate(req) }
            result.onSuccess { r ->
                audio.delete()
                if (saveHistory && r.text.isNotBlank()) {
                    history.add(HistoryEntry(System.currentTimeMillis(), pkg, mode, r.raw, r.text, durationMs = ms))
                }
            }.onFailure { e ->
                if (!saveHistory) { audio.delete(); return@onFailure }
                // Keep the audio so it can be retried from History.
                val kept = File(history.audioDir, audio.name)
                audio.renameTo(kept)
                history.add(HistoryEntry(System.currentTimeMillis(), pkg, mode, "", "",
                    error = e.message ?: "Network error", audioPath = kept.absolutePath, durationMs = ms))
            }
            main.post {
                state = State.IDLE
                editSelection = null
                if (brainDump) brainDump = false
                result.onSuccess { r ->
                    when {
                        r.text.isBlank() -> flash("Didn't catch anything")
                        selection != null -> replaceSelection(selection, r.text)
                        else -> { insert(r.text); render() }
                    }
                }.onFailure { e ->
                    val where = if (saveHistory) " - saved in History" else ""
                    flash((e.message ?: "Network error") + where)
                }
            }
        }
    }

    /** Show a message in the bar for a few seconds, then go back to the normal label. */
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
            copyToClipboard(text)
            return
        }
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
        val needsSpace = before.isNotEmpty() && !before.last().isWhitespace() && !text.first().isWhitespace()
        val inserted = if (needsSpace) " $text" else text
        ic.commitText(inserted, 1)
        autoCaps()
        showUndo(inserted, "")
    }

    /** Swap the voice-edited selection for the rewritten text, if it's still selected. */
    private fun replaceSelection(original: String, rewritten: String) {
        val ic = currentInputConnection
        if (ic == null || ic.getSelectedText(0)?.toString() != original) {
            copyToClipboard(rewritten)
            flash("Selection changed - the edit is on your clipboard")
            return
        }
        ic.commitText(rewritten, 1)
        render()
        showUndo(rewritten, original)
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("SpeakEasy", text))
    }

    private fun commit(s: String) { currentInputConnection?.commitText(s, 1) }

    private fun enter() {
        lastAutocorrect = null
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
        val editing = hasSelection && !brainDump
        val color = when {
            state == State.RECORDING -> R.color.mic_rec
            state == State.BUSY -> R.color.mic_busy
            brainDump || editing -> R.color.mic_dump
            else -> R.color.mic_idle
        }
        bar.background = rounded(getColor(color), 23)
        micIcon?.setImageResource(if (state == State.RECORDING) R.drawable.ic_stop else R.drawable.ic_mic)
        bar.isEnabled = state != State.BUSY
        idleText = when {
            passwordField -> "Voice off in password fields"
            brainDump -> "Brain dump: tap, ramble, tap to finish"
            editing -> "Edit selection: tap and say the change"
            else -> "Tap to talk"
        }
        when (state) {
            State.IDLE -> status?.text = idleText
            State.BUSY -> status?.text = when {
                editSelection != null -> "Rewriting…"
                brainDump -> "Organising your thoughts…"
                else -> "Writing…"
            }
            State.RECORDING -> Unit  // the meter updates it
        }
        dumpKey?.background = rounded(getColor(if (brainDump) R.color.key_on else R.color.key_special))
        updateAvailable = sp().getLong("latest_code", 0) > Updater(this).currentCode
        settingsKey?.apply {
            // A dot on the gear when a new version is out; tapping it opens Settings → Update.
            text = if (updateAvailable) "⚙•" else "⚙"
            background = rounded(getColor(if (updateAvailable) R.color.mic_idle else R.color.key_special))
        }

        val upper = shift != Shift.OFF
        letterKeys.forEach { k -> k.text = if (upper) k.text.toString().uppercase() else k.text.toString().lowercase() }
        shiftKey?.apply {
            text = if (shift == Shift.LOCK) "⇪" else "⇧"
            background = keyBg(getColor(if (shift != Shift.OFF) R.color.key_on else R.color.key_special))
            setTypeface(null, if (shift != Shift.OFF) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun View.haptic() { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
}
