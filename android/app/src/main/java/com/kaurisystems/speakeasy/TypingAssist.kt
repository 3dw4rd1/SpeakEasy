package com.kaurisystems.speakeasy

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Word suggestions and autocorrect for typing.
 *
 * Everything comes from the app itself: a built-in word list (assets/words.txt, NZ spellings)
 * plus your SpeakEasy dictionary, the Kiwi slang list and words you've taught it. It used to
 * ask the phone's spell checker, but many phones (Samsung especially) ship with that switched
 * off, which left the suggestion bar empty.
 */
class TypingAssist(
    private val context: Context,
    private val onSuggestions: (Speller.Result) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val sp = context.getSharedPreferences("speakeasy", Context.MODE_PRIVATE)

    @Volatile private var speller: Speller? = null
    private var vocab: List<String> = emptyList()
    private var learned: Set<String> = sp.getStringSet("learned_words", emptySet())?.toSet() ?: emptySet()
    private var sequence = 0
    private var last: Speller.Result? = null
    private var lastAtStart = false

    init {
        // 60,000 words take a moment to read, so do it off the main thread, once.
        worker.execute {
            val words = runCatching {
                context.assets.open("words.txt").bufferedReader().useLines { lines ->
                    lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
                }
            }.getOrDefault(emptyList())
            val s = Speller(words)
            main.post { speller = s; applyPersonal() }
        }
    }

    fun stop() { worker.shutdownNow() }

    /**
     * Dictionary terms from the server's vocab.txt ("misheard -> Right" lines give "Right"),
     * plus the built-in slang list (one term per line).
     */
    fun setVocab(text: String, slang: String = "") {
        vocab = (text.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { if ("->" in it) it.substringAfter("->").trim() else it } +
            slang.lines().map { it.trim() })
            .filter { it.isNotEmpty() }
            .distinct()
        applyPersonal()
    }

    /** You kept a word we didn't know (undid its correction, or tapped it): never touch it again. */
    fun learn(word: String) {
        if (word.length < 2 || word in learned || word in vocab) return
        learned = (learned + word).toList().takeLast(2000).toSet()
        sp.edit().putStringSet("learned_words", learned).apply()
        applyPersonal()
    }

    private fun applyPersonal() {
        speller?.setPersonal(vocab + learned)
        last = null
    }

    /** The word being typed changed: work out suggestions in the background, then publish them. */
    fun update(word: String, sentenceStart: Boolean) {
        val seq = ++sequence
        val s = speller
        if (word.isEmpty() || s == null) {
            publish(Speller.Result(word, emptyList(), null), sentenceStart)
            return
        }
        worker.execute {
            val result = s.check(word, sentenceStart)
            main.post { if (seq == sequence) publish(result, sentenceStart) }   // else: typed on since
        }
    }

    private fun publish(result: Speller.Result, sentenceStart: Boolean) {
        last = result
        lastAtStart = sentenceStart
        onSuggestions(result)
    }

    /** The correction to apply when this word is finished with space/punctuation, if any. */
    fun autocorrectFor(word: String, sentenceStart: Boolean): String? {
        if (word.isEmpty()) return null
        last?.let { if (it.word == word && lastAtStart == sentenceStart) return it.autocorrect }
        // Typed faster than the background lookup: check right now (a few milliseconds).
        return speller?.check(word, sentenceStart)?.autocorrect
    }
}
