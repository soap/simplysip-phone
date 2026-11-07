package com.simplysip.phone.ui.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatDurationTest {
    @Test
    fun `formats minutes and hours`() {
        assertEquals("00:00", formatDuration(999))
        assertEquals("01:05", formatDuration(65_000))
        assertEquals("1:00:01", formatDuration(3_601_000))
    }
}
