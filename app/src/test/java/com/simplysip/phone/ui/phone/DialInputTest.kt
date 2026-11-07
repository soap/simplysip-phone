package com.simplysip.phone.ui.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class DialInputTest {
    @Test
    fun `accepts keypad characters`() {
        val typed = "*43#0".fold("") { acc, c -> DialInput.append(acc, c) }
        assertEquals("*43#0", typed)
    }

    @Test
    fun `ignores other characters and plus after the start`() {
        assertEquals("+", DialInput.append("", '+'))
        assertEquals("66", DialInput.append("66", '+'))
        assertEquals("66", DialInput.append("66", 'a'))
    }

    @Test
    fun `stops at the maximum length`() {
        val full = "1".repeat(DialInput.MAX_LENGTH)
        assertEquals(full, DialInput.append(full, '2'))
    }

    @Test
    fun `backspace removes the last key`() {
        assertEquals("20", DialInput.backspace("202"))
        assertEquals("", DialInput.backspace(""))
    }
}
