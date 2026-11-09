package com.kaurisystems.speakeasy

import android.content.Context
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager

/**
 * Word suggestions and autocorrect for typing.
 *
 * Uses the phone's own spell checker (Samsung's or Google's, whichever is set in Android) for
 * corrections, plus prefix completions from the SpeakEasy dictionary so Jotun, SteelMaster and
 * customer names are offered and never "corrected" away.
 */
class TypingAssist(
    private val context: Context,
    private val onSuggestions: (word: String, suggestions: List<String>) -> Unit,
) : SpellCheckerSession.SpellCheckerSessionListener {

    private var session: SpellCheckerSession? = null
    private var vocab: List<String> = emptyList()
    private var vocabLower: Set<String> = emptySet()
    private var sequence = 0

    // Result of the last spell check, for the word it was asked about.
    private var checkedWord = ""
    private var checkedSuggestions: List<String> = emptyList()
    private var checkedIsTypo = false

    fun start() {
        if (session != null) return
        val tsm = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager
        session = runCatching { tsm?.newSpellCheckerSession(null, null, this, true) }.getOrNull()
    }

    fun stop() {
        session?.close()
        session = null
    }

    /** Dictionary terms from the server's vocab.txt ("misheard -> Right" lines give "Right"). */
    fun setVocab(text: String) {
        vocab = text.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { if ("->" in it) it.substringAfter("->").trim() else it }
            .filter { it.isNotEmpty() }
            .distinct()
        vocabLower = vocab.flatMap { it.lowercase().split(" ") }.toSet()
    }

    fun inVocab(word: String) = word.lowercase() in vocabLower

    /** The word being typed changed: publish dictionary completions now, spell-check async. */
    fun update(word: String) {
        pendingWord = word
        if (word.isEmpty()) {
            checkedWord = ""
            onSuggestions("", emptyList())
            return
        }
        onSuggestions(word, merge(word, completions(word), emptyList()))
        val s = session ?: return
        sequence++
        s.getSentenceSuggestions(arrayOf(TextInfo(word, 0, word.length, sequence, sequence)), 3)
    }

    override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
        val sentence = results?.firstOrNull() ?: return
        if (sentence.suggestionsCount == 0) return
        val info = sentence.getSuggestionsInfoAt(0)
        if (info.sequence != sequence) return          // a newer word has been typed since
        handle(info)
    }

    override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
        val info = results?.firstOrNull() ?: return
        if (info.sequence != sequence) return
        handle(info)
    }

    private fun handle(info: SuggestionsInfo) {
        val attrs = info.suggestionsAttributes
        val suggestions = (0 until maxOf(info.suggestionsCount, 0)).map { info.getSuggestionAt(it) }
        val word = pendingWord
        checkedWord = word
        checkedSuggestions = suggestions
        checkedIsTypo = (attrs and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY) == 0 &&
            (attrs and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) != 0 &&
            (attrs and SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS) != 0
        onSuggestions(word, merge(word, completions(word), suggestions))
    }

    /** The word most recently passed to update(). */
    private var pendingWord = ""

    /** The correction to apply when this word is finished with space/punctuation, if any. */
    fun autocorrectFor(word: String): String? {
        if (word.length < 2 || word != checkedWord || !checkedIsTypo || inVocab(word)) return null
        val best = checkedSuggestions.firstOrNull() ?: return null
        if (best.equals(word, ignoreCase = true)) return null
        return matchCase(word, best)
    }

    private fun completions(word: String): List<String> {
        if (word.length < 2) return emptyList()
        val w = word.lowercase()
        return vocab.filter { it.length > word.length && it.lowercase().startsWith(w) }.take(2)
    }

    /** Typed word first (so you can keep it), then dictionary completions, then spell-check fixes. */
    private fun merge(word: String, fromVocab: List<String>, fromSpell: List<String>): List<String> =
        (listOf(word) + fromVocab + fromSpell.map { matchCase(word, it) })
            .distinctBy { it.lowercase() }
            .take(3)

    companion object {
        fun matchCase(typed: String, s: String): String = when {
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> s.uppercase()
            typed.firstOrNull()?.isUpperCase() == true -> s.replaceFirstChar { it.uppercase() }
            else -> s
        }
    }
}
