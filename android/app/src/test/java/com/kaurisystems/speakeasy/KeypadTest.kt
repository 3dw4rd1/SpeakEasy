package com.kaurisystems.speakeasy

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Test

class KeypadTest {
    private fun pad(type: Int) = Keypad.forInputType(type)

    @Test fun pinAndNumberFieldsGetTheNumberPad() {
        assertEquals(Keypad.NUMBER, pad(InputType.TYPE_CLASS_NUMBER))
        assertEquals(Keypad.NUMBER,
            pad(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertEquals(Keypad.NUMBER, pad(InputType.TYPE_CLASS_NUMBER or
            InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED))
    }

    @Test fun phoneAndDateFieldsGetTheirOwnPads() {
        assertEquals(Keypad.PHONE, pad(InputType.TYPE_CLASS_PHONE))
        assertEquals(Keypad.DATETIME,
            pad(InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME))
    }

    @Test fun textFieldsKeepTheLetters() {
        assertEquals(Keypad.NONE, pad(0))
        assertEquals(Keypad.NONE, pad(InputType.TYPE_CLASS_TEXT))
        assertEquals(Keypad.NONE,
            pad(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertEquals(Keypad.NONE, pad(InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
    }

    @Test fun everyPadHasThreeSideKeys() {
        Keypad.values().filter { it != Keypad.NONE }.forEach { assertEquals(3, it.side.size) }
    }
}
