package com.kaurisystems.speakeasy

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Run with: gradle testDebugUnitTest */
class SpellerTest {
    private val speller = Speller(File(System.getProperty("words") ?: "src/main/assets/words.txt").readLines())
        .apply { setPersonal(listOf("Jotun", "SteelMaster", "chur", "sweet as", "Kauri Systems")) }

    private fun fix(word: String, sentenceStart: Boolean = false) = speller.check(word, sentenceStart).autocorrect
    private fun offered(word: String) = speller.check(word).suggestions

    @Test fun fixesCommonTypos() {
        mapOf("teh" to "the", "adn" to "and", "wirk" to "work", "tomorow" to "tomorrow",
            "recieve" to "receive", "definately" to "definitely", "becuase" to "because",
            "thier" to "their", "freind" to "friend", "seperate" to "separate", "wich" to "which",
            "ot" to "to", "helllo" to "hello", "alot" to "a lot", "goin" to "going", "tommorrow" to "tomorrow", "probaly" to "probably",
            "thnaks" to "thanks", "wrok" to "work", "abotu" to "about", "peopel" to "people",
        ).forEach { (typed, want) -> assertEquals(typed, want, fix(typed)) }
    }

    @Test fun leavesRealWordsAlone() {
        listOf("the", "form", "colour", "organise", "tyre", "its", "well", "were", "Monday", "I", "a")
            .forEach { assertNull(it, fix(it)) }
        // Texting shorthand and stretched words aren't typos either.
        listOf("thx", "pls", "lol", "btw", "gonna", "yeah", "nah", "ok", "sooo", "nz", "ed", "u")
            .forEach { assertNull(it, fix(it)) }
    }

    @Test fun usesNewZealandSpelling() {
        assertEquals("colour", fix("colur"))
        assertEquals("organise", fix("organsie"))
        assertTrue(offered("colou").toString(), "colour" in offered("colou"))
        assertNull("short capitalised entries are offered, not forced", fix("eco"))
    }

    @Test fun addsCapitalsAndApostrophes() {
        assertEquals("Monday", fix("monday"))
        assertEquals("I", fix("i"))
        assertEquals("don't", fix("dont"))
        assertEquals("I'm", fix("im"))
        assertEquals("can't", fix("cant"))
        assertEquals("Auckland", fix("auckland"))
        assertEquals("Don't", fix("Dont", sentenceStart = true))
        assertTrue("it's" in offered("its"))
    }

    @Test fun completesWords() {
        assertTrue(offered("tomor").toString(), "tomorrow" in offered("tomor"))
        assertTrue(offered("definitel").toString(), "definitely" in offered("definitel"))
        assertNull(fix("tomor"))
        assertEquals("typed word comes first", "tomor", offered("tomor").first())
    }

    @Test fun keepsPersonalWordsAndNames() {
        listOf("Jotun", "chur", "SteelMaster", "Kauri").forEach { assertNull(it, fix(it)) }
        assertEquals("Jotun", fix("jotun"))
        assertEquals("Jotun", fix("Jotin"))
        assertTrue("SteelMaster" in offered("Stee"))
        assertNull("an unknown capitalised word mid-sentence is a name", fix("Bridgmann"))
        assertNull("so is anything in capitals", fix("TEH"))
        assertNull("or with a digit", fix("b2b"))
        speller.setPersonal(listOf("Jotun", "sam"))
        assertNull("a word you chose to keep stays as typed", fix("sam"))
    }

    @Test fun capitalsFollowWhatWasTyped() {
        assertEquals("The", fix("Teh", sentenceStart = true))
        assertTrue("TOMORROW" in offered("TOMOR"))
    }
}
