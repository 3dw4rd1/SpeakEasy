package com.kaurisystems.speakeasy

/**
 * Word completions and typo fixes from a built-in word list. No Android code in here, so it
 * can be tested on a computer.
 *
 * [words] is the list from assets/words.txt: most common first, in their proper capitals
 * (the, Monday, I'm). Personal words (the SpeakEasy dictionary, slang, words you've taught it)
 * are added with [setPersonal] and are never "corrected".
 */
class Speller(private val words: List<String>) {

    /** What to show for a word, and what space/punctuation should swap it for (null = leave it). */
    data class Result(val word: String, val suggestions: List<String>, val autocorrect: String?)

    private val lower = Array(words.size) { words[it].lowercase() }
    private val rank = HashMap<String, Int>(words.size * 2)        // lowercase word -> position
    private val folded = HashMap<String, Int>()                    // "dont" -> position of "don't"
    private val sorted: Array<Int>                                 // positions, in a-z order
    private val byLength = HashMap<Int, IntArray>()                // word length -> positions

    private var personal: List<String> = emptyList()
    private var personalLower: Set<String> = emptySet()

    init {
        for (i in lower.indices) {
            rank.putIfAbsent(lower[i], i)
            if ('\'' in lower[i]) folded.putIfAbsent(lower[i].replace("'", ""), i)
        }
        sorted = lower.indices.sortedBy { lower[it] }.toTypedArray()
        lower.indices.groupBy { lower[it].length }.forEach { (len, list) -> byLength[len] = list.toIntArray() }
    }

    /** Whole terms ("Jotun", "sweet as"). Each word in them counts as correctly spelt. */
    fun setPersonal(terms: Collection<String>) {
        personal = terms.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        personalLower = personal.flatMap { it.lowercase().split(' ', '-') }.toSet() +
            personal.map { it.lowercase() }
    }

    fun isPersonal(word: String) = norm(word) in personalLower

    fun isKnown(word: String): Boolean {
        val w = norm(word)
        return w in rank || w in personalLower || w.any { it.isDigit() }
    }

    /**
     * @param sentenceStart the word begins a sentence, so its capital letter is just grammar.
     *   Elsewhere a capitalised word we don't know is taken to be a name and left alone.
     */
    fun check(typed: String, sentenceStart: Boolean = false): Result {
        if (typed.isEmpty()) return Result(typed, emptyList(), null)
        val w = norm(typed)
        val allCaps = typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() }
        val capitalised = typed.first().isUpperCase()
        val known = isKnown(typed)
        val scored = ArrayList<Pair<String, Double>>()   // candidate, score (lower = better)
        var fix: String? = null

        // The same word in its proper form: monday -> Monday, i -> I, jotun -> Jotun, dont -> don't.
        // Not if you've told us you want it exactly as typed.
        val asTyped = typed in personal
        val properAt = rank[w]?.takeIf { words[it] != w }
        val personalProper = personal.firstOrNull { it.lowercase() == w && it != w }
        val proper = if (asTyped) null else personalProper ?: properAt?.let { words[it] }
        if (proper != null) {
            scored += proper to -2.0
            // Short capitalised entries are mostly abbreviations (Eco, Dir): offer, don't force.
            val sure = personalProper != null || w == "i" || (w.length >= 4 && properAt!! < 12000)
            if (!allCaps && sure) fix = proper
        }
        folded[w]?.let { i ->
            scored += words[i] to -1.0
            // "its", "well", "were" are words too: offer the apostrophe but don't force it.
            if (fix == null && !allCaps && (w !in rank || w in ALWAYS_APOSTROPHE)) fix = words[i]
        }

        SPLITS[w]?.let { scored += it to -1.5; if (fix == null && !allCaps) fix = it }

        // Finishing the word: personal terms first, then the most common dictionary words.
        if (w.length >= 2) {
            personal.filter { it.length > w.length && it.lowercase().startsWith(w) }.take(2)
                .forEachIndexed { n, term -> scored += term to 0.5 + n * 0.1 }
            for ((n, i) in completions(w, 3).withIndex()) {
                // A prefix that isn't a word yet is probably half-typed: completions lead.
                scored += words[i] to (if (known) 4.0 else 1.0) + n + commonness(i)
            }
        }

        // Typos: only for something that isn't a word.
        if (!known && fix == null && w.length >= 2 && w.all { it.isLetter() || it == '\'' }) {
            val budget = if (w.length >= 5) 22 else 12
            val near = nearest(w, budget)
            for ((i, cost) in near.take(4)) scored += display(i) to cost / 5.0 + commonness(i)
            val best = near.firstOrNull()
            // A capitalised word we don't know is a name, unless it's nearly one of your own terms.
            val name = capitalised && !sentenceStart && best != null && best.first >= 0
            // "sooo", "nooo": stretched on purpose.
            val stretched = (2 until w.length).any { w[it] == w[it - 1] && w[it] == w[it - 2] } && w.length <= 5
            if (best != null && !allCaps && !name && !stretched) {
                val (i, cost) = best
                val sure = when {
                    w.length == 2 -> cost <= 6 && i in 0 until 300    // "ot" -> "to", nothing bolder
                    // Three letters could be anything (thx, pls, btw): only the likeliest slips.
                    w.length == 3 -> cost <= 9 && i < 5000
                    cost <= 12 -> i < 40000
                    else -> w.length >= 6 && i < 20000              // two slips: long, common words only
                }
                if (sure) fix = display(i)
            }
        }

        val ranked = scored.sortedBy { it.second }.map { cased(typed, it.first) }
        val fixCased = fix?.let { cased(typed, it) }?.takeIf { it != typed }
        // Typed word first (tap it to keep it), then what space will put in, then the rest.
        val list = (listOf(typed) + listOfNotNull(fixCased) + ranked).distinct().take(3)
        return Result(typed, list, fixCased)
    }

    // ---- lookups -----------------------------------------------------------

    private fun display(i: Int) = if (i < 0) personal[-i - 1] else words[i]

    /** About 0.4 for the commonest words up to 1.9 for the rarest: breaks ties between candidates. */
    private fun commonness(i: Int): Double =
        if (i < 0) 0.4 else Math.log10(i + 10.0) / 2.5

    /** Positions of the most common words that start with [prefix] and are longer than it. */
    private fun completions(prefix: String, max: Int): List<Int> {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (lower[sorted[mid]] < prefix) lo = mid + 1 else hi = mid
        }
        val found = ArrayList<Int>()
        var n = lo
        while (n < sorted.size && lower[sorted[n]].startsWith(prefix)) {
            if (lower[sorted[n]].length > prefix.length) found += sorted[n]
            n++
        }
        found.sort()                       // position = how common
        return found.take(max)
    }

    /** Words within [budget] of [w] (about 10 = one slip), best first. Negative positions are personal terms. */
    private fun nearest(w: String, budget: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Double>>()
        val reach = budget / 10
        for (len in (w.length - reach)..(w.length + reach)) {
            for (i in byLength[len] ?: continue) {
                val cost = distance(w, lower[i], budget)
                if (cost > budget) continue
                // Abbreviations (DE, PMS) are in the list so they aren't flagged, not to be suggested.
                if (len > 1 && words[i].all { it.isUpperCase() }) continue
                out += Triple(i, cost, cost / 5.0 + commonness(i))
            }
        }
        personal.forEachIndexed { n, term ->
            val t = term.lowercase()
            if (' ' !in t && Math.abs(t.length - w.length) <= reach) {
                val cost = distance(w, t, budget)
                if (cost in 1..budget) out += Triple(-n - 1, cost, cost / 5.0 + commonness(-1))
            }
        }
        return out.sortedBy { it.third }.map { it.first to it.second }
    }

    /**
     * How far apart two words are, as typing slips. 10 = one wrong, missing or extra letter;
     * hitting the key next door, swapping two letters or mis-doubling a letter cost a bit less
     * because they're the commonest slips. Returns more than [budget] as soon as it's hopeless.
     */
    private fun distance(a: String, b: String, budget: Int): Int {
        val n = a.length
        val m = b.length
        if (Math.abs(n - m) * 6 > budget) return budget + 1
        var prev2 = IntArray(m + 1)
        var prev = IntArray(m + 1) { it * 10 }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i * 10
            var rowMin = cur[0]
            for (j in 1..m) {
                val ca = a[i - 1]
                val cb = b[j - 1]
                var best = prev[j - 1] + when {
                    ca == cb -> 0
                    i == 1 -> if (adjacent(ca, cb)) 9 else 14   // the first letter is rarely wrong
                    adjacent(ca, cb) -> 7
                    else -> 12                        // a key nowhere near: the least likely slip
                }
                // Extra letter in what was typed / letter left out. Doubled letters are cheap.
                val extra = if (i > 1 && ca == a[i - 2]) 6 else 10
                val missing = if (j > 1 && cb == b[j - 2]) 6 else if (cb == '\'') 2 else 10
                best = minOf(best, prev[j] + extra, cur[j - 1] + missing)
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) {
                    best = minOf(best, prev2[j - 2] + 6)   // two letters swapped
                }
                cur[j] = best
                if (best < rowMin) rowMin = best
            }
            if (rowMin > budget) return budget + 1
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[m]
    }

    private fun adjacent(a: Char, b: Char): Boolean {
        val pa = KEY_POS[a] ?: return false
        val pb = KEY_POS[b] ?: return false
        return Math.abs(pa.first - pb.first) <= 1 && Math.abs(pa.second - pb.second) <= 1.0
    }

    companion object {
        /** Curly apostrophes (from other keyboards / pasted text) count as plain ones. */
        private fun norm(word: String) = word.lowercase().replace('’', '\'')

        // Real but rare words that are nearly always a contraction missing its apostrophe.
        private val ALWAYS_APOSTROPHE = setOf("cant", "wont")

        // Two words run together.
        private val SPLITS = mapOf("alot" to "a lot", "aswell" to "as well", "infact" to "in fact",
            "incase" to "in case", "abit" to "a bit", "atleast" to "at least", "eachother" to "each other",
            "thankyou" to "thank you", "noone" to "no one", "everytime" to "every time")

        // Row and column of each letter on a QWERTY keyboard (rows are offset, like the keys).
        private val KEY_POS: Map<Char, Pair<Int, Double>> = HashMap<Char, Pair<Int, Double>>().apply {
            listOf("qwertyuiop" to 0.0, "asdfghjkl" to 0.5, "zxcvbnm" to 1.5).forEachIndexed { row, (keys, offset) ->
                keys.forEachIndexed { col, c -> put(c, row to col + offset) }
            }
        }

        /** Give a suggestion the capitals of what was typed, keeping any it has itself (I'm, Auckland). */
        fun cased(typed: String, s: String): String = when {
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> s.uppercase()
            typed.firstOrNull()?.isUpperCase() == true -> s.replaceFirstChar { it.uppercase() }
            else -> s
        }
    }
}
