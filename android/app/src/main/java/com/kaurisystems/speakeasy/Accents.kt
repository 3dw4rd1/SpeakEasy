package com.kaurisystems.speakeasy

/**
 * Accented letters offered when a letter key is held down (like Samsung Keyboard).
 * Macrons come first so te reo Māori words (Māori, whānau, tēnā koe) are one hold-and-release away.
 */
object Accents {
    private val map = mapOf(
        'a' to "āàáâäãåæą",
        'c' to "çćč",
        'd' to "ďð",
        'e' to "ēèéêëęėě",
        'g' to "ğ",
        'i' to "īìíîïį",
        'l' to "ł",
        'n' to "ñńň",
        'o' to "ōòóôöõøœ",
        'r' to "ř",
        's' to "ßśš",
        't' to "ťþ",
        'u' to "ūùúûüůų",
        'y' to "ýÿ",
        'z' to "žźż",
    )

    /** Accents for this letter (lower case), or empty if it has none. */
    fun forLetter(c: Char): List<String> = map[c.lowercaseChar()]?.map { it.toString() } ?: emptyList()
}
