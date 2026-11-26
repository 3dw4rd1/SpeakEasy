package com.kaurisystems.speakeasy

import android.text.InputType

/**
 * The number pad shown instead of the letters when a field only takes digits: a PIN, a code,
 * a quantity, a phone number, a date or time. side is the three extra keys down the right.
 */
enum class Keypad(val side: List<String>) {
    NONE(emptyList()),
    NUMBER(listOf("-", ".", ",")),
    PHONE(listOf("+", "*", "#")),
    DATETIME(listOf("/", ":", "-"));

    companion object {
        /** Which pad a field wants, from its EditorInfo.inputType. */
        fun forInputType(inputType: Int): Keypad = when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> NUMBER
            InputType.TYPE_CLASS_PHONE -> PHONE
            InputType.TYPE_CLASS_DATETIME -> DATETIME
            else -> NONE
        }
    }
}
